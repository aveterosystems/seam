# Seam relay

The relay, a TypeScript Cloudflare Worker deployed with wrangler. A PairGroup Durable Object holds each pair of devices' WebSockets and offline queue, R2 holds encrypted file chunks for up to 24 hours, and the Worker sends FCM and APNs pushes to wake sleeping devices. Devices authenticate by signing a challenge with their hardware key. The relay never sees keys or plaintext. Tests use vitest.
