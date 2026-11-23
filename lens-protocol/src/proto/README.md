# Lens server protos

Vendored unchanged from chromium/chromium `third_party/lens_server_proto` at the
commit in `CHROMIUM_COMMIT` (the same pin as chrome-lens-rs). BSD-licensed,
Copyright The Chromium Authors.

`aim_communication.proto` and `search_communication.proto` are left out: both
import `third_party/omnibox_proto`.

Most files are `edition = "2023"`/`"2024"`, which Wire cannot parse. The build
does not touch these files; `downgradeProtoEditions` writes proto2/proto3
copies into `build/generated/proto-downgraded`, and Wire reads those. Why and
how: `build-logic/src/main/kotlin/lens/buildlogic/ProtoEditionsDowngrader.kt`.
