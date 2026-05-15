---
name: feedback-track-experiments-as-gh-issues
description: "For comparison runs / bake-offs / experiments that span multiple sessions, track them as GitHub issues (body = spec, comments = per-run results) — don't commit eval scripts or plan files"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 465b0e19-fa5f-4bc9-82e1-ea2b6e965dff
---

When the user wants to track an experiment, bake-off, or comparison that will accumulate results over time and across sessions (e.g. the local-model AIIT bake-off in `petrixh/mise-demo#4`), the right home is a **GitHub issue in the project's repo**, not a committed plan file or eval script.

Shape:
- **Issue body** = the spec / what to test / how to run it. Stays stable.
- **Comments** = structured per-run results, one comment per run, using a consistent template defined in the body.
- **Final summary comment** when enough data is in.

**Why:** Keeps the bake-off recoverable across sessions, lets us compare past runs, and avoids polluting the codebase with experiment scaffolding. The user explicitly redirected away from "create `eval/results/` directory + plan file" toward `gh issue create` for exactly this reason.

**How to apply:** When a session lands on "we want to run X across multiple Y and compare," default to drafting a GitHub issue, not a committed artifact. Same for any multi-session experimentation that benefits from a permanent rolling log. Confirm with the user only if the scope is ambiguous (e.g. for a one-off comparison that won't be revisited, a plain Markdown file might still be fine).
