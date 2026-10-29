// SPDX-License-Identifier: AGPL-3.0-only
import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { beforeEach, describe, expect, it } from "vitest";
import { EnvelopeSchema } from "../src/generated/seam/v1/envelope_pb.js";
import { ClientFrameSchema, type ServerFrame, ServerFrameSchema } from "../src/generated/seam/v1/relay_pb.js";
import { Group, LOGIN_CONTEXT, MAX_QUEUE_COUNT, type Socket, type SocketState, type Storage } from "../src/group.js";
import worker, { decodeGroup } from "../src/index.js";

class MemStorage implements Storage {
  readonly data = new Map<string, unknown>();
  async get<T>(k: string) { return structuredClone(this.data.get(k)) as T | undefined; }
  async put(k: string, v: unknown) { this.data.set(k, structuredClone(v)); }
  async delete(k: string) { return this.data.delete(k); }
  async list<T>(prefix: string) {
    const keys = [...this.data.keys()].filter((k) => k.startsWith(prefix)).sort();
    return new Map(keys.map((k) => [k, structuredClone(this.data.get(k)) as T]));
  }
}

class FakeSocket implements Socket {
  frames: ServerFrame["frame"][] = [];
  closed: string | null = null;
  private s: SocketState | null = null;
  send(data: Uint8Array) { this.frames.push(fromBinary(ServerFrameSchema, data).frame); }
  close(_code: number, reason: string) { this.closed = reason; }
  state() { return this.s; }
  setState(s: SocketState) { this.s = s; }
  last() { return this.frames.at(-1)!; }
  of<C extends NonNullable<ServerFrame["frame"]["case"]>>(c: C) {
    return this.frames.filter((f) => f.case === c).map((f) => f.value) as Extract<ServerFrame["frame"], { case: C }>["value"][];
  }
}

interface Device { pub: Uint8Array; priv: CryptoKey; }
async function device(): Promise<Device> {
  const k = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"])) as CryptoKeyPair;
  return { pub: new Uint8Array((await crypto.subtle.exportKey("raw", k.publicKey)) as ArrayBuffer), priv: k.privateKey };
}

const frame = (f: object) => toBinary(ClientFrameSchema, create(ClientFrameSchema, { frame: f } as never)).buffer as ArrayBuffer;
const concat = (...p: Uint8Array[]) => { const o = new Uint8Array(p.reduce((n, x) => n + x.length, 0)); let i = 0; for (const x of p) { o.set(x, i); i += x.length; } return o; };

let storage: MemStorage;
let sockets: FakeSocket[];
let now: number;
let group: Group;
const G = new Uint8Array(16).fill(7);

beforeEach(() => {
  storage = new MemStorage();
  sockets = [];
  now = 1_000_000;
  group = new Group(storage, () => sockets, () => now);
});

function connect(): FakeSocket {
  const s = new FakeSocket();
  sockets.push(s);
  group.open(s, G);
  return s;
}

async function signLogin(s: FakeSocket, d: Device, groupId = G) {
  const challenge = s.of("challenge")[0]!;
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, d.priv, concat(LOGIN_CONTEXT, challenge.nonce, groupId)));
  return { groupId, deviceKey: d.pub, signature: sig, joinToken: new Uint8Array() as Uint8Array };
}

async function login(d: Device, joinToken?: Uint8Array): Promise<FakeSocket> {
  const s = connect();
  const l = await signLogin(s, d);
  if (joinToken) l.joinToken = joinToken;
  await group.message(s, frame({ case: "login", value: l }));
  return s;
}

async function openPairing(mac: FakeSocket, token: Uint8Array, ttlS = 300) {
  const tokenHash = new Uint8Array(await crypto.subtle.digest("SHA-256", token));
  await group.message(mac, frame({ case: "openPairing", value: { tokenHash, ttlS } }));
}

/** A Mac that created the group and a phone that joined by pairing. */
async function paired() {
  const [mac, phone] = [await device(), await device()];
  const macSock = await login(mac);
  const token = crypto.getRandomValues(new Uint8Array(32));
  await openPairing(macSock, token);
  const phoneSock = await login(phone, token);
  return { mac, phone, macSock, phoneSock };
}

function envelope(from: Uint8Array, to: Uint8Array, id: number, extra: object = {}) {
  return create(EnvelopeSchema, { version: 1, msgId: new Uint8Array(16).fill(id), from, to, ciphertext: new Uint8Array([1, 2, 3]), ...extra });
}
const send = (s: FakeSocket, env: ReturnType<typeof envelope>) => group.message(s, frame({ case: "send", value: env }));
const ids = (s: FakeSocket) => s.of("deliver").map((e) => e.msgId[0]);

