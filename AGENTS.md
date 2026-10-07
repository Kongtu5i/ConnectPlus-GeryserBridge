# Project instructions

## Permanent host boundary (user requirement, 2026-10-04)

All current and future functionality must use unmodified official ViaProxy and Geyser-ViaProxy.
Do not change their source, JAR contents, classes, authentication or duplicate-login logic.
Do not apply source patches, Java agents/instrumentation, ASM/mixins, replacement or shadow host classes,
or reflection/pipeline injections that change host behavior. Do not recreate a patched host through runtime hooks.
Ordinary documented plugin/extension APIs, normal operator configuration and read-only runtime inspection are allowed.

Implement features in ConnectPlus or the independent ConnectPlus-GeyserBridge extension.
Prefer public APIs. Keep necessary read-only internal access isolated in the adapter, with explicit supported
version series and fail-closed capability checks. A host upgrade must never require synchronizing a host patch.
If an official host API cannot provide a requested feature, explain the limitation and propose a plugin-side alternative.

Official Geyser's duplicate-XUID policy remains native: the existing client stays connected, the duplicate is refused.
The bridge must not advertise authenticated-duplicate-admission on current official hosts.
Java/Bedrock linked-profile session exclusivity remains a ConnectPlus responsibility.

Preserve unrelated working-tree changes. Keep the bridge protocol documents in both projects synchronized.
Record real-client acceptance separately from automated checks.
