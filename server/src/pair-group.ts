// SPDX-License-Identifier: AGPL-3.0-only
// One Durable Object per pairing group. A thin adapter: Cloudflare's hibernating WebSockets and
// storage on one side, the testable protocol logic in group.ts on the other.

import { Group, type Socket, type SocketState, type Storage, unhex } from "./group.js";

export class PairGroup {
  private readonly group: Group;

  constructor(private readonly ctx: DurableObjectState) {
    const storage: Storage = {
      get: (key) => ctx.storage.get(key),
      put: (key, value) => ctx.storage.put(key, value),
      delete: (key) => ctx.storage.delete(key),
      list: (prefix) => ctx.storage.list({ prefix }),
    };
    this.group = new Group(storage, () => ctx.getWebSockets().map(adapt));
  }

  /** Called by the Worker with an already validated ?group= (hex). */
  async fetch(request: Request): Promise<Response> {
    const group = new URL(request.url).searchParams.get("group_hex");
    if (!group || request.headers.get("Upgrade") !== "websocket") return new Response("bad request", { status: 400 });
    const { 0: client, 1: server } = new WebSocketPair();
    this.ctx.acceptWebSocket(server);
    this.group.open(adapt(server), unhex(group));
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: ArrayBuffer | string): Promise<void> {
    await this.group.message(adapt(ws), message);
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    ws.close(code === 1005 ? 1000 : code, "closed");
  }
}

function adapt(ws: WebSocket): Socket {
  return {
    send: (data) => ws.send(data),
    close: (code, reason) => ws.close(code, reason),
    state: () => ws.deserializeAttachment() as SocketState | null,
    setState: (s) => ws.serializeAttachment(s),
  };
}
