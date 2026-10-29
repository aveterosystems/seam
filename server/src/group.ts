// SPDX-License-Identifier: AGPL-3.0-only
// The relay's protocol logic for one pairing group, kept free of Cloudflare APIs so it runs in
// plain Node tests. The Durable Object (pair-group.ts) is a thin adapter over it.
//
// Privacy: nothing here logs. The relay stores member keys, queued sealed envelopes until they're
// acked or expire, opaque prekey bundles and one pairing slot; no history.

import { create, fromBinary, type MessageInitShape, toBinary } from "@bufbuild/protobuf";
import { type Envelope, EnvelopeSchema } from "./generated/seam/v1/envelope_pb.js";
import { type ClientFrame, ClientFrameSchema, type Login, ServerFrameSchema } from "./generated/seam/v1/relay_pb.js";

export interface Storage {
  get<T>(key: string): Promise<T | undefined>;
  put(key: string, value: unknown): Promise<void>;
  /** Writes every entry or none (Durable Object storage batches are atomic). */
  putAll(entries: Record<string, unknown>): Promise<void>;
  delete(key: string): Promise<boolean>;
  /** Entries whose key starts with prefix, in key order. */
  list<T>(prefix: string): Promise<Map<string, T>>;
  deleteAll(): Promise<void>;
}

