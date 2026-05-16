---
name: ticket-worker
description: "Pick up and complete the next eligible ticket end-to-end (eligibility → claim → implement → test → PR → merge). Designed for /loop usage in worker containers."
user-invocable: true
---

# /ticket-worker — Single-iteration ticket worker

You are a **lightweight orchestrator** in a fleet. Your job is fleet management: claim a ticket, delegate the implementation to a sub-agent, then handle PR creation, merge, and cleanup based on the result. You do **not** write code yourself.

`/loop` invokes you periodically; each invocation does **one ticket end-to-end** (or exits if nothing's eligible).

## Usage

```
/ticket-worker
/ticket-worker --model sonnet
/ticket-worker --model opus
/ticket-worker --area plan
/ticket-worker --model sonnet --area shopping
```

- `--model` filters to issues labeled `model:<value>`. If omitted, use the model of the running session (Opus or Sonnet).
- `--area` filters to issues labeled `area:<value>`. Optional lane specialisation.

---

## Phase 1 — Eligibility query

Determine the repo from `git remote get-url origin`. Run one GraphQL query:

```bash
OWNER=$(git remote get-url origin | sed -E 's|.*[:/]([^/]+)/[^/]+\.git$|\1|')
REPO=$(git remote get-url origin | sed -E 's|.*[:/][^/]+/([^/]+)\.git$|\1|')
gh api graphql \
  -f query='query($o:String!,$r:String!){repository(owner:$o,name:$r){issues(first:50,states:OPEN,orderBy:{field:CREATED_AT,direction:ASC}){nodes{number assignees(first:1){totalCount} labels(first:20){nodes{name}} issueDependenciesSummary{blockedBy}}}}}' \
  -f o="$OWNER" -f r="$REPO" \
  | jq -r --arg m "model:sonnet" '.data.repository.issues.nodes
    | map(select(.assignees.totalCount==0
        and .issueDependenciesSummary.blockedBy==0
        and (.labels.nodes | map(.name) | index($m))
        and (.labels.nodes | map(.name) | (index("wip") or index("needs-spec") or index("needs-human")) | not)))
    | sort_by(.number) | .[0].number // empty'
```

Replace `"model:sonnet"` with the `--model` value (or your session model). If the output is empty, **exit cleanly** — no work available.

---

## Phase 2 — Atomic claim

```bash
gh issue edit <N> --add-assignee @me --add-label wip
sleep 2
gh issue view <N> --json assignees,labels
```

If `assignees.totalCount > 1`: remove yourself (`gh issue edit <N> --remove-assignee @me`) and exit — another worker got there first.

---

## Phase 3 — Derive branch name

Fetch just the title (minimal context — the sub-agent fetches the full body itself):

```bash
gh issue view <N> --json title,number | jq -r '.title'
```

Derive the branch name: `fix/issue-<N>-<2-4-word-slug>` from the title.

---

## Phase 4 — Spawn implementation sub-agent

Use the **Agent tool** to spawn a sub-agent. Pass a self-contained prompt (see template below). The sub-agent fetches its own issue context and handles all of: git sync, branch, read project memory, implement, test, commit, push.

**Wait for the sub-agent to complete** (foreground, not background).

Parse the sub-agent's output for a fenced block that starts with `IMPLEMENTATION_RESULT`:

```
IMPLEMENTATION_RESULT
status: success | failure
branch: fix/issue-<N>-<slug>
pr_title: Fix #<N>: <short description>
pr_body: |
  Closes #<N>

  ## Summary
  - bullet 1
  - bullet 2

  ## Verification
  - [x] `./mvnw test` passes
  - [x] <other checks>
```

For failure:
```
IMPLEMENTATION_RESULT
status: failure
reason: <one paragraph — what blocked the sub-agent>
```

If the sub-agent output contains no `IMPLEMENTATION_RESULT` block, treat it as a failure with reason "sub-agent returned no result block".

---

## Phase 5 — Act on sub-agent result

### On success

```bash
# PR is on the branch the sub-agent pushed
gh pr create --base dev-main \
  --title "<pr_title from result>" \
  --body "<pr_body from result>"

# Mirror labels
gh pr edit <PR#> --add-label "model:<model>,area:<area>"

# Merge
gh pr merge <PR#> --squash --delete-branch
```

If merge fails due to conflict: rebase the branch onto `dev-main`, retry once. If still conflicting, go to failure path.

If merge fails due to branch protection: post a comment on the issue (`Auto-merge blocked by branch protection — needs human merge.`), remove `wip`, exit.

### On failure

```bash
gh issue edit <N> --remove-label wip --remove-assignee @me
gh issue comment <N> --body "/cc orchestrator

<reason from result block or sub-agent output>

- What was tried: implementation sub-agent was spawned but did not complete
- What's needed: <human triage / spec clarification / dependency unblock>"
```

Exit cleanly.

---

## Phase 6 — Cleanup

After successful merge:

```bash
gh issue view <N> --json state   # confirm CLOSED (Closes #N in PR does this)
gh issue edit <N> --remove-label wip
```

Exit cleanly. `/loop` re-ticks.

---

## Sub-agent prompt template

Build this prompt dynamically, substituting only `<N>`, `<BRANCH>`, and `<MODEL>`. The sub-agent fetches its own issue context. Pass it to the Agent tool.

```
You are an implementation sub-agent for the Mise meal-planner project.
Your job: implement a fix for GitHub issue #<N>, commit it, and push the branch.
You do NOT create PRs or handle GitHub issue management — just the code.

## Steps

1. Fetch your ticket context:
   gh issue view <N> --json title,body,labels,number

2. Read `.claude/memory/MEMORY.md` and pull any linked files relevant to this ticket.
   These contain project gotchas and conventions that will save you debugging time.

3. Read `CLAUDE.md` at the repo root for conventions (especially CSS rules).

5. Sync and branch:
   git fetch origin
   git checkout dev-main
   git pull --ff-only origin dev-main
   git checkout -b <BRANCH>

6. Implement the fix. Key constraints from CLAUDE.md and project memory:
   - No inline CSS for static styling — use class names and put rules in the view's CSS file.
   - One CSS file per view (`mise-<view>.css`), imported from `styles.css`.
   - After editing any `mise-<view>.css`, also touch `styles.css` (add a blank line) so the
     Vaadin bundler rebuilds — sub-file edits alone are ignored.
   - Do NOT use Lumo `--lumo-*` tokens — they don't resolve under the Aura theme.
   - Do NOT bump Spring AI past 2.0.0-M4 (breaks `SpringAILLMProvider`).
   - No multi-line `/* */` comments inside `@media` blocks (breaks Vaadin CSS parser).
   - Do NOT edit `pom.xml`, `vite.config.ts`, or `spec/architecture.md` — escalate instead.
   - Screenshot context is in `Mise-review-1/` and `Mise-review-1/verify/`.

7. Run tests:
   ./mvnw test
   Tests must pass (175 tests, 0 failures). If they fail due to your change, fix it.
   If they fail for an unrelated reason, escalate (see below).

8. Commit (one commit):
   git add <changed files>
   git commit -m "Fix #<N>: <short description>

   <optional detail>

   Closes #<N>

   Co-Authored-By: Claude <MODEL> <noreply@anthropic.com>"

9. Push:
   git push -u origin <BRANCH>

## Output format

After completing your work, output **exactly** this block (fill in the fields):

\`\`\`
IMPLEMENTATION_RESULT
status: success
branch: <BRANCH>
pr_title: Fix #<N>: <short description matching commit>
pr_body: |
  Closes #<N>

  ## Summary
  - <bullet 1>
  - <bullet 2>

  ## Verification
  - [x] `./mvnw test` passes — 175 tests, 0 failures
  - [x] <any other checks performed>

  🤖 Auto-merged by /ticket-worker once tests are green.
\`\`\`

If you cannot complete the implementation (ambiguous spec, unrelated test failure,
would need to touch pom.xml/vite.config.ts/spec/, discovered blocker), output:

\`\`\`
IMPLEMENTATION_RESULT
status: failure
reason: <one paragraph describing exactly what blocked you and what a human needs to do>
\`\`\`

Do not attempt any GitHub operations (gh issue, gh pr). Those are the orchestrator's job.
```

---

## Failure paths (main agent)

Clean up and exit whenever:
- Sub-agent returns `status: failure`
- Sub-agent produces no `IMPLEMENTATION_RESULT` block
- PR merge fails after one rebase retry

Cleanup command:
```bash
gh issue edit <N> --remove-label wip --remove-assignee @me
gh issue comment <N> --body "/cc orchestrator

<reason>

- What you tried: ...
- What's needed: ..."
```

Never:
- Force-push to `dev-main`
- Bypass branch protection
- Edit `pom.xml`, `vite.config.ts`, or `spec/architecture.md`
- Delete branches that aren't yours

## Out of scope

- Rewriting `Blocked by` dependency relations — that's the orchestrator.
- Spec decisions on `needs-spec` issues — skip them entirely.
