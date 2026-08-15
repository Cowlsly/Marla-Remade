// Copyright 2023 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Vendored: only run protobuf-codegen when `cargo` feature is active.
// Default vendored feature is `soong` (pre-generated src/ukey2_all_proto/*.rs), so
// skip protoc invocation unless `cargo` feature is enabled. This matches upstream
// intent and avoids requiring `protoc` on every host (Windows/NDK builds don't have it).
fn main() {
    #[cfg(feature = "cargo")]
    {
        use protobuf_codegen::Customize;
        protobuf_codegen::Codegen::new()
            .protoc()
            .includes(["proto"])
            .input("proto/ukey.proto")
            .input("proto/securemessage.proto")
            .input("proto/securegcm.proto")
            .input("proto/device_to_device_messages.proto")
            .customize(Customize::default().gen_mod_rs(true))
            .cargo_out_dir("proto")
            .run_from_script();
    }
    #[cfg(not(feature = "cargo"))]
    {
        // No build-script codegen needed when using soong pre-generated protos.
        println!("cargo:rerun-if-changed=proto/ukey.proto");
    }
}