describe("login", () => {
  it("challenges every new connection with a fresh nonce", () => {
    const [a, b] = [connect(), connect()];
    expect(a.of("challenge")[0]!.nonce).toHaveLength(32);
    expect(a.of("challenge")[0]!.nonce).not.toEqual(b.of("challenge")[0]!.nonce);
  });

  it("the first device creates the group", async () => {
    const mac = await device();
    const s = await login(mac);
    expect(s.of("loginResult")[0]).toMatchObject({ ok: true, members: [mac.pub] });
    expect(s.closed).toBeNull();
  });

  it("refuses a bad signature, a wrong group and a key of the wrong size", async () => {
    const mac = await device();
    const bad = connect();
    const l = await signLogin(bad, mac);
    l.signature[0]! ^= 1;
    await group.message(bad, frame({ case: "login", value: l }));
    expect(bad.of("error")[0]!.code).toBe("bad_login");
    expect(bad.closed).toBe("bad_login");

    const wrong = connect();
    await group.message(wrong, frame({ case: "login", value: await signLogin(wrong, mac, new Uint8Array(16).fill(9)) }));
    expect(wrong.closed).toBe("bad_login");

    const short = connect();
    await group.message(short, frame({ case: "login", value: { ...(await signLogin(short, mac)), deviceKey: new Uint8Array(31) } }));
    expect(short.closed).toBe("bad_login");
    expect(storage.data.has("members")).toBe(false);
  });

  it("refuses a login replayed from another connection's challenge", async () => {
    const mac = await device();
    const first = connect();
    const l = await signLogin(first, mac);
    const second = connect();
    await group.message(second, frame({ case: "login", value: l }));
    expect(second.closed).toBe("bad_login");
  });

  it("refuses a stranger once the group exists", async () => {
    await login(await device());
    const s = await login(await device());
    expect(s.closed).toBe("bad_login");
  });

  it("accepts nothing but Login before login, and refuses text frames", async () => {
    const mac = await device();
    const s = connect();
    await group.message(s, frame({ case: "send", value: envelope(mac.pub, mac.pub, 1) }));
    expect(s.closed).toBe("bad_login");
    const t = connect();
    await group.message(t, "hello");
    expect(t.of("error")[0]!.code).toBe("bad_frame");
  });
});

describe("pairing", () => {
  it("joins with a valid token, tells the other members, and reports every member", async () => {
    const { mac, phone, macSock, phoneSock } = await paired();
    expect(phoneSock.of("loginResult")[0]!.members).toEqual([mac.pub, phone.pub]);
    expect(macSock.of("peerJoined")[0]!.deviceKey).toEqual(phone.pub);
    expect(phoneSock.of("peerJoined")).toHaveLength(0);
  });

  it("refuses a wrong, expired or reused token", async () => {
    const macSock = await login(await device());
    const token = crypto.getRandomValues(new Uint8Array(32));
    await openPairing(macSock, token, 60);

    expect((await login(await device(), new Uint8Array(32))).closed).toBe("bad_token");

    now += 61_000;
    expect((await login(await device(), token)).closed).toBe("bad_token");

    await openPairing(macSock, token);
    expect((await login(await device(), token)).closed).toBeNull();
    expect((await login(await device(), token)).closed).toBe("bad_token"); // single use
  });

  it("caps the pairing slot at 5 minutes", async () => {
    const macSock = await login(await device());
    const token = crypto.getRandomValues(new Uint8Array(32));
    await openPairing(macSock, token, 3600);
    now += 301_000;
    expect((await login(await device(), token)).closed).toBe("bad_token");
  });
});

