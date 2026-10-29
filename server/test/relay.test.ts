// SPDX-License-Identifier: AGPL-3.0-only
import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { beforeEach, describe, expect, it } from "vitest";
import { EnvelopeSchema } from "../src/generated/seam/v1/envelope_pb.js";
import { ClientFrameSchema, type ServerFrame, ServerFrameSchema } from "../src/generated/seam/v1/relay_pb.js";
import {
  BUNDLE_RATE, Group, INACTIVE_MS, LOGIN_CONTEXT, LOGIN_DEADLINE_MS, MAX_BUNDLE_BYTES, MAX_BUNDLES, MAX_FRAME, MAX_MEMBERS,
  MAX_PRELOGIN, MAX_QUEUE_COUNT, type Socket, type SocketState, type Storage,
} from "../src/group.js";
import worker, { decodeGroup } from "../src/index.js";

class MemStorage implements Storage {
  readonly data = new Map<string, unknown>();
  lists = 0;
  failPuts = false;
  /** Writes touching keys with this prefix fail. */
  failPrefix: string | null = null;
  private write(...keys: string[]) {
    if (this.failPuts || keys.some((k) => this.failPrefix !== null && k.startsWith(this.failPrefix))) throw new Error("storage down");
  }
  async get<T>(k: string) { return structuredClone(this.data.get(k)) as T | undefined; }
  async put(k: string, v: unknown) { this.write(k); this.data.set(k, structuredClone(v)); }
  async putAll(entries: Record<string, unknown>) {
    this.write(...Object.keys(entries));
    for (const [k, v] of Object.entries(entries)) this.data.set(k, structuredClone(v));
  }
  async delete(k: string) { return this.data.delete(k); }
  async list<T>(prefix: string) {
    this.lists++;
    const keys = [...this.data.keys()].filter((k) => k.startsWith(prefix)).sort();
    return new Map(keys.map((k) => [k, structuredClone(this.data.get(k)) as T]));
  }
  async deleteAll() { this.data.clear(); }
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

const toHex = (b: Uint8Array) => [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
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
let alarms: number[];
const G = new Uint8Array(16).fill(7);

beforeEach(() => {
  storage = new MemStorage();
  sockets = [];
  now = 1_000_000;
  alarms = [];
  group = new Group(storage, () => sockets, () => now, async (at) => { alarms.push(at); });
});

async function connect(): Promise<FakeSocket> {
  const s = new FakeSocket();
  sockets.push(s);
  await group.open(s, G);
  return s;
}

async function signLogin(s: FakeSocket, d: Device, groupId = G) {
  const challenge = s.of("challenge")[0]!;
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, d.priv, concat(LOGIN_CONTEXT, challenge.nonce, groupId)));
  return { groupId, deviceKey: d.pub, signature: sig, joinToken: new Uint8Array() as Uint8Array, createGroup: false };
}

