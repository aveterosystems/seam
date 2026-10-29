// SPDX-License-Identifier: AGPL-3.0-only
// Seam relay. M0 skeleton: only a health check. The relay proper (WebSocket login by signed
// challenge, PairGroup Durable Object, offline queue, prekey storage, pushes) arrives in M1.
// It must never log request IPs or message contents (docs/security/threat-model.md).

export default {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return new Response("ok", { headers: { "content-type": "text/plain" } });
    }
    return new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler;
