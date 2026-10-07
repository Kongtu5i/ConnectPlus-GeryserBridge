# Official-host Geyser bridge implementation plan

> Implementation: inline, authorized by the user on 2026-10-04.

**Goal:** Deliver a runnable ConnectPlus acceptance package using byte-identical official ViaProxy and Geyser-ViaProxy, without host patches.
**Architecture:** A separate Geyser extension supplies verified XUID, exact TCP connection binding, and targeted disconnect. ConnectPlus treats authenticated duplicate admission as optional and gates its events by the registration capability snapshot. Official Geyser retains native duplicate-XUID rejection.
**Tech Stack:** Java 21 bridge, Java 17 ConnectPlus, Gradle, JUnit, Python verification.
**Spec:** docs/geyser-bridge-v1.md and ConnectPlus docs/integrations/geyser-bridge-v1.md.

## Global constraints
- Never edit, patch, replace host classes, inject bytecode, modify host internals, or intercept authentication/duplicate admission to alter ViaProxy or Geyser-ViaProxy behavior. Configuration and normal documented extension/plugin APIs are allowed.
- Preserve existing ConnectPlus working-tree changes. Root is not a git repository; sibling already has a feature branch. Do not commit unrelated work.
- Compile dependency checksums establish reproducible builds; host runtime compatibility must not require an exact Git commit or artifact checksum.
- Supported runtime series initially Geyser 2.11.x >=2.11.3, ViaProxy 3.4.x >=3.4.13, Java >=21, plus read-only adapter API probes. New series require reviewing the bridge adapter only.
- Real-client human acceptance remains pending until a human completes it.

## Review Focus
Review removal of every host-patch path, classloader linkage on official hosts, optional capability event gating and immutable snapshots, runtime version parsing and probes, package host provenance/checksums, and continued exact connection/identity validation.

## Tasks
1. Add behavior tests for baseline provider registration/resolve/validate/disconnect and rejection of undeclared duplicate events; replace exact-commit tests with compatible-series behavior tests.
   Verification: bridge verification runner and ConnectPlus endpoint test.
   Expected: new successful-registration/version tests fail before production changes.
2. Remove patched-event code/stubs/build workflow; register only three baseline capabilities; implement optional capability gating and compatible runtime series.
   Verification: complete bridge and ConnectPlus suites plus JAR build.
   Expected: all tests pass; bridge contains no host classes or patched event references.
3. Persist no-host-modification policy in both projects; synchronize protocol and acceptance expectations; acquire verified official host.
   Verification: inspect source/docs changes and official artifact checksum.
   Expected: native duplicate-XUID rejection explicitly documented and old patched archive marked obsolete.
4. Assemble isolated official-host acceptance package, run fresh host startup/registration/TCP/Bedrock UDP/clean shutdown verification, and obtain fresh reviewer.
   Expected: three capabilities registered, no custom duplicate event needed, official host bytes unchanged, human acceptance NOT_EXECUTED.

## Ledger
- Initial state: ConnectPlus branch feature/geyser-bedrock-account-linking has existing uncommitted work; preserved in place. Relevant files backed up before edits.

- Task 1 RED: bridge 34 tests, 2 expected compatibility failures; ConnectPlus endpoint 20 tests, 2 expected baseline registration failures.
- Task 2 implementation: removed all eight patch/hook/stub source files; declared only baseline capabilities; optional event gating uses immutable capability snapshot.

- Final review: fresh independent reviewer found two important issues: reused package directory and missing downstream remote address. Both fixed in one pass with failing behavior regressions followed by GREEN.
- Official smoke exposed decorated BuildData.VERSION; parser regression RED then GREEN; host untouched.
- Final fixes GREEN: complete bridge 36/36; package staging regression 1/1. ConnectPlus unchanged since 714/714 full suite.

- Task 2 GREEN: bridge 36/36; ConnectPlus 714 tests, zero failures/errors/skips.
- Task 3 complete: policies persisted in both AGENTS.md; protocols synchronized; official host checksum verified.
- Task 4 smoke PASS: official unchanged hosts, real plugins, three capabilities, assets, TCP/UDP, clean stop; human acceptance NOT_EXECUTED.
