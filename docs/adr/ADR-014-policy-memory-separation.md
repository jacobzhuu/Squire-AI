# ADR-014: Policy / Memory Separation

## Status
Accepted (spec §4/ADR-014, §42)

## Context
Anything stored as "memory" is writable through model-driven flows. Authorization data must
never live there.

## Decision
Owner, permissions, economy mode, world-edit rights, command rights, MCP trust, provider
endpoint, remote MCP switch, killswitch and confirmation policy are Typed Policy
(`common.policy`, persisted separately), never Memory entries. Memory types are limited to
Working / Episodic / Semantic / World. Long-term memory writes follow
candidate → category → validation → dedup → persist. Default `persistConversation=false`.

## Consequences
- Even a fully prompt-injected model cannot escalate: policy stores are not addressable from
  memory/tool arguments.
- `/squire memory clear` cannot affect authorization state.