async function login(d: Device, joinToken?: Uint8Array, createGroup = false): Promise<FakeSocket> {
  const s = await connect();
  const l = await signLogin(s, d);
  if (joinToken) l.joinToken = joinToken;
  l.createGroup = createGroup;
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
  const macSock = await login(mac, undefined, true);
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
  it("challenges every new connection with a fresh nonce", async () => {
    const [a, b] = [await connect(), await connect()];
    expect(a.of("challenge")[0]!.nonce).toHaveLength(32);
    expect(a.of("challenge")[0]!.nonce).not.toEqual(b.of("challenge")[0]!.nonce);
  });

  it("the first device creates the group when it asks to", async () => {
    const mac = await device();
    const s = await login(mac, undefined, true);
    expect(s.of("loginResult")[0]).toMatchObject({ ok: true, members: [mac.pub] });
    expect(s.closed).toBeNull();
  });

  it("refuses a bad signature, a wrong group and a key of the wrong size", async () => {
    const mac = await device();
    const bad = await connect();
    const l = await signLogin(bad, mac);
    l.signature[0]! ^= 1;
    await group.message(bad, frame({ case: "login", value: l }));
    expect(bad.of("error")[0]!.code).toBe("bad_login");
    expect(bad.closed).toBe("bad_login");

    const wrong = await connect();
    await group.message(wrong, frame({ case: "login", value: await signLogin(wrong, mac, new Uint8Array(16).fill(9)) }));
    expect(wrong.closed).toBe("bad_login");

    const short = await connect();
    await group.message(short, frame({ case: "login", value: { ...(await signLogin(short, mac)), deviceKey: new Uint8Array(31) } }));
    expect(short.closed).toBe("bad_login");
    expect(storage.data.has("members")).toBe(false);
  });

  it("refuses a login replayed from another connection's challenge", async () => {
    const mac = await device();
    const first = await connect();
    const l = await signLogin(first, mac);
    const second = await connect();
    await group.message(second, frame({ case: "login", value: l }));
    expect(second.closed).toBe("bad_login");
  });

  it("refuses a stranger once the group exists, even one asking to create it", async () => {
    await login(await device(), undefined, true);
    expect((await login(await device())).closed).toBe("bad_login");
    expect((await login(await device(), undefined, true)).closed).toBe("bad_login");
  });

  it("accepts nothing but Login before login, and closes on a text or malformed frame", async () => {
    const mac = await device();
    const s = await connect();
    await group.message(s, frame({ case: "send", value: envelope(mac.pub, mac.pub, 1) }));
    expect(s.closed).toBe("bad_login");
    const t = await connect();
    await group.message(t, "hello");
    expect(t.of("error")[0]!.code).toBe("bad_frame");
    expect(t.closed).toBe("bad_frame");
    const u = await connect();
    await group.message(u, new Uint8Array([0xff, 0xff, 0xff]).buffer);
    expect(u.closed).toBe("bad_frame");
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
    const macSock = await login(await device(), undefined, true);
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
    const macSock = await login(await device(), undefined, true);
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


describe("SR-2-13 · frame size, login deadline, pre-login cap", () => {
  it("rejects an oversized frame before parsing it", async () => {
    const s = await connect();
    await group.message(s, new ArrayBuffer(MAX_FRAME + 1));
    expect(s.of("error")[0]!.code).toBe("too_large");
    expect(s.closed).toBe("too_large");
  });

  it("closes a socket that hasn't logged in within 10 seconds, on its next frame and by alarm", async () => {
    const mac = await device();
    const late = await connect();
    const l = await signLogin(late, mac);
    expect(alarms).toContain(now + LOGIN_DEADLINE_MS);
    now += LOGIN_DEADLINE_MS + 1;
    await group.message(late, frame({ case: "login", value: { ...l, createGroup: true } }));
    expect(late.closed).toBe("bad_login");

    now = 1_000_000;
    const idle = await connect();
    now += LOGIN_DEADLINE_MS;
    await group.alarm();
    expect(idle.closed).toBe("login timeout");
  });

  it("refuses more than 16 sockets waiting to log in", async () => {
    for (let i = 0; i < MAX_PRELOGIN; i++) expect((await connect()).closed).toBeNull();
    const extra = await connect();
    expect(extra.closed).toBe("busy");
    expect(extra.of("challenge")).toHaveLength(0);
  });
});

describe("SR-2-14 · group creation", () => {
  it("a plain login never creates a group", async () => {
    const s = await login(await device());
    expect(s.closed).toBe("bad_login");
    expect(storage.data.has("members")).toBe(false);
  });
});

describe("SR-2-15 · members, pairing slot, unpairing", () => {
  it("caps a group at 8 members", async () => {
    const macSock = await login(await device(), undefined, true);
    for (let i = 1; i < MAX_MEMBERS; i++) {
      const t = crypto.getRandomValues(new Uint8Array(32));
      await openPairing(macSock, t);
      expect((await login(await device(), t)).closed).toBeNull();
    }
    const t = crypto.getRandomValues(new Uint8Array(32));
    await openPairing(macSock, t);
    const ninth = await login(await device(), t);
    expect(ninth.of("error")[0]!.code).toBe("group_full");
    expect(ninth.closed).toBe("group_full");
  });

  it("refuses another member's slot while one is open; the opener can replace or close its own", async () => {
    const { macSock, phoneSock } = await paired();
    const macToken = crypto.getRandomValues(new Uint8Array(32));
    await openPairing(macSock, macToken);
    await openPairing(phoneSock, crypto.getRandomValues(new Uint8Array(32)));
    expect(phoneSock.of("error")[0]!.code).toBe("slot_open");

    const replaced = crypto.getRandomValues(new Uint8Array(32));
    await openPairing(macSock, replaced);
    expect(macSock.of("error")).toHaveLength(0);
    expect((await login(await device(), macToken)).closed).toBe("bad_token");

    await group.message(phoneSock, frame({ case: "closePairing", value: {} }));
    expect(storage.data.has("pairing")).toBe(true); // not the opener
    await group.message(macSock, frame({ case: "closePairing", value: {} }));
    expect((await login(await device(), replaced)).closed).toBe("bad_token");

    now += 301_000; // once the slot expires, anyone may open one
    await openPairing(phoneSock, crypto.getRandomValues(new Uint8Array(32)));
    expect(phoneSock.of("error")).toHaveLength(1);
  });

  it("removes a member: its queue and bundles go, its sockets close, and it can't log in again", async () => {
    const { mac, phone, macSock, phoneSock } = await paired();
    await group.message(phoneSock, frame({ case: "putBundles", value: { bundles: [new Uint8Array([1])] } }));
    sockets = sockets.filter((s) => s !== phoneSock);
    await send(macSock, envelope(mac.pub, phone.pub, 1));
    const back = await login(phone);
    await group.message(macSock, frame({ case: "removeMember", value: { deviceKey: phone.pub } }));
    expect(back.closed).toBe("removed");
    expect([...storage.data.keys()].some((k) => k.includes(Buffer.from(phone.pub).toString("hex")))).toBe(false);
    expect((await login(phone)).closed).toBe("bad_login");
    await send(macSock, envelope(mac.pub, phone.pub, 2));
    expect(macSock.of("error").at(-1)!.code).toBe("not_member");
  });
});

describe("SR-2-16 · expiry and housekeeping", () => {
  it("purges expired envelopes before the quota check", async () => {
    const { mac, phone, macSock } = await paired();
    sockets = sockets.filter((s) => s === macSock);
    for (let i = 0; i < MAX_QUEUE_COUNT; i++) await send(macSock, envelope(mac.pub, phone.pub, i % 250, { ttlS: 60 }));
    now += 61_000;
    await send(macSock, envelope(mac.pub, phone.pub, 1));
    expect(macSock.of("error")).toHaveLength(0);
  });

  it("the alarm sweeps expired envelopes and an expired pairing slot", async () => {
    const { mac, phone, macSock } = await paired();
    sockets = sockets.filter((s) => s === macSock);
    await send(macSock, envelope(mac.pub, phone.pub, 1, { ttlS: 60 }));
    await send(macSock, envelope(mac.pub, phone.pub, 2));
    await openPairing(macSock, crypto.getRandomValues(new Uint8Array(32)), 30);
    now += 61_000;
    await group.alarm();
    expect([...storage.data.keys()].filter((k) => k.startsWith("q:"))).toHaveLength(1);
    expect(storage.data.has("pairing")).toBe(false);
    expect(alarms.at(-1)).toBeGreaterThan(now); // the remaining envelope's expiry
  });

  it("the alarm keeps a group whose device has stayed connected for 90 days (SR-2-66)", async () => {
    const { macSock } = await paired();
    now += INACTIVE_MS;
    await group.alarm();
    expect(macSock.closed).toBeNull();
    expect(storage.data.get("lastActive")).toBe(now);
  });

  it("the alarm deletes a group after 90 days with no login and nobody connected", async () => {
    await paired();
    sockets = []; // both devices disconnect
    now += INACTIVE_MS;
    await group.alarm();
    expect(storage.data.size).toBe(0);
  });
});

describe("SR-2-17 · quotas and failure handling", () => {
  it("caps bundle uploads at 100 bundles of 4 KB", async () => {
    const { macSock } = await paired();
    await group.message(macSock, frame({ case: "putBundles", value: { bundles: Array.from({ length: MAX_BUNDLES + 1 }, () => new Uint8Array(1)) } }));
    await group.message(macSock, frame({ case: "putBundles", value: { bundles: [new Uint8Array(MAX_BUNDLE_BYTES + 1)] } }));
    expect(macSock.of("error").map((e) => e.code)).toEqual(["too_large", "too_large"]);
    expect([...storage.data.keys()].some((k) => k.startsWith("b:"))).toBe(false);
  });

  it("rate-limits bundle requests per member", async () => {
    const { mac, macSock, phoneSock } = await paired();
    await group.message(macSock, frame({ case: "putBundles", value: { bundles: [new Uint8Array([1])] } }));
    for (let i = 0; i < BUNDLE_RATE.limit; i++) await group.message(phoneSock, frame({ case: "getBundle", value: { deviceKey: mac.pub } }));
    expect(phoneSock.of("error")).toHaveLength(0);
    await group.message(phoneSock, frame({ case: "getBundle", value: { deviceKey: mac.pub } }));
    expect(phoneSock.of("error")[0]!.code).toBe("rate_limited");
    now += BUNDLE_RATE.windowMs;
    await group.message(phoneSock, frame({ case: "getBundle", value: { deviceKey: mac.pub } }));
    expect(phoneSock.of("error")).toHaveLength(1);
  });

  it("keeps queue counters instead of listing the queue on every send", async () => {
    const { mac, phone, macSock } = await paired();
    const before = storage.lists;
    for (let i = 0; i < 5; i++) await send(macSock, envelope(mac.pub, phone.pub, i));
    expect(storage.lists).toBe(before);
  });

  it("a storage error becomes a 'storage' error frame, not a throw", async () => {
    const { mac, phone, macSock } = await paired();
    storage.failPuts = true;
    await expect(send(macSock, envelope(mac.pub, phone.pub, 1))).resolves.toBeUndefined();
    expect(macSock.of("error")[0]!.code).toBe("storage");
    expect(macSock.closed).toBeNull();
  });

  it("a failed write never leaves an envelope its queue counter doesn't count (SR-2-65)", async () => {
    const { mac, phone, macSock } = await paired();
    await send(macSock, envelope(mac.pub, phone.pub, 1));
    storage.failPrefix = "qc:"; // the counter write fails
    await send(macSock, envelope(mac.pub, phone.pub, 2));
    expect(macSock.of("error").at(-1)!.code).toBe("storage");
    const queued = [...storage.data.keys()].filter((k) => k.startsWith(`q:${toHex(phone.pub)}:`)).length;
    expect(queued).toBe(1);
    expect((storage.data.get(`qc:${toHex(phone.pub)}`) as { count: number }).count).toBe(queued);
  });

  it("refuses envelopes with a bad msg_id or no ciphertext", async () => {
    const { mac, phone, macSock } = await paired();
    await send(macSock, envelope(mac.pub, phone.pub, 1, { msgId: new Uint8Array(4) }));
    await send(macSock, envelope(mac.pub, phone.pub, 2, { ciphertext: new Uint8Array() }));
    expect(macSock.of("error").map((e) => e.code)).toEqual(["bad_frame", "bad_frame"]);
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

  const G64 = "BwcHBwcHBwcHBwcHBwcHBw";
  function stubDO() {
    const seen: Request[] = [];
    const ns = { idFromName: (n: string) => n, get: () => ({ fetch: async (r: Request) => { seen.push(r); return new Response("to the DO"); } }) };
    return { seen, ns };
  }

  it("accepts a case-insensitive Upgrade header and forwards no client headers", async () => {
    const { seen, ns } = stubDO();
    const r = await worker.fetch(new Request(`https://relay.test/v1/connect?group=${G64}`, {
      headers: { Upgrade: "WebSocket", "cf-connecting-ip": "203.0.113.9", Cookie: "x=1" },
    }), { PAIR_GROUP: ns } as never);
    expect(await r.text()).toBe("to the DO");
    expect([...seen[0]!.headers.keys()]).toEqual(["upgrade"]);
    expect(new URL(seen[0]!.url).search).toBe("?group_hex=07070707070707070707070707070707");
  });

  it("rate-limits connects with 429 when the limiter refuses, before reaching the DO", async () => {
    const keys: string[] = [];
    const CONNECT_LIMITER = { limit: async ({ key }: { key: string }) => { keys.push(key); return { success: false }; } };
    const r = await worker.fetch(new Request(`https://relay.test/v1/connect?group=${G64}`, {
      headers: { Upgrade: "websocket", "cf-connecting-ip": "203.0.113.9" },
    }), { ...(env as object), CONNECT_LIMITER } as never);
    expect(r.status).toBe(429);
    expect(keys).toEqual(["203.0.113.9"]);
  });

  it("decodes a base64url group id of exactly 16 bytes", () => {
    expect(decodeGroup("BwcHBwcHBwcHBwcHBwcHBw")).toEqual(G);
  });
});
