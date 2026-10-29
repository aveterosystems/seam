// SPDX-License-Identifier: AGPL-3.0-only
// Generates Rust types for the shared protocol (protocol/seam/v1) and the core's own saved state.
fn main() {
    let protocol = "../../protocol";
    let mut paths: Vec<String> = std::fs::read_dir(format!("{protocol}/seam/v1"))
        .expect("protocol/seam/v1 exists")
        .filter_map(|e| e.ok().map(|e| e.path().to_string_lossy().into_owned()))
        .filter(|p| p.ends_with(".proto"))
        .collect();
    paths.sort();
    println!("cargo:rerun-if-changed={protocol}/seam/v1");
    println!("cargo:rerun-if-changed=proto/state.proto");
    prost_build::compile_protos(&paths, &[protocol]).expect("protocol .proto files compile");
    prost_build::compile_protos(&["proto/state.proto"], &["proto"]).expect("state.proto compiles");
}
