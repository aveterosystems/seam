// SPDX-License-Identifier: AGPL-3.0-only
// The relay's protocol logic for one pairing group, kept free of Cloudflare APIs so it runs in
// plain Node tests. The Durable Object (pair-group.ts) is a thin adapter over it.
//
// Privacy: nothing here logs. The relay stores member keys, queued sealed envelopes until they're
// acked or expire, opaque prekey bundles and one pairing slot; no history.

import { create, fromBinary, type MessageInitShape, toBinary } from "@bufbuild/protobuf";
import { type Envelope, EnvelopeSchema } from "./generated/seam/v1/envelope_pb.js";
import { type ClientFrame, ClientFrameSchema, ServerFrameSchema } from "./generated/seam/v1/relay_pb.js";

export interface Storage {
  get<T>(key: string): Promise<T | undefined>;
  put(key: string, value: unknown): Promise<void>;
  delete(key: string): Promise<boolean>;
  /** Entries whose key starts with prefix, in key order. */
  list<T>(prefix: string): Promise<Map<string, T>>;
}

/** What survives hibernation for each socket. */
export interface SocketState {
  group: string; // hex
  nonce: string; // hex
  device?: string; // hex relay key, once logged in
}

export interface Socket {
  send(data: Uint8Array): void;
  close(code: number, reason: string): void;
  state(): SocketState | null;
  setState(s: SocketState): void;
}

interface Queued {
  msgId: string; // hex
  bytes: Uint8Array; // the serialized Envelope
  expires: number; // ms
}

interface Pairing {
  hash: string; // hex SHA-256 of the token
  expires: number; // ms
}

export const LOGIN_CONTEXT = new TextEncoder().encode("seam-relay-login-v1");
export const MAX_ENVELOPE = 1_100_000;
export const MAX_QUEUE_COUNT = 1000;
export const MAX_QUEUE_BYTES = 10 * 1024 * 1024;
const MAX_TTL_S = 86_400;
const MAX_PAIRING_TTL_S = 300;
const POLICY = 1008; // WebSocket close: policy violation

export const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
export const unhex = (s: string) => new Uint8Array(s.match(/../g)?.map((h) => parseInt(h, 16)) ?? []);

export class Group {
  constructor(
    private readonly storage: Storage,
    /** Every open socket on this group's object. */
    private readonly sockets: () => Socket[],
    private readonly now: () => number = Date.now,
  ) {}

  /** A new connection: send it a fresh challenge. */
  open(socket: Socket, group: Uint8Array): void {
    const nonce = crypto.getRandomValues(new Uint8Array(32));
    socket.setState({ group: hex(group), nonce: hex(nonce) });
    this.send(socket, { case: "challenge", value: { nonce } });
  }

  async message(socket: Socket, data: ArrayBuffer | string): Promise<void> {
    const state = socket.state();
    if (!state) return socket.close(POLICY, "no state");
    let frame: ClientFrame;
    try {
      if (typeof data === "string") throw new Error("text frame");
      frame = fromBinary(ClientFrameSchema, new Uint8Array(data));
    } catch {
      return this.fail(socket, "bad_frame");
    }

    if (!state.device) {
      if (frame.frame.case !== "login") return this.fail(socket, "bad_login", true);
      return this.login(socket, state, frame.frame.value);
    }
    const me = state.device;
    switch (frame.frame.case) {
      case "send":
        return this.relay(socket, me, frame.frame.value);
      case "delivered":
        return this.delivered(me, frame.frame.value.msgId);
      case "putBundles":
        await this.storage.put(`b:${me}`, frame.frame.value.bundles);
        return;
      case "getBundle":
        return this.getBundle(socket, frame.frame.value.deviceKey);
      case "openPairing":
        return this.openPairing(socket, frame.frame.value.tokenHash, frame.frame.value.ttlS);
      default:
        return this.fail(socket, "bad_frame");
    }
  }

  private async login(
    socket: Socket,
    state: SocketState,
    login: { groupId: Uint8Array; deviceKey: Uint8Array; signature: Uint8Array; joinToken: Uint8Array },
  ): Promise<void> {
    if (hex(login.groupId) !== state.group || login.deviceKey.length !== 32) return this.fail(socket, "bad_login", true);
    const signed = concat(LOGIN_CONTEXT, unhex(state.nonce), login.groupId);
    if (!(await verifyEd25519(login.deviceKey, login.signature, signed))) return this.fail(socket, "bad_login", true);

    const device = hex(login.deviceKey);
    // Hash before touching storage: from here to the put below only storage is awaited, so the
    // Durable Object's input gate keeps this read-modify-write atomic (one token, one join).
    const hash = login.joinToken.length > 0 ? hex(new Uint8Array(await crypto.subtle.digest("SHA-256", login.joinToken))) : "";
    const members = (await this.storage.get<string[]>("members")) ?? [];
    let joined = false;
    if (login.joinToken.length > 0) {
      const slot = await this.storage.get<Pairing>("pairing");
      if (!slot || slot.expires <= this.now() || slot.hash !== hash) return this.fail(socket, "bad_token", true);
      await this.storage.delete("pairing"); // single use
      if (!members.includes(device)) {
        members.push(device);
        joined = true;
      }
    } else if (members.length === 0) {
      members.push(device); // the first device creates the group
    } else if (!members.includes(device)) {
      return this.fail(socket, "bad_login", true);
    }
    await this.storage.put("members", members);

    socket.setState({ ...state, device });
    this.send(socket, { case: "loginResult", value: { ok: true, members: members.map(unhex) } });
    if (joined) {
      for (const other of this.connected()) {
        if (other.state()?.device !== device) this.send(other, { case: "peerJoined", value: { deviceKey: login.deviceKey } });
      }
    }
    await this.flush(socket, device);
  }