/** What survives hibernation for each socket. */
export interface SocketState {
  group: string; // hex
  nonce: string; // hex
  connectedAt: number; // ms
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

interface Counter {
  count: number;
  bytes: number;
}

interface Pairing {
  hash: string; // hex SHA-256 of the token
  expires: number; // ms
  opener: string; // hex relay key of the member who opened it
}

interface Window {
  start: number; // ms
  count: number;
}

export const LOGIN_CONTEXT = new TextEncoder().encode("seam-relay-login-v1");
export const MAX_FRAME = 1_200_000;
export const MAX_ENVELOPE = 1_100_000;
export const MAX_QUEUE_COUNT = 1000;
export const MAX_QUEUE_BYTES = 10 * 1024 * 1024;
export const MAX_MEMBERS = 8;
export const MAX_PRELOGIN = 16;
export const LOGIN_DEADLINE_MS = 10_000;
export const MAX_BUNDLES = 100;
export const MAX_BUNDLE_BYTES = 4096;
export const BUNDLE_RATE = { limit: 20, windowMs: 10 * 60_000 };
export const INACTIVE_MS = 90 * 86_400_000;
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
    /** Asks for alarm() to run no later than `at` (ms). */
    private readonly scheduleAlarm: (at: number) => Promise<void> = async () => {},
  ) {}

  /** A new connection: refuse it if too many are waiting to log in, else send a fresh challenge. */
  async open(socket: Socket, group: Uint8Array): Promise<void> {
    if (this.sockets().filter((s) => !s.state()?.device).length > MAX_PRELOGIN) return socket.close(POLICY, "busy");
    const nonce = crypto.getRandomValues(new Uint8Array(32));
    const connectedAt = this.now();
    socket.setState({ group: hex(group), nonce: hex(nonce), connectedAt });
    this.send(socket, { case: "challenge", value: { nonce } });
    await this.scheduleAlarm(connectedAt + LOGIN_DEADLINE_MS);
  }

  async message(socket: Socket, data: ArrayBuffer | string): Promise<void> {
    const state = socket.state();
    if (!state) return socket.close(POLICY, "no state");
    const loggedIn = Boolean(state.device);
    const size = typeof data === "string" ? data.length : data.byteLength;
    if (size > MAX_FRAME) return this.fail(socket, "too_large", true); // before parsing anything
    if (!loggedIn && this.now() > state.connectedAt + LOGIN_DEADLINE_MS) return this.fail(socket, "bad_login", true);

    let frame: ClientFrame;
    try {
      if (typeof data === "string") throw new Error("text frame");
      frame = fromBinary(ClientFrameSchema, new Uint8Array(data));
    } catch {
      return this.fail(socket, "bad_frame", !loggedIn);
    }

    try {
      if (!loggedIn) {
        if (frame.frame.case !== "login") return this.fail(socket, "bad_login", true);
        return await this.login(socket, state, frame.frame.value);
      }
      return await this.handle(socket, state.device!, frame);
    } catch {
      // A storage failure (or anything unexpected) answers with an error instead of throwing
      return this.fail(socket, "storage");
    }
  }

  private async handle(socket: Socket, me: string, frame: ClientFrame): Promise<void> {
    switch (frame.frame.case) {
      case "send":
        return this.relay(socket, me, frame.frame.value);
      case "delivered":
        return this.delivered(me, frame.frame.value.msgId);
      case "putBundles":
        return this.putBundles(socket, me, frame.frame.value.bundles);
      case "getBundle":
        return this.getBundle(socket, me, frame.frame.value.deviceKey);
      case "openPairing":
        return this.openPairing(socket, me, frame.frame.value.tokenHash, frame.frame.value.ttlS);
      case "closePairing":
        return this.closePairing(me);
      case "removeMember":
        return this.removeMember(socket, me, frame.frame.value.deviceKey);
      default:
        return this.fail(socket, "bad_frame");
    }
  }

  private async login(socket: Socket, state: SocketState, login: Login): Promise<void> {
    if (hex(login.groupId) !== state.group || login.deviceKey.length !== 32) return this.fail(socket, "bad_login", true);
    const signed = concat(LOGIN_CONTEXT, unhex(state.nonce), login.groupId);
    if (!(await verifyEd25519(login.deviceKey, login.signature, signed))) return this.fail(socket, "bad_login", true);
    const hash = login.joinToken.length > 0 ? hex(new Uint8Array(await crypto.subtle.digest("SHA-256", login.joinToken))) : "";
    if (socket.state()?.device) return; // a second Login raced the first on this socket

    // From here to the put below only storage is awaited, so the Durable Object's input gate
    // keeps this read-modify-write atomic (one token, one join).
    const device = hex(login.deviceKey);
    const members = (await this.storage.get<string[]>("members")) ?? [];
    let joined = false;
    if (login.joinToken.length > 0) {
      const slot = await this.storage.get<Pairing>("pairing");
      if (!slot || slot.expires <= this.now() || slot.hash !== hash) return this.fail(socket, "bad_token", true);
      if (!members.includes(device)) {
        if (members.length >= MAX_MEMBERS) return this.fail(socket, "group_full", true);
        members.push(device);
        joined = true;
      }
      await this.storage.delete("pairing"); // single use
    } else if (members.length === 0 && login.createGroup) {
      members.push(device); // the first device creates the group, and only when it asks to
    } else if (!members.includes(device)) {
      return this.fail(socket, "bad_login", true);
    }
    await this.storage.put("members", members);
    await this.storage.put("lastActive", this.now());

    socket.setState({ ...state, device });
    this.send(socket, { case: "loginResult", value: { ok: true, members: members.map(unhex) } });
    if (joined) {
      for (const other of this.connected()) {
        if (other.state()?.device !== device) this.send(other, { case: "peerJoined", value: { deviceKey: login.deviceKey } });
      }
    }
    await this.flush(socket, device);
    await this.scheduleAlarm(this.now() + INACTIVE_MS);
  }

  /** Delivers everything queued for this device, oldest first, dropping what has expired. */
  private async flush(socket: Socket, device: string): Promise<void> {
    for (const q of (await this.purge(device)).values()) this.sendRaw(socket, deliverFrame(q.bytes));
  }

  /** Deletes a device's expired envelopes and rewrites its counter. Returns what's left. */
  private async purge(device: string): Promise<Map<string, Queued>> {
    const left = new Map<string, Queued>();
    const counter: Counter = { count: 0, bytes: 0 };
    for (const [key, q] of await this.storage.list<Queued>(`q:${device}:`)) {
      if (q.expires <= this.now()) {
        await this.storage.delete(key);
      } else {
        left.set(key, q);
        counter.count++;
        counter.bytes += q.bytes.length;
      }
    }
    await this.storage.put(`qc:${device}`, counter);
    return left;
  }

  private async relay(socket: Socket, me: string, env: Envelope): Promise<void> {
    if (env.msgId.length !== 16 || env.ciphertext.length === 0) return this.fail(socket, "bad_frame");
    const to = hex(env.to);
    const members = (await this.storage.get<string[]>("members")) ?? [];
    if (hex(env.from) !== me || to === me || !members.includes(to)) return this.fail(socket, "not_member");
    const bytes = toBinary(EnvelopeSchema, env);
    if (bytes.length > MAX_ENVELOPE) return this.fail(socket, "too_large");

    const full = (c: Counter) => c.count >= MAX_QUEUE_COUNT || c.bytes + bytes.length > MAX_QUEUE_BYTES;
    let counter = (await this.storage.get<Counter>(`qc:${to}`)) ?? { count: 0, bytes: 0 };
    if (full(counter)) {
      // Only list the queue when it looks full: expired entries mustn't count against the quota
      await this.purge(to);
      counter = (await this.storage.get<Counter>(`qc:${to}`))!;
      if (full(counter)) return this.fail(socket, "queue_full");
    }

    const seq = ((await this.storage.get<number>("seq")) ?? 0) + 1;
    const ttl = Math.min(env.ttlS || MAX_TTL_S, MAX_TTL_S);
    const expires = this.now() + ttl * 1000;
    // One atomic write, so the counter can never drift from the queue (SR-2-65).
    // Zero-padded so storage key order is arrival order.
    await this.storage.putAll({
      seq,
      [`q:${to}:${String(seq).padStart(16, "0")}`]: { msgId: hex(env.msgId), bytes, expires } satisfies Queued,
      [`qc:${to}`]: { count: counter.count + 1, bytes: counter.bytes + bytes.length } satisfies Counter,
    });
    await this.scheduleAlarm(expires);

    const frame = deliverFrame(bytes);
    for (const s of this.connected()) if (s.state()?.device === to) this.sendRaw(s, frame);
  }

  private async delivered(me: string, msgId: Uint8Array): Promise<void> {
    const id = hex(msgId);
    let removed = false;
    for (const [key, q] of await this.storage.list<Queued>(`q:${me}:`)) {
      if (q.msgId === id) removed = (await this.storage.delete(key)) || removed;
    }
    if (removed) await this.purge(me);
  }

  private async putBundles(socket: Socket, me: string, bundles: Uint8Array[]): Promise<void> {
    if (bundles.length > MAX_BUNDLES || bundles.some((b) => b.length > MAX_BUNDLE_BYTES)) return this.fail(socket, "too_large");
    await this.storage.put(`b:${me}`, bundles);
  }

  private async getBundle(socket: Socket, me: string, deviceKey: Uint8Array): Promise<void> {
    const device = hex(deviceKey);
    const members = (await this.storage.get<string[]>("members")) ?? [];
    if (!members.includes(device)) return this.fail(socket, "not_member");

    // A member draining another's one-time prekeys would push new sessions onto the last resort
    const now = this.now();
    let w = (await this.storage.get<Window>(`rl:${me}`)) ?? { start: now, count: 0 };
    if (now - w.start >= BUNDLE_RATE.windowMs) w = { start: now, count: 0 };
    if (w.count >= BUNDLE_RATE.limit) return this.fail(socket, "rate_limited");
    await this.storage.put(`rl:${me}`, { start: w.start, count: w.count + 1 } satisfies Window);

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

  private async openPairing(socket: Socket, me: string, tokenHash: Uint8Array, ttlS: number): Promise<void> {
    if (tokenHash.length !== 32) return this.fail(socket, "bad_frame");
    const slot = await this.storage.get<Pairing>("pairing");
    if (slot && slot.expires > this.now() && slot.opener !== me) return this.fail(socket, "slot_open");
    const ttl = Math.min(ttlS || MAX_PAIRING_TTL_S, MAX_PAIRING_TTL_S);
    const expires = this.now() + ttl * 1000;
    await this.storage.put("pairing", { hash: hex(tokenHash), expires, opener: me } satisfies Pairing);
    await this.scheduleAlarm(expires);
  }

  private async closePairing(me: string): Promise<void> {
    const slot = await this.storage.get<Pairing>("pairing");
    if (slot?.opener === me) await this.storage.delete("pairing");
  }

  /** Unpairing, from either side: the device leaves the group with its queue and bundles. */
  private async removeMember(socket: Socket, me: string, deviceKey: Uint8Array): Promise<void> {
    const target = hex(deviceKey);
    const members = (await this.storage.get<string[]>("members")) ?? [];
    if (!members.includes(target)) return this.fail(socket, "not_member");
    await this.storage.put("members", members.filter((m) => m !== target));
    for (const key of (await this.storage.list(`q:${target}:`)).keys()) await this.storage.delete(key);
    for (const key of [`qc:${target}`, `b:${target}`, `rl:${target}`]) await this.storage.delete(key);
    const slot = await this.storage.get<Pairing>("pairing");
    if (slot?.opener === target) await this.storage.delete("pairing");
    for (const s of this.connected()) if (s.state()?.device === target) s.close(1000, "removed");
    if (target === me) socket.close(1000, "removed");
  }

  /**
   * Housekeeping: closes sockets that never logged in, sweeps expired envelopes and an expired
   * pairing slot, deletes a group nobody has logged in to for 90 days, then sets the next alarm.
   */
  async alarm(): Promise<void> {
    const now = this.now();
    let next = Infinity;
    for (const s of this.sockets()) {
      const st = s.state();
      if (!st || st.device) continue;
      const deadline = st.connectedAt + LOGIN_DEADLINE_MS;
      if (now >= deadline) s.close(POLICY, "login timeout");
      else next = Math.min(next, deadline);
    }

    let lastActive = await this.storage.get<number>("lastActive");
    // A device that stays connected for months is active, even without logging in again (SR-2-66)
    if (lastActive !== undefined && this.connected().length > 0) {
      lastActive = now;
      await this.storage.put("lastActive", now);
    }
    if (lastActive !== undefined && now >= lastActive + INACTIVE_MS) {
      for (const s of this.sockets()) s.close(1000, "group expired");
      return this.storage.deleteAll();
    }
    if (lastActive !== undefined) next = Math.min(next, lastActive + INACTIVE_MS);

    const devices = new Set<string>();
    for (const [key, q] of await this.storage.list<Queued>("q:")) {
      if (q.expires <= now) devices.add(key.split(":")[1]!);
      else next = Math.min(next, q.expires);
    }
    for (const d of devices) await this.purge(d);

    const slot = await this.storage.get<Pairing>("pairing");
    if (slot && slot.expires <= now) await this.storage.delete("pairing");
    else if (slot) next = Math.min(next, slot.expires);

    if (next !== Infinity) await this.scheduleAlarm(next);
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
