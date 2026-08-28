A privileged Wi-Fi and cell network location and geocoder provider

Features:
- Supplies the system network location provider, so apps get a fix without GPS
- Supplies the system geocoder, resolved from a bundled database
- Your position is solved on device by native Rust, never by a server
- Bundled Wi-Fi and cell beacon databases are tried before any network lookup
- Only beacon identifiers ever leave the device, never your position
- Always on, with nothing to configure

Internet only used for: beacons missing from the bundled offline database