  /** Delivers everything queued for this device, oldest first, dropping what has expired. */
  private async flush(socket: Socket, device: string): Promise<void> {
    for (const [key, q] of await this.storage.list<Queued>(`q:${device}:`)) {
      if (q.expires <= this.now()) await this.storage.delete(key);
      else this.sendRaw(socket, deliverFrame(q.bytes));
    }
  }

  private async relay(socket: Socket, me: string, env: Envelope): Promise<void> {
    const to = hex(env.to);
    const members = (await this.storage.get<string[]>("members")) ?? [];
    if (hex(env.from) !== me || to === me || !members.includes(to)) return this.fail(socket, "not_member");
    const bytes = toBinary(EnvelopeSchema, env);
    if (bytes.length > MAX_ENVELOPE) return this.fail(socket, "too_large");

    const queue = await this.storage.list<Queued>(`q:${to}:`);
    let size = 0;
    for (const q of queue.values()) size += q.bytes.length;
    if (queue.size >= MAX_QUEUE_COUNT || size + bytes.length > MAX_QUEUE_BYTES) return this.fail(socket, "queue_full");

    const seq = ((await this.storage.get<number>("seq")) ?? 0) + 1;
    await this.storage.put("seq", seq);
    const ttl = Math.min(env.ttlS || MAX_TTL_S, MAX_TTL_S);
    // Zero-padded so storage key order is arrival order
    await this.storage.put(`q:${to}:${String(seq).padStart(16, "0")}`, {
      msgId: hex(env.msgId),
      bytes,
      expires: this.now() + ttl * 1000,
    } satisfies Queued);

    const frame = deliverFrame(bytes);
    for (const s of this.connected()) if (s.state()?.device === to) this.sendRaw(s, frame);
  }

  private async delivered(me: string, msgId: Uint8Array): Promise<void> {
    const id = hex(msgId);
    for (const [key, q] of await this.storage.list<Queued>(`q:${me}:`)) {
      if (q.msgId === id) await this.storage.delete(key);
    }
  }

  private async getBundle(socket: Socket, deviceKey: Uint8Array): Promise<void> {
    const device = hex(deviceKey);
    const members = (await this.storage.get<string[]>("members")) ?? [];
    if (!members.includes(device)) return this.fail(socket, "not_member");
    const bundles = (await this.storage.get<Uint8Array[]>(`b:${device}`)) ?? [];
    let bundle: Uint8Array = new Uint8Array();
    if (bundles.length > 1) {
      bundle = bundles.shift()!;
      await this.storage.put(`b:${device}`, bundles);
    } else if (bundles.length === 1) {
      bundle = bundles[0]!; // the last one is kept as a last resort
    }
    this.send(socket, { case: "bundle", value: { deviceKey, bundle } });
  }

  private async openPairing(socket: Socket, tokenHash: Uint8Array, ttlS: number): Promise<void> {
    if (tokenHash.length !== 32) return this.fail(socket, "bad_frame");
    const ttl = Math.min(ttlS || MAX_PAIRING_TTL_S, MAX_PAIRING_TTL_S);
    await this.storage.put("pairing", { hash: hex(tokenHash), expires: this.now() + ttl * 1000 } satisfies Pairing);
  }

  private connected(): Socket[] {
    return this.sockets().filter((s) => s.state()?.device);
  }

  private fail(socket: Socket, code: string, close = false): void {
    this.send(socket, { case: "error", value: { code } });
    if (close) socket.close(POLICY, code);
  }

  private send(socket: Socket, frame: MessageInitShape<typeof ServerFrameSchema>["frame"]): void {
    this.sendRaw(socket, toBinary(ServerFrameSchema, create(ServerFrameSchema, { frame })));
  }

  private sendRaw(socket: Socket, bytes: Uint8Array): void {
    try {
      socket.send(bytes);
    } catch {
      // The socket closed under us; queued envelopes stay until acked
    }
  }
}

function deliverFrame(envelope: Uint8Array): Uint8Array {
  const value = fromBinary(EnvelopeSchema, envelope);
  return toBinary(ServerFrameSchema, create(ServerFrameSchema, { frame: { case: "deliver", value } }));
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

async function verifyEd25519(publicKey: Uint8Array, signature: Uint8Array, data: Uint8Array): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey("raw", publicKey, { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "Ed25519" }, key, signature, data);
  } catch {
    return false;
  }
}