describe("relaying", () => {
  it("delivers straight away to a connected recipient", async () => {
    const { mac, phone, macSock, phoneSock } = await paired();
    await send(macSock, envelope(mac.pub, phone.pub, 1));
    expect(ids(phoneSock)).toEqual([1]);
    expect(macSock.of("error")).toHaveLength(0);
  });

  it("queues for an offline device and delivers in order on its next login", async () => {
    const { mac, phone, macSock } = await paired();
    sockets = sockets.filter((s) => s === macSock); // phone disconnects
    await send(macSock, envelope(mac.pub, phone.pub, 1));
    await send(macSock, envelope(mac.pub, phone.pub, 2));
    const back = await login(phone);
    expect(ids(back)).toEqual([1, 2]);
  });

  it("deletes only on Delivered, and redelivers the rest after a reconnect", async () => {
    const { mac, phone, macSock, phoneSock } = await paired();
    await send(macSock, envelope(mac.pub, phone.pub, 1));
    await send(macSock, envelope(mac.pub, phone.pub, 2));
    await group.message(phoneSock, frame({ case: "delivered", value: { msgId: new Uint8Array(16).fill(1) } }));
    sockets = sockets.filter((s) => s !== phoneSock);
    expect(ids(await login(phone))).toEqual([2]);
  });

  it("refuses a spoofed sender and a recipient outside the group", async () => {
    const { mac, phone, macSock } = await paired();
    await send(macSock, envelope(phone.pub, phone.pub, 1));
    await send(macSock, envelope(mac.pub, (await device()).pub, 2));
    await send(macSock, envelope(mac.pub, mac.pub, 3));
    expect(macSock.of("error").map((e) => e.code)).toEqual(["not_member", "not_member", "not_member"]);
    expect([...storage.data.keys()].filter((k) => k.startsWith("q:"))).toHaveLength(0);
  });

  it("refuses envelopes over the size limit", async () => {
    const { mac, phone, macSock } = await paired();
    await send(macSock, envelope(mac.pub, phone.pub, 1, { ciphertext: new Uint8Array(1_100_001) }));
    expect(macSock.of("error")[0]!.code).toBe("too_large");
  });

  it("refuses more than the queue limit", async () => {
    const { mac, phone, macSock } = await paired();
    sockets = sockets.filter((s) => s === macSock);
    for (let i = 0; i < MAX_QUEUE_COUNT; i++) await send(macSock, envelope(mac.pub, phone.pub, i % 250));
    expect(macSock.of("error")).toHaveLength(0);
    await send(macSock, envelope(mac.pub, phone.pub, 1));
    expect(macSock.of("error")[0]!.code).toBe("queue_full");
  });

  it("refuses more than 10 MB queued", async () => {
    const { mac, phone, macSock } = await paired();
    sockets = sockets.filter((s) => s === macSock);
    for (let i = 0; i < 10; i++) await send(macSock, envelope(mac.pub, phone.pub, i, { ciphertext: new Uint8Array(1_000_000) }));
    expect(macSock.of("error")).toHaveLength(0);
    await send(macSock, envelope(mac.pub, phone.pub, 11, { ciphertext: new Uint8Array(500_000) }));
    expect(macSock.of("error")[0]!.code).toBe("queue_full");
  });

  it("drops expired envelopes, with the TTL capped at 24 hours", async () => {
    const { mac, phone, macSock } = await paired();
    sockets = sockets.filter((s) => s === macSock);
    await send(macSock, envelope(mac.pub, phone.pub, 1, { ttlS: 60 }));
    await send(macSock, envelope(mac.pub, phone.pub, 2, { ttlS: 999_999 }));
    await send(macSock, envelope(mac.pub, phone.pub, 3));
    now += 61_000;
    let back = await login(phone);
    expect(ids(back)).toEqual([2, 3]);
    sockets = sockets.filter((s) => s !== back);
    now += 86_400_000;
    back = await login(phone);
    expect(ids(back)).toEqual([]);
    expect([...storage.data.keys()].filter((k) => k.startsWith("q:"))).toHaveLength(0);
  });
});

describe("prekey bundles", () => {
  it("hands out one per request and keeps the last as a last resort", async () => {
    const { mac, macSock, phoneSock } = await paired();
    const bundles = [new Uint8Array([1]), new Uint8Array([2])];
    await group.message(macSock, frame({ case: "putBundles", value: { bundles } }));
    const get = () => group.message(phoneSock, frame({ case: "getBundle", value: { deviceKey: mac.pub } }));
    await get();
    await get();
    await get();
    expect(phoneSock.of("bundle").map((b) => [...b.bundle])).toEqual([[1], [2], [2]]);
  });

  it("returns an empty bundle when none were uploaded, and refuses non-members", async () => {
    const { mac, phoneSock } = await paired();
    await group.message(phoneSock, frame({ case: "getBundle", value: { deviceKey: mac.pub } }));
    expect(phoneSock.of("bundle")[0]!.bundle).toHaveLength(0);
    await group.message(phoneSock, frame({ case: "getBundle", value: { deviceKey: (await device()).pub } }));
    expect(phoneSock.of("error")[0]!.code).toBe("not_member");
  });
});

describe("worker routes", () => {
  const env = { PAIR_GROUP: { idFromName: () => { throw new Error("should not reach the DO"); } } } as never;

  it("answers the health check and 404s everything else", async () => {
    const health = await worker.fetch(new Request("https://relay.test/health"), env);
    expect(await health.text()).toBe("ok");
    expect((await worker.fetch(new Request("https://relay.test/anything"), env)).status).toBe(404);
  });

  it("refuses a missing or malformed group with 400", async () => {
    for (const q of ["", "?group=", "?group=short", `?group=${"A".repeat(23)}`, "?group=!!!!!!!!!!!!!!!!!!!!!!"]) {
      const r = await worker.fetch(new Request(`https://relay.test/v1/connect${q}`, { headers: { Upgrade: "websocket" } }), env);
      expect(r.status).toBe(400);
    }
  });

  it("decodes a base64url group id of exactly 16 bytes", () => {
    expect(decodeGroup("BwcHBwcHBwcHBwcHBwcHBw")).toEqual(G);
  });
});
