// SPDX-License-Identifier: AGPL-3.0-only
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { fromBinary, toBinary } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { InnerSchema } from "../src/generated/seam/v1/payload_pb.js";
import worker from "../src/index.js";

describe("protocol", () => {
  it("decodes the golden vector the same way Kotlin and Swift do", () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const bytes = readFileSync(join(here, "../../protocol/testdata/inner_clipboard.bin"));
    const msg = fromBinary(InnerSchema, bytes);
    expect([...msg.msgId]).toEqual([...Array(16).keys()]);
    expect(msg.sentAt).toBe(1793266000000n);
    expect(msg.payload?.body.case).toBe("clipboard");
    if (msg.payload?.body.case !== "clipboard") return;
    expect(msg.payload.body.value.text).toBe("ZK4 9QP · café");
    expect([...toBinary(InnerSchema, msg)]).toEqual([...bytes]);
  });
});

describe("worker", () => {
  it("answers the health check and nothing else", async () => {
    const health = await worker.fetch(new Request("https://relay.test/health"));
    expect(health.status).toBe(200);
    expect(await health.text()).toBe("ok");
    expect((await worker.fetch(new Request("https://relay.test/anything"))).status).toBe(404);
  });
});
