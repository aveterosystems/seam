# Golden vectors

Binary messages encoded by `protoc` itself, independently of any of our code. The Kotlin, Swift and server tests each decode them and check the same fields, so all three codebases provably agree on the wire format.

Regenerate one after editing its `.txtpb`:

```bash
protoc -I protocol --encode=seam.v1.Inner seam/v1/payload.proto < protocol/testdata/inner_clipboard.txtpb > protocol/testdata/inner_clipboard.bin
```
