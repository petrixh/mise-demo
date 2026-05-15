---
name: ticket-worker
description: "Pick up and complete the next eligible ticket end-to-end (eligibility → claim → implement → test → PR → merge). Designed for /loop usage in worker containers."
user-invocable: true
---

# /ticket-worker — Single-iteration ticket worker

You are a **single-iteration worker** in a fleet. `/loop` invokes you periodically; each invocation does **one ticket end-to-end** (or exits if nothing's eligible). Concurrency is bounded by the dependency graph encoded as native GitHub `Blocked by` relations, plus atomic claim via `assignees` + `wip` label.

## Usage

```
/ticket-worker
/ticket-worker --model sonnet
/ticket-worker --model opus
/ticket-worker --area plan
/ticket-worker --model sonnet --area shopping
```

Typical container invocation:

```
/loop 10m /ticket-worker --model sonnet
```

- `--model` filters to issues labeled `model:<value>`. If omitted, use the model of the running session (Opus or Sonnet).
- `--area` filters to issues labeled `area:<value>`. Useful for "lane" specialisation but optional.

## What you do (one pass)

### Phase 1 — Eligibility query

Determine the repo from `git remote get-url origin` (strip to `owner/name`). Then run **one** GraphQL query for minimal fields — no body fetches yet:

```bash
gh api graphql -f query='
query($owner: String!, $repo: String!) {
  repository(owner: $owner, name: $repo) {
    issues(first: 50, states: OPEN, orderBy: {field: CREATED_AT, direction: ASC}) {
      nodes {
        number
        assignees(first: 1) { totalCount }
        labels(first: 20) { nodes { name } }
        issueDependenciesSummary { blockedBy }
      }
    }
  }
}' -f owner=<OWNER> -f repo=<REPO>
```

Filter the result locally to issues that satisfy **all** of:

- `state == OPEN`
- `assignees.totalCount == 0`
- `issueDependenciesSummary.blockedBy == 0`
- Labels do **not** include `wip`, `needs-spec`, or `needs-human`
- Labels **include** `model:<your-model>` (matching `--model` or your running session)
- If `--area X` was passed: labels include `area:<X>`

Pick the **lowest-numbered** match. If none, exit cleanly — `/loop` sleeps until the next tick.

### Phase 2 — Atomic claim

```bash
gh issue edit <N> --add-assignee @me --add-label wip
```

Wait 2 seconds, then re-fetch the issue:

```bash
gh issue view <N> --json assignees,labels
```

If `assignees.totalCount > 1` (race) or `wip` was added by someone else first, **back off**: remove yourself with `gh issue edit <N> --remove-assignee @me` and exit. Another worker got there first.

### Phase 3 — Fetch context

Only **now** read the full issue body and any linked artefacts:

```bash
gh issue view <N> --json title,body,labels,number > /tmp/issue.json
```

The body for every Mise review ticket points at:
- `Mise-review-1/Screenshot <timestamp>.png` (reviewer screenshots)
- `Mise-review-1/verify/*.png` (Playwright verification screenshots, if any)
- `Mise-review-1/findings.md` — search for the trailing `Finding ID:` line to find the matching entry

Read those for full context. Pay particular attention to:
- The reviewer's transcript quote
- Verification notes from earlier in this conversation
- "Acceptance" / "Likely fix" sections in the issue body

### Phase 4 — Implementation

1. **Sync**: `git fetch origin`, `git checkout dev-main`, `git pull --ff-only origin dev-main`.
2. **Branch**: `git checkout -b fix/issue-<N>-<short-slug>` where `<short-slug>` is 2–4 hyphenated words derived from the title.
3. **Read project memory first**: scan `.claude/memory/MEMORY.md` for topics that touch your ticket. Pull in linked files — codebase quirks, references, and workflow feedback live there and *will* save you from re-discovering known traps.
4. **Implement the fix**. Constraints:
   - Stay inside files identified by the issue body. If your fix would touch unrelated files, **stop** and escalate (see Failure paths).
   - Respect `CLAUDE.md` conventions — especially the **CSS rules**: no inline styling (use class names), one CSS file per view (`mise-<view>.css`), and for the master `styles.css` only **add** `@import` lines, never edit shared tokens.
   - Quick-reference gotchas (full detail in `.claude/memory/`):
     - Don't bump Spring AI past 2.0.0-M4 (breaks Vaadin AI).
     - Don't use Lumo `--lumo-*` tokens — they don't resolve under Aura.
     - Multi-line `/* */` comments inside `@media` blocks break the Vaadin CSS bundler.
     - When editing `mise-<view>.css`, also touch `styles.css` (no-op whitespace) so the bundler rebuilds — sub-file edits alone are ignored.
5. **Tests**:
   - `./mvnw test` — must pass. This runs unit + Browserless tests.
   - For view changes: if the change is visible on screen, also smoke-test with Playwright MCP at 1440×900 (desktop) and 390×844 (mobile). Compare against the `Mise-review-1/` baseline screenshots if applicable.
   - For AI tool changes (issues #5, #6): consider running `./mvnw -Pai-it verify` against the live LLM. Slow (~minutes) — only if relevant.
6. **Commit**: one commit with a clear message ending with `Closes #<N>`. Co-author tag:
   ```
   Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
   ```
   (Use your actual model name in the co-author line.)

### Phase 5 — PR + merge

1. **Push**: `git push -u origin fix/issue-<N>-<short-slug>`.
2. **Open PR**:
   ```bash
   gh pr create --base dev-main --title "Fix #<N>: <slug>" --body "$(cat <<EOF
   Closes #<N>

   ## Summary
   <1-3 bullets describing the change>

   ## Verification
   - [x] \`./mvnw test\` passes locally
   - [x] <view-specific smoke if applicable>

   🤖 Auto-merged by /ticket-worker once tests are green.
   EOF
   )"
   ```
3. **Mirror labels**: add the issue's `model:*` and `area:*` labels to the PR for visibility.
4. **Self-merge**:
   - `gh pr merge <PR#> --squash --delete-branch --auto` (uses GH auto-merge if CI is wired; immediate squash if not).
   - If merge fails due to merge conflict on `dev-main`: rebase and retry once. If still conflicting, escalate (Failure paths).
   - If merge fails due to branch protection: leave the PR open, post a comment `Auto-merge blocked by branch protection — needs human merge.`, remove `wip`, exit. The orchestrator will surface this.

### Phase 6 — Cleanup

After successful merge:
- The `Closes #<N>` automatically closes the issue. Verify with `gh issue view <N> --json state`.
- Remove the `wip` label (issue close removes assignee but labels persist):
  ```bash
  gh issue edit <N> --remove-label wip
  ```
- Optionally: post a short close-out comment with the PR URL.

Exit cleanly. `/loop` will re-tick.

## Failure paths

If anything fails irrecoverably in Phases 3–5, **always** clean up before exiting:

```bash
gh issue edit <N> --remove-label wip --remove-assignee @me
gh issue comment <N> --body "/cc orchestrator

<one-paragraph description of what blocked you>

- What you tried: ...
- What's needed: <human triage / spec clarification / unrelated fix / dependency unblock>"
```

Examples:
- **Ambiguous spec**: post comment, exit. Orchestrator labels `needs-spec`.
- **Unrelated regression in tests**: post comment with failing test names, exit. Orchestrator may label `needs-human`.
- **Discovered blocker** (e.g. realized this ticket actually depends on another open one): post comment naming the blocker. Orchestrator adds the `Blocked by` link.
- **Merge conflict that can't be auto-resolved**: post comment, exit.

Never:
- Force-push to `dev-main`.
- Bypass branch protection (`--no-verify`, `--admin`).
- Delete branches that aren't yours.
- Edit `pom.xml`, `vite.config.ts`, or `spec/architecture.md` — those require human approval per `CLAUDE.md` guardrails. Escalate instead.

## Out of scope for you

- **The dependency graph** is owned by the orchestrator. If a ticket seems mis-ordered, escalate; don't rewrite `Blocked by` relations.
- **Spec decisions** (anything labeled `needs-spec`) — skip these entirely.

## Snippets reference

**Get owner/repo**:
```bash
git remote get-url origin | sed -E 's|.*[:/]([^/]+)/([^/]+)\.git$|\1 \2|'
```

**Issue eligibility check (drop-in)**:
```bash
OWNER=$(git remote get-url origin | sed -E 's|.*[:/]([^/]+)/[^/]+\.git$|\1|')
REPO=$(git remote get-url origin | sed -E 's|.*[:/][^/]+/([^/]+)\.git$|\1|')
MODEL=${1:-sonnet}  # default; override via --model
gh api graphql \
  -f query='query($o:String!,$r:String!){repository(owner:$o,name:$r){issues(first:50,states:OPEN,orderBy:{field:CREATED_AT,direction:ASC}){nodes{number assignees(first:1){totalCount} labels(first:20){nodes{name}} issueDependenciesSummary{blockedBy}}}}}' \
  -f o="$OWNER" -f r="$REPO" \
  | jq -r --arg m "model:$MODEL" '.data.repository.issues.nodes
    | map(select(.assignees.totalCount==0
        and .issueDependenciesSummary.blockedBy==0
        and (.labels.nodes | map(.name) | index($m))
        and (.labels.nodes | map(.name) | (index("wip") or index("needs-spec") or index("needs-human")) | not)))
    | sort_by(.number) | .[0].number // empty'
```

If the output is empty, no work — exit.
