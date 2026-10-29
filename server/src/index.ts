// SPDX-License-Identifier: AGPL-3.0-only
// Seam relay entry point. Routes /v1/connect to the pairing group's Durable Object; it must never
// log request IPs or message contents (docs/security/threat-model.md).

import { hex } from "./group.js";

export { PairGroup } from "./pair-group.js";

export interface Env {
  PAIR_GROUP: DurableObjectNamespace;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return new Response("ok", { headers: { "content-type": "text/plain" } });
    }
    if (request.method === "GET" && url.pathname === "/v1/connect") {
      const group = decodeGroup(url.searchParams.get("group"));
      if (!group) return new Response("bad group", { status: 400 });
      if (request.headers.get("Upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
      const id = hex(group);
      // Hand the DO a canonical form so it never has to re-parse base64
      const inner = new URL(request.url);
      inner.search = `?group_hex=${id}`;
      return env.PAIR_GROUP.get(env.PAIR_GROUP.idFromName(id)).fetch(new Request(inner, request));
    }
    return new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;

/** base64url (no padding) of exactly 16 bytes, or null. */
export function decodeGroup(value: string | null): Uint8Array | null {
  if (!value || !/^[A-Za-z0-9_-]{22}$/.test(value)) return null;
  const bin = atob(value.replace(/-/g, "+").replace(/_/g, "/") + "==");
  return bin.length === 16 ? Uint8Array.from(bin, (c) => c.charCodeAt(0)) : null;
}
