// Copyright 2024 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

pub fn cpp_bytes(bytes: &[u8]) -> impl std::fmt::Display + '_ {
    display_bytes("0x", bytes)
}

pub fn java_bytes(bytes: &[u8]) -> impl std::fmt::Display + '_ {
    display_bytes("(byte) 0x", bytes)
}

pub fn display_bytes<'a>(prefix: &'a str, bytes: &'a [u8]) -> impl std::fmt::Display + 'a {
    use std::fmt;

    struct DisplayBytes<'a> {
        prefix: &'a str,
        bytes: &'a [u8],
    }

    impl fmt::Display for DisplayBytes<'_> {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            let prefix = self.prefix;

            write!(f, "{{")?;

            let mut first = true;
            for byte in self.bytes {
                if !first {
                    write!(f, ", ")?;
                } else {
                    first = false;
                }
                write!(f, "{prefix}{byte:02X}")?;
            }

            write!(f, "}}")?;
            Ok(())
        }
    }

    DisplayBytes { prefix, bytes }
}

pub fn java_static<'a>(name: &'a str, bytes: &'a [u8]) -> impl std::fmt::Display + 'a {
    use std::fmt;

    struct JavaStatic<'a>(&'a str, &'a [u8]);

    impl fmt::Display for JavaStatic<'_> {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            let JavaStatic(name, bytes) = *self;
            write!(f, "public static final byte[] {name} = {};", java_bytes(bytes))
        }
    }

    JavaStatic(name, bytes)
}

pub fn cpp_static<'a>(name: &'a str, bytes: &'a [u8]) -> impl std::fmt::Display + 'a {
    use std::fmt;

    struct CppStatic<'a>(&'a str, &'a [u8]);

    impl fmt::Display for CppStatic<'_> {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            let CppStatic(name, bytes) = *self;
            let len = bytes.len();
            write!(f, "constexpr std::array<uint8_t, {len}> {name} = {};", cpp_bytes(bytes))
        }
    }

    CppStatic(name, bytes)
}
