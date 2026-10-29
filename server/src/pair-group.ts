// SPDX-License-Identifier: AGPL-3.0-only
// One Durable Object per pairing group. A thin adapter: Cloudflare's hibernating WebSockets and
// storage on one side, the testable protocol logic in group.ts on the other.

import { Group, type Socket, type SocketState, type Storage, unhex } from "./group.js";

// Close codes that only describe a closure and can't be sent in a close frame
const RESERVED_CLOSE = new Set([1005, 1006, 1015]);

export class PairGroup {
  private readonly group: Group;

  constructor(private readonly ctx: DurableObjectState) {
    const storage: Storage = {
      get: (key) => ctx.storage.get(key),
      put: (key, value) => ctx.storage.put(key, value),
      putAll: (entries) => ctx.storage.put(entries),
      delete: (key) => ctx.storage.delete(key),
      list: (prefix) => ctx.storage.list({ prefix }),
      deleteAll: () => ctx.storage.deleteAll(),
    };
    const scheduleAlarm = async (at: number) => {
      const current = await ctx.storage.getAlarm();
      if (current === null || current > at) await ctx.storage.setAlarm(at);
    };
    this.group = new Group(storage, () => ctx.getWebSockets().map(adapt), Date.now, scheduleAlarm);
  }

  /** Called by the Worker with an already validated ?group= (hex). */
  async fetch(request: Request): Promise<Response> {
    const group = new URL(request.url).searchParams.get("group_hex");
    if (!group || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response("bad request", { status: 400 });
    const { 0: client, 1: server } = new WebSocketPair();
    this.ctx.acceptWebSocket(server);
    await this.group.open(adapt(server), unhex(group));
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: ArrayBuffer | string): Promise<void> {
    await this.group.message(adapt(ws), message);
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    closeQuietly(ws, RESERVED_CLOSE.has(code) ? 1000 : code);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    closeQuietly(ws, 1011);
  }

  async alarm(): Promise<void> {
    await this.group.alarm();
  }
}

function closeQuietly(ws: WebSocket, code: number, reason = "closed"): void {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed
  }
}

function adapt(ws: WebSocket): Socket {
  return {
    send: (data) => ws.send(data),
    close: (code, reason) => closeQuietly(ws, code, reason),
    state: () => ws.deserializeAttachment() as SocketState | null,
    setState: (s) => ws.serializeAttachment(s),
  };
}

