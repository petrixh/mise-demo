---
name: reference-ticket-skills
description: The two skills that drive the ticket fleet on petrixh/mise-demo — /ticket-worker (per container) and /ticket-orchestrator (Opus supervisor).
metadata: 
  node_type: memory
  type: reference
  originSessionId: 5e57c7f9-70ca-4790-af2d-65e0b087c8cf
---

Two skills under `.claude/skills/` in this repo drive the multi-container ticket fleet:

- **`.claude/skills/ticket-worker/SKILL.md`** — single-iteration worker. `/loop 10m /ticket-worker [--model opus|sonnet] [--area plan|shopping|reports|chat|ai|header]`. Eligibility query → atomic claim → context fetch → branch → implement → test → PR → squash-merge → cleanup. Failure path: `/cc orchestrator` comment + cleanup labels.
- **`.claude/skills/ticket-orchestrator/SKILL.md`** — fleet supervisor. `/loop 30m /ticket-orchestrator` from an Opus session. Six checks per tick: inventory, stall detection, failure triage, graph hygiene, new-finding sweep, critical-path status.

Both use native GitHub `Blocks`/`Blocked by` deps (GraphQL `addBlockedBy` mutation) and `model:*` / `area:*` / `wip` / `needs-spec` / `needs-human` labels. Workflow rationale: [[feedback-ticket-fleet-orchestration]].

**When to recall:** Any time the user mentions ticket queue management, container fleets, /loop for issue workers, or asks how to parallelize GH issue fixes on this repo.
