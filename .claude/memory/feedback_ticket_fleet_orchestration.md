---
name: feedback-ticket-fleet-orchestration
description: "For multi-ticket workloads in this repo, use the pull-based fleet pattern (native GH deps + /ticket-worker + /ticket-orchestrator skills) instead of stage-based push orchestration or pasted prompts."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 5e57c7f9-70ca-4790-af2d-65e0b087c8cf
---

When the user asks to "fan out work" / "split tickets across containers" / "tackle these issues in parallel" on this project, default to the **pull-based fleet** model:

- **Native GitHub `Blocks` / `Blocked by` relations** encode the conflict graph on issues themselves (use `addBlockedBy` / `removeBlockedBy` GraphQL mutations; `gh issue edit` doesn't have a native flag yet).
- **Labels `model:opus|sonnet`, `area:<view>`** filter tickets to workers; **`wip` / `needs-spec` / `needs-human`** gate eligibility and triage.
- **Per-container `/loop 10m /ticket-worker`** — single-iteration worker, atomic claim via `assignees` + `wip`, body fetched only after claim succeeds (saves tokens).
- **Orchestrator (Opus) `/loop 30m /ticket-orchestrator`** — supervises: stalls, escalations (`/cc orchestrator` comments), graph hygiene, new-finding sweep, status reports.
- Workers self-merge after `./mvnw test` passes; **`/ultrareview` is operator-triggered only** and cannot be invoked by workers.

**Why:** Established through this session's plan iterations after the user (a) explicitly rejected a stage-based push plan in favour of pull + `Blocked by` polling, (b) requested skills (not pasted prompts) as the encapsulation unit, and (c) called out that native GH features beat reinventing in body-text. Future sessions should not re-debate these choices.

**How to apply:** Skill files live at [[reference-ticket-skills]]. When new multi-ticket work arrives, the orchestration setup is: create labels → label issues → wire `Blocked by` graph → kick off workers. See `.claude/skills/ticket-orchestrator/SKILL.md` for the supervisor logic.

Related project memory: `Mise-review-1/findings.md` is the source of truth that links each issue → finding ID → reviewer screenshot → verification notes. The fleet was first deployed on issues #5–#27 (May 2026 review).
