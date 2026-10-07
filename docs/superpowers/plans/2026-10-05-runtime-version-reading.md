# Runtime version reading implementation plan

> **For agentic workers:** Implement inline with test-driven-development and verification-before-completion. Steps use checkbox syntax for tracking.

**Goal:** Register the existing bridge on official ViaProxy 3.4.14 without mistaking the ConnectPlus compile dependency version for the running host version.

**Architecture:** Read the public VERSION field at runtime inside ConnectPlus's existing ViaProxyCompat adapter. Keep the bridge protocol and version equality check intact. Use a separate build copy of the current working tree, then apply only the reviewed source, test and documentation changes to ConnectPlus.

**Tech Stack:** Java 17 plugin, Java 21 verification, Gradle, JUnit 5.

**Spec:** User-reported `declares host version 3.4.14 but this process runs 3.4.13`; permanent host boundary in AGENTS.md.

## Global constraints

- Never modify official ViaProxy or Geyser source, classes, JAR contents or authentication behavior.
- Preserve unrelated working-tree changes and keep both bridge protocol documents identical.
- Record automated verification separately from real-client acceptance.

## Task 1: Correct runtime host version resolution

Files: ConnectPlus `src/main/java/dev/connectplus/compat/ViaProxyCompat.java`, new `src/test/java/dev/connectplus/compat/ViaProxyCompatTest.java`, both `geyser-bridge-v1.md` protocol documents.

- [x] Add regression tests loading an unmodified official runtime host separately from the plugin's compile dependency; test matching registration and genuine version mismatch rejection.
- [x] Run against official ViaProxy 3.4.14 and observe the current code returning 3.4.13 and rejecting matching registration.
- [x] Replace the constant reference with read-only access to the public VERSION field in ViaProxyCompat.
- [x] Run the targeted tests on 3.4.14, the complete ConnectPlus suite on the compile baseline, and build the plugin.
- [x] Synchronize the runtime-version diagnostic explanation in both protocol documents.
- [x] Apply only these changes to the original ConnectPlus tree and deliver a verified JAR with checksums and acceptance status.

## Review focus

Verify the official host bytes remain unchanged, the runtime field is never written, unsupported runtime-series checks remain in the bridge, genuine version mismatches remain rejected, and the JAR contains no host classes.

## Completion evidence

- Regression: 2 expected failures before the fix, 2 passing tests on official 3.4.14 afterward.
- Full suite: 716 tests, 0 failures/errors/skips; Gradle build successful.
- Actual isolated startup: bridge registered on official 3.4.14 + Geyser 2.11.3 build 1247; TCP/UDP checks and clean shutdown passed; both host hashes unchanged.
- Source copy and synchronized documents verified byte-for-byte; plugin archive contains no host classes.
- Independent reviewer: no production blockers. Deferred minor: an invalid optional test runtime JAR could fall back to baseline URLs under a URL-based test loader; the actual supplied official 3.4.14 JAR and result were independently verified. Reproducible invocation and required init script are included in the fix package.
- Real-client acceptance remains NOT_EXECUTED.
