---
name: project-aiit-forkcount-override
description: "The ai-it Maven profile hardcodes forkCount=2 in pom.xml line 217 and the `-DforkCount=1` CLI override does NOT take effect for Failsafe — edit pom.xml directly (or use a different property) to force serial execution against single-concurrent LLM endpoints like LM Studio."
metadata: 
  node_type: memory
  type: project
  originSessionId: 465b0e19-fa5f-4bc9-82e1-ea2b6e965dff
---

The `ai-it` profile in `pom.xml` (around line 217) has `<forkCount>2</forkCount>` hardcoded. The natural CLI override `./mvnw -Pai-it verify -DforkCount=1` is **silently ignored** for the Failsafe plugin — two forks still spawn, and against a single-concurrent LM Studio endpoint they both queue at the server.

Empirically (qwen3.5-9b bake-off, 2026-05-14): both forks spawn (PIDs visible in test output), queue serially at LM Studio, and the wall-clock matches single-fork execution. So the override didn't break correctness — it just didn't help. But for endpoints that *do* fan-out parallel-2 properly (the `.196` host), the dual fork is appropriate.

**How to apply:** Before running AIIT against an LM-Studio-class endpoint, temporarily edit `pom.xml:217` from `<forkCount>2</forkCount>` → `<forkCount>1</forkCount>` and revert when finished. Don't waste cycles trying `-DforkCount=1` on the CLI — it doesn't take.

Possible alternatives worth trying if the pom edit is annoying:
- `-Dfailsafe.forkCount=1` (some Failsafe versions read this)
- A second `<profile>` in pom.xml (e.g. `ai-it-serial`) that copies `ai-it` but with `forkCount=1`

Either of these is a small one-time pom change that would avoid the temp-edit dance.
