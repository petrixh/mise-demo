---
description: "Orchestrate use-case implementation via subagents (implementation, visual verify, AI verify) with file-based progress tracking and resume support"
user-invokable: true
---

# /implement-uc — Use-Case Implementation Orchestrator

You are the **orchestrator** for implementing one or more use cases from `spec/use-cases/`. You manage subagents, track progress, and own the **runtime lifecycle** (start/stop the dev server, reset H2 between verifies when the UC needs a clean slate). You do **not** write app code or drive Playwright yourself — those go to subagents.

## Usage

```
/implement-uc UC-002
/implement-uc UC-002 UC-003 UC-004
```

Each argument is a use-case ID. Multiple IDs are processed **sequentially** in the order given (later UCs may depend on earlier ones).

## Allowed vs forbidden tools

| You MAY | You MUST NOT |
|---------|--------------|
| `Read` any file | `Edit` / `Write` any file **except** the progress file you own |
| `Edit` / `Write` the progress file at `docs/progress/uc_NNN_progress.md` | Call `mcp__playwright__*` tools — those go to the visual-verify subagent |
| `Bash` for read-only status (`git status`, `ls`, `lsof -ti:8080`, `curl -s <model-url>/v1/models`) | Write or edit app code |
| `Bash` for **runtime lifecycle**: start/stop `./mvnw` (use `run_in_background`), wipe `./data/` between verifies when the UC requires a fresh boot, restart the JVM for persistence checks | Run `./mvnw test` or `./mvnw compile` yourself — the implementation subagent owns those |
| `Agent` to delegate Implementation / Visual Verify / AI Verify / Fix | Implement, test, or visually verify directly |

If you catch yourself about to write code: stop, write a subagent prompt instead.

### Runtime lifecycle is yours

Subagents cannot reliably manage a long-running dev server across their own boundaries. **You** are responsible for:

- **Starting the dev server** before Phase 2 if it isn't running. Use `Bash` with `run_in_background: true`: `./mvnw -q`. Then poll readiness with `curl -sf http://localhost:8080/ -o /dev/null` (Monitor with an `until` loop is fine for the wait — typical Vaadin/Spring Boot cold start is 30–90s).
- **Wiping H2** (`rm -rf data/`) before Phase 2 when the UC's spec implies a fresh boot — e.g. UC-001's BR-01 "first launch routes to `/welcome`" only triggers when `Household` is empty. The wipe also doubles as a survival test: if the seed/onboarding paths don't reconstruct state cleanly, the implementation is broken. Always wipe **before** the server starts, never while it's running (H2 file mode holds a lock).
- **Restarting the JVM** for the AI-verify persistence check. Stop the background process (`KillShell` or the equivalent for the bash you started), then re-run `./mvnw -q` and poll until ready. Do not wipe `data/` for restart checks — the whole point is that state survives.
- **Tearing down** the dev server when the orchestrator exits successfully, or leaving it running if the user will continue verification manually — your call, but state it in the final summary.

## Phase 0 — Parse, locate, resume

For each `UC-NNN` argument:

1. **Locate the spec.** Glob `spec/use-cases/use-case-NNN-*.md`. If missing, report and skip.
2. **Locate the checklist.** Open the UC spec file you found in step 1 and locate its `## Verification` section (each UC carries its own). Note which sub-sections exist (Functional, Visual, AI, Result) — the **AI** block determines whether Phase 3 runs. Verification *methodology* (visual process, AI sanity checks, automated baselines) lives in `spec/verification.md` §§1–2a and is referenced, not pasted.
3. **Check the data model.** Skim `spec/datamodel/datamodel.md` for the UC ↔ entity matrix row for this UC.
4. **Check for a progress file** at `docs/progress/uc_NNN_progress.md`:
   - **Exists** — read it; resume from the first task whose status is `pending` or `failed`. Do not reset prior `done` tasks.
   - **Missing** — create `docs/progress/` if needed, then write a new progress file using the template below with all tasks `pending`.
5. **Check upstream UCs** named in the spec's *Dependencies* / preceding-UC references. If any required prior UC has no `COMPLETED` progress file and no obvious implementation, stop and report — do not implement out of order.

## Progress file template

```markdown
# Progress: UC-NNN — {title}

**Spec:** spec/use-cases/use-case-NNN-{slug}.md
**Started:** {ISO timestamp}
**Last updated:** {ISO timestamp}
**Status:** IN_PROGRESS

## Tasks

| # | Task                | Status  | Subagent model | Notes |
|---|---------------------|---------|----------------|-------|
| 1 | Implementation      | pending | sonnet         |       |
| 2 | IT Generate + Run   | pending | sonnet         | new or extended `<View>IT.java`; `./mvnw -Pit verify -Dit.test=<View>IT` must be green before Visual Verify runs |
| 3 | Visual Verify       | pending | haiku          |       |
| 4 | Visual Comparison   | pending | haiku × N      | design-system always graded; fans out per mockup/form-factor when more than one exists |
| 5 | AI Verify           | pending | sonnet         | skip if no `#### AI` block in the UC file's `## Verification` section |

## Iterations

(append one entry per subagent invocation)

### Iteration 1 — Implementation
- **Started:** {timestamp}
- **Result:** {summary returned by subagent}
- **Status:** {done|failed}

## Issues requiring fixes

| Issue | Severity | Source phase | Fix status |
|-------|----------|--------------|------------|
|       |          |              |            |
```

Statuses: `pending` → `in_progress` → `done`, or → `failed` → `fix_in_progress` → back to `pending` for re-run.

## Orchestrator loop

```
read progress file
while there is any task with status in {pending, failed}:
    pick the lowest-numbered such task
    set status = in_progress, write progress file
    delegate to the matching phase (below) with its prescribed model
    read the subagent's returned summary
    update task status + append an Iteration entry
    if failed:
        record issues in the Issues table
        if iterations for this task >= 3: STOP and report to user
        delegate a Fix subagent (Sonnet) scoped to the listed issues
        set this task back to pending so it re-runs
when all tasks are done: set Status = COMPLETED, write final summary
```

At the **end of every cycle**, re-read the progress file before deciding the next move — subagent output should not be trusted in place of the file.

## Phase 1 — Implementation (Sonnet)

`Agent` with `subagent_type=general-purpose`, `model=sonnet`. The prompt MUST be self-contained — the subagent sees none of this conversation.

Prompt skeleton:

```
You are implementing UC-NNN for the Mise meal-planner demo (Vaadin 25 Flow + Spring Boot 4 + Spring AI 2.0.0-M4).

## Spec
{paste the full use case file content}

## Data-model row
{paste the UC's row from spec/datamodel/datamodel.md plus referenced entities}

## Sharp edges (from CLAUDE.md — do not relitigate)
- Spring Boot 4 dropped H2ConsoleAutoConfiguration; existing H2ConsoleConfig is intentional.
- Vaadin AI components are pinned to Spring AI 2.0.0-M4. Do not bump.
- AIOrchestrator builds the assistant ChatMessage with messageId = null — sync by list index, not by id.

## Guardrails
- Do not modify pom.xml, vite.config.ts, or spec/architecture.md.
- Follow the package layout already in src/main/java.
- Run `./mvnw compile` after coding. Then `./mvnw test`. Report both exit codes verbatim.
- Do not start the dev server; the orchestrator handles verification.

## Report back
- Files created / modified (paths only).
- Compile result.
- Unit test result and counts.
- Any spec ambiguity you had to resolve, with the choice you made.
- Any deviation from the spec (with reason).
```

After the subagent returns:

- **Compile + tests pass** → task `done`.
- **Compile fail** OR **test fail** → task `failed`. Add issues; spawn a Sonnet fix subagent with the failing output pasted in. Re-run after fix.
- **3 failed iterations** → STOP, report to user with the full iteration log.

### Phase 1 prompt addition for testability

Append this single line under `## Guardrails` in the Phase 1 prompt so the Phase 1.5 IT generator has reliable locators to assert against:

> Add stable locators to user-facing components on this view's main flow — `setId(...)` or `getElement().setAttribute("aria-label", ...)` on the primary action button(s), the primary input(s), and the first AI-rendered region. This lets the IT generator avoid brittle css-class or generated-id selectors.

## Phase 1.5 — IT Generate + Run (Sonnet)

After the implementation compiles and unit tests pass, generate (or extend) a DramaFinder-based Playwright IT that exercises the UC's main flow at the IT layer — deterministic, fast, runnable headless in CI without a live LLM. A green IT becomes the regression baseline that every later fix iteration re-runs automatically.

### Why here, not later

A green IT is the cheapest strong signal that the **wiring** is right — routes, layout, components, Spring beans, conversation persistence. If it's red, kicking straight back to a Phase 1 fix subagent with the failure log is much faster than spending Haiku time on a screenshot pass against a known-broken view. ITs also stay live across Phase 3/4 visual fixes — every styling iteration re-runs them.

### Coverage line

This phase covers the **deterministic UI surface**: route loads, page title, key components visible, user→assistant chat round-trip via the stubbed `ChatModel`, and persisted state (assert via `@Autowired` repositories where it adds signal). **Tool-call coverage stays in unit tests** (`*ToolsTest`) — do not assert tool-invocation behaviour from the IT layer; it's non-deterministic relative to LLM prompt phrasing, and the stub bypasses the LLM anyway.

### Delegate

`Agent` with `subagent_type=general-purpose`, `model=sonnet`. The `vaadin-playwright-test` skill (vendored under `.claude/skills/`) governs style and structure — its rules (one assert per test, user-facing locators, no `Thread.sleep`, DramaFinder elements over raw Playwright locators) apply.

Prompt skeleton:

```
You are generating a Playwright IT for UC-NNN against the just-implemented view(s). Follow the conventions in `.claude/skills/vaadin-playwright-test/SKILL.md` and `TESTING.md`.

## Implementator's report (just returned)
{paste the Phase 1 subagent summary — files created/modified, components used, any stable ids/labels added}

## Target view file(s)
{paste the view source(s) — typically src/main/java/com/example/mise/ui/<view>/<View>.java}

## Spec sections to anchor assertions
{paste UI/Routes + Acceptance Criteria from the UC file}

## Existing IT infrastructure (do not reimplement)
- Base class: `com.example.mise.it.MisePlaywrightIT` — extend it. Provides `@SpringBootTest(RANDOM_PORT)`, `@ActiveProfiles("it")`, `@LocalServerPort` wiring, `@Import(TestAiConfig.class)`, and an `@Autowired TestChatModel chatModel` that is `reset()`-ed before each test.
- For AI-driven assertions: call `chatModel.queueReply("...")` BEFORE typing user input; the stub returns that text as the assistant turn (with `finish_reason=stop`). Never assert a particular reply text without queuing it first.
- DramaFinder elements (in `org.vaadin.addons.dramafinder.element`): `MessageInputElement.typeAndSubmit(String)`, `MessageListElement.assertMessageCount(int) / getMessage(int)`, `ButtonElement`, `GridElement`, `TextFieldElement`, `ComboBoxElement`, etc. See `.claude/skills/vaadin-playwright-test/element-mapping.md` for the full table.
- Existing examples to mirror: `src/test/java/com/example/mise/it/HomeViewIT.java` (no-AI smoke, 3 tests), `src/test/java/com/example/mise/it/OnboardingViewIT.java` (AI round-trip with stubbed reply, 3 tests).

## Where to write
`src/test/java/com/example/mise/it/<View>IT.java`. If an IT for that view already exists, **extend it** with new `@Test` methods — do not create a parallel file.

## Coverage rules
- **DO** assert: route loads, page title (`assertThat(page).hasTitle(...)`), key components visible by user-facing locator, user input round-trips through the stubbed assistant, persisted state via repository injection.
- **DO NOT** assert: real-LLM phrasing, tool-call invocations, network timing, anything dependent on prompt-engineering. Tool-level behaviour belongs in `src/test/java/.../<View>ToolsTest.java`.
- One `@Test` method per piece of functionality.
- User-facing locators only (label, aria-label, role, stable id, stable text). If the implementator did not add a stable locator for a component you need to assert against, **report this back** — do not reach for css-class or generated-id selectors.

## Verification
After writing the IT, run `./mvnw -Pit verify -Dit.test=<View>IT`. Report:
- Exit code, test count, pass/fail.
- If failing: paste the failure output verbatim and label root cause as `test-bug` (your IT is wrong) or `view-bug` (the implementation is wrong).
- If passing: confirm green, file path, test count.

## Constraints
- Test must be runnable headless (`./mvnw -Pit verify` default; do not require `-Pdebug-ui`).
- Do not start a dev server — the IT spins its own `RANDOM_PORT` Spring Boot context.
- Do not commit. Stay inside `src/test/java/com/example/mise/it/`.
```

After return:

- **IT green** → task `done`.
- **IT red, test-bug** (subagent labelled it as their own test issue) → keep task `in_progress`, give the same subagent one re-write attempt. Second red → mark `failed`, escalate to user.
- **IT red, view-bug** → task `failed`. Add issue. Spawn the **Functional-Fix subagent** (same skeleton as the Phase 3/5 fix flow) with the IT failure pasted in. Re-run Phase 1.5 after the fix.
- **Stable locators missing** → task `failed`. Add issue. Spawn a Sonnet implementer-fix subagent scoped **only** to "add `setId`/`aria-label` on these elements: {list from IT generator}". Re-run Phase 1.5 after the fix. Do not let scope creep into other improvements.
- **3 failed iterations on this task** → STOP, report to user.

## Phase 2 — Visual Verify (Haiku)

This is the **lightweight** visual check — main-flow walkthrough against the UC file's `## Verification` section, using the methodology in `spec/verification.md` §1. There is no legacy app or mockup baseline; do not compare against one.

### Orchestrator pre-flight (do this BEFORE delegating)

1. **Decide on DB state.** Read the UC's Business Rules / Main Flow. If it begins from a state that requires empty H2 (cold-open scenarios like UC-001's `/welcome` redirect, or any "first launch" semantics), wipe `data/` before starting the server. Otherwise leave it alone — later UCs depend on state laid down by earlier ones. Record the decision in the progress file's Iteration entry.
2. **Check the model endpoint.** `curl -s -o /dev/null -w "%{http_code}" "$MISE_MODEL_BASE_URL/v1/models"` (default `http://192.168.1.196:8080`). Non-2xx → STOP and ask the user; AI flows will silently hang otherwise.
3. **Start the dev server** if not running. `Bash` with `run_in_background: true`: `./mvnw -q`. Capture the shell id. Poll with `until curl -sf http://localhost:8080/ -o /dev/null; do sleep 2; done` until ready (Monitor tool is fine; expect 30–90s on cold start). If the server fails to start, STOP and surface the log tail.
4. Record server shell id + DB-state decision in the progress file so a resumed run knows what's already up.

Then delegate:

`Agent` with `subagent_type=general-purpose`, `model=haiku`. The subagent has Playwright MCP available.

Prompt skeleton:

```
You are visually verifying UC-NNN. Use Playwright MCP only — do not edit code, do not touch the dev server, do not touch the H2 database. The orchestrator has already started the server and (where applicable) wiped H2.

## Spec — Main Flow + UI/Routes
{paste those sections from the use case}

## Verification checklist — Functional + Visual
{paste the Functional and Visual sub-sections from the UC file's ## Verification section}

## Pre-flight assumptions (orchestrator has already done these)
- Dev server is running on http://localhost:8080.
- H2 state is: {fresh | preserved-from-prior-UC} — orchestrator will tell you which.
- Model endpoint reachable.

## Steps
1. Navigate to the UC's route at 1920×1080.
2. Walk the Main Flow exactly as written.
3. Screenshot: initial load, mid-flow at each interaction, and final state.
4. If the UC's UI section names mobile-specific behaviour, repeat at 390×844.
5. For each Functional and Visual checkbox, mark PASS / FAIL / BLOCKED with one line of evidence.

## Report back
- Per-checkbox pass/fail table.
- Screenshot paths.
- Blocking issues (severity: CRITICAL = breaks the flow; MAJOR = visible regression; MINOR = cosmetic).
- Do not propose fixes — that is the orchestrator's job.
```

After return:

- **All CRITICAL/MAJOR pass** → task `done` (MINOR issues recorded but not blocking).
- **Any CRITICAL/MAJOR fail** → task `failed`. Add issues. Spawn Sonnet fix subagent. Re-verify.

## Phase 2.5 — Visual Comparison (Haiku)

A **look-and-feel** comparison of the running view against the Mise design system (`ai-meal-planner/mise/design-system.md`) and — *when one exists* — the matching mockup in `ai-meal-planner/mise/*.png`. This is about **structure, color usage, hierarchy, and recurring component patterns** — not pixel matching. The data shown in the app is dynamic and will not match the mockup's data; judge the layout and the design language, not the content.

### Two modes

**Mockup + design-system (preferred):** when a mockup exists for the UC's view (`Plan-*.png`, `Shopping-*.png`, `Reports-*.png`), grade against both. The mockup provides composition; the design system provides the rules.

**Design-system only (fallback):** when no mockup exists (UC-001 onboarding chat, UC-006 detour reasoning, UC-009 insights banner, anything mid-flow), grade against the design system alone. Color usage, typography hierarchy, surface levels, recurring patterns (chat dock, tags/pills, status colors), iconography rules — all still apply. This phase always runs; it is never skipped on the basis of "no mockup."

If a mockup exists but only for one form factor (e.g. only desktop), run the comparison against the mockup for that form factor and the design system alone for the other.

### Pre-flight (orchestrator)

The same dev server and DB state from Phase 2 should still be in place. If the orchestrator restarted things between phases, re-screenshot the post-flow state by re-running Phase 2 first. Do not enter Phase 2.5 against a stale screenshot.

### Fan-out: parallel Haikus per mockup / form factor

This phase is the natural fan-out point. Visual Comparison is **read-only** — each Haiku consumes already-captured screenshots from Phase 2 plus the design-system doc and optionally one mockup PNG. There is no DB interaction, no shared mutable state, no risk of contention.

**Fan out when any of these is true:**

- The UC has mockups at multiple form factors (e.g. `Plan-desktop.png` AND `Plan-mobile.png`).
- The UC has multiple sub-views with their own mockups (e.g. UC-007 Reports has three widget mockups).
- The UC has a mockup AND a design-system-only check that warrants a focused pass (rare — usually one Haiku can hold both for a single form factor).

**Do NOT fan out when:**

- Only one mockup file (or zero) applies. One Haiku does the whole phase.
- The UC has a tiny visual surface (UC-001 onboarding chat panel — design-system-only, one pass is enough).

**How to fan out:**

1. Decide the partition. Each Haiku gets **one scope**:
   - `(mockup-path, form-factor, screenshot-paths)` for mockup+design-system mode, OR
   - `(design-system-only, form-factor, screenshot-paths)` for fallback mode.
2. Cap at **3 parallel Haikus per UC** (Anthropic guidance + Haiku rate limits + diminishing returns).
3. Send all `Agent` calls in a **single message with multiple tool uses** so they run concurrently. Do NOT await each before launching the next.
4. Wait for all to return. Merge:
   - **Per-checkbox results** — union them. A checkbox present in multiple scopes must PASS in **all** of them.
   - **Findings tables** — concatenate; deduplicate by `(finding text, severity)` only when the evidence line matches verbatim.
   - **Verdict** — phase fails if any Haiku reports CRITICAL or MAJOR; otherwise pass.
5. The Visual-Fix subagent (below) sees the merged findings table, not the individual reports.

Each Haiku's prompt is the standard skeleton below, with `## Mode`, `## Mockups for this UC`, `## Prior screenshots from Phase 2`, and `## Verification checklist` narrowed to that Haiku's scope. Tell each Haiku in its prompt: "you are one of N parallel graders; another Haiku owns {other-scope}. Stay in your scope; don't grade against mockups outside it."

### Delegate

`Agent` with `subagent_type=general-purpose`, `model=haiku`. The subagent has Playwright MCP (to retake screenshots if needed) and `Read` (for both the design-system doc and the mockup PNGs — Read renders PNGs as images for the model to see).

> **Why Phase 2 doesn't fan out.** Visual Verify *walks the flow* — chat input, DB writes, navigation between routes. Two Haikus walking the same flow in parallel would race on the shared H2 + conversation state. Run Phase 2 as one Haiku that captures screenshots at every required form factor (`browser_resize` between steps); the savings from parallelism don't pay back the contention risk. Phase 2.5 has no such constraint, hence the fan-out lives there.

Prompt skeleton:

```
You are comparing the running UI of UC-NNN against the Mise mockups. Use **Read** to open the mockup PNGs (they render as images) and the design-system doc, and **Playwright MCP** to retake / inspect the live UI if the prior screenshot didn't capture what you need.

## Mode
{either "mockup + design-system" with the matching PNG paths, or "design-system only" with a reason — e.g. "UC-001 onboarding has no mockup"}

## Mockups for this UC (if any)
{paths, or "none — design-system only"}

## Design system (always graded)
ai-meal-planner/mise/design-system.md — read this. It defines the semantic color categories (Protein / Produce / Pantry / Dairy / Other), the status colors (info, success, **edited/attention** — the recurring "AI just did this" highlight), the typography scale, the surface hierarchy (primary / secondary / tertiary), the recurring component patterns (KPI card, meal row, save-elsewhere hint, recommendation card, chat dock, toggle track, tabs), iconography (Tabler outline; sparkle reserved for AI), and the responsive rules.

Grade the running UI against these rules **regardless of whether a mockup exists**:
- Semantic colors used correctly (edited highlight = attention/amber, not arbitrary; info reserved for AI recommendations; category colors stable wherever a category appears).
- Surface hierarchy honored (page bg / containers / cards distinct).
- Typography hierarchy honored (headline numbers visibly larger; meta visibly smaller and secondary; uppercase + tracking on structural labels).
- Recurring patterns reused, not reinvented.
- Aura theme tokens drive fills/borders/radii — no ad-hoc inline hex colors.

## Prior screenshots from Phase 2
{paste the screenshot paths the visual-verify subagent saved}

## Verification checklist
{paste the "Visual comparison" sub-section from the UC file's ## Verification section}

## What to compare

You are grading the **look and feel**, not the content. The app's data is dynamic and will not match the mockup. Look at:

- **Layout & placement.** Does the page have the same structural areas in roughly the same places? (Header, side panel, chat dock at bottom, tabs at top, KPI strip position.)
- **Component patterns.** Are the recurring patterns from the design system actually being used? (Meal row with day chip + meta + tag; KPI card with uppercase label + headline number; chat dock as a single pill input.)
- **Color usage.** Are the design system's semantic colors honored? (Edited highlight in attention/amber; info color reserved for AI recommendations; category colors stable.) Don't grade specific hex values — Aura theme tokens will differ from mockup hex. Grade *which color goes where*.
- **Typography hierarchy.** Are headline numbers visibly larger than body, meta text visibly smaller and secondary, uppercase labels actually uppercase with tracking?
- **Spacing & density.** Is the page roughly as dense as the mockup, or surprisingly empty / surprisingly cramped?
- **Mobile vs desktop.** If both mockups exist, does the responsive behavior follow the design system (KPI grid 2×2 on mobile, tables become cards, side panels stack, chat dock unchanged)?

## How to score

For each Visual Comparison checkbox, mark **PASS / PARTIAL / FAIL** with one line of evidence. Group findings by severity:

- **CRITICAL** — A recurring pattern is missing or wrong (no chat dock, KPI strip absent, tabs not at top, edited highlight uses the wrong color category, ad-hoc inline color where a semantic token is required).
- **MAJOR** — A visible design-system violation that materially changes the impression (KPI cards but with no uppercase labels; cost donut using arbitrary palette instead of category colors; recommendation card without its `2px` info border; surface hierarchy collapsed to one level).
- **MINOR** — Cosmetic gaps (spacing slightly off, missing tag pill on a row, icon used isn't from Tabler set, hex value slightly off the design-system intent).

Do not fail on the data being different. Do not fail on Aura theme tokens producing slightly different hex than the mockup. Do not fail on missing affordances that haven't been implemented yet (the UC may not include them).

In **design-system-only mode**, focus on the rules that don't depend on having a mockup: color semantics, typography scale, surface levels, pattern reuse, iconography. A UC with very little UI surface (e.g. UC-001's chat panel) will have correspondingly fewer checks — that's fine; thoroughness scales with surface area.

## Report back

- Per-checkbox PASS/PARTIAL/FAIL with evidence.
- Three lists: CRITICAL findings, MAJOR findings, MINOR findings.
- Screenshot vs mockup pairings you actually looked at.
- One-line overall verdict: "look-and-feel parity acceptable" / "look-and-feel needs fixes (see CRITICAL/MAJOR)".
- Do not propose code fixes — that is the orchestrator's job. (You may note which design-system section governs each finding so the fix subagent has a starting point.)
```

After return:

- **No CRITICAL or MAJOR** → task `done` (MINORs recorded).
- **Any CRITICAL or MAJOR** → task `failed`. Add issues. Spawn the **Visual-Fix subagent** (separate flow — see below). Re-run Phase 2 *and* Phase 2.5 after the fix because the fix changes styles or layout that Phase 2 grades too.

### Visual-Fix subagent (Sonnet)

Unlike the Functional-Fix subagent in the Phase 2/3 path, this one **owns the dev server during its run**. Styling changes need to be iterated against the live browser — restart, screenshot, compare, adjust. The orchestrator hands control of the running server to this subagent for the duration of the fix.

`Agent` with `subagent_type=general-purpose`, `model=sonnet`.

Prompt skeleton:

```
You are fixing visual / styling issues found while comparing UC-NNN against the Mise mockups. You **own the dev server** for this run: stop, restart, screenshot, iterate. The orchestrator will reclaim it when you return.

## Findings to fix
| Finding | Severity | Design-system rule | Evidence |
|---|---|---|---|
{paste the CRITICAL/MAJOR rows from the Issues table}

## Resources
- ai-meal-planner/mise/design-system.md — the rules you're conforming to.
- ai-meal-planner/mise/*.png — visual reference. Look-and-feel target, not pixel target.
- spec/architecture.md §3 — package layout (UI lives under `com.example.mise.ui.*`).
- For Vaadin Aura styling, **use the `vaadin-claude:aura-theme` skill** to generate / adjust CSS custom properties and theme settings. For broader theming guidance (Lumo vs Aura, component variants, utility classes) use `vaadin-claude:theming`.

## Server control
You can run any of these — restart whenever you have a code change to load:
- `pkill -f com.example.mise.Application; sleep 3` to stop the running JVM.
- `./mvnw -q` in the background (use Bash `run_in_background: true`) to start it.
- `until curl -sf http://localhost:8080/ -o /dev/null; do sleep 2; done` to wait for ready.
- **Do not wipe `data/`** unless a finding specifically requires the fresh-boot view — onboarding state is currently populated and most UCs need it.

You also have Playwright MCP — use it to screenshot the live UI after each iteration and visually confirm the fix.

## Constraints
- Fix CRITICAL first, then MAJOR. Leave MINOR for follow-up unless trivial.
- Stay inside the existing theming approach (Aura tokens, application-level CSS custom properties in `META-INF/resources/styles.css`). Do not introduce a competing theme.
- Application-level custom properties for Mise-specific category colors should follow the convention `--mise-category-{name}` per design-system.md §"Implementation note".
- Do not modify `pom.xml`, `vite.config.ts`, `spec/architecture.md`, or anything under `spec/` / `ai-meal-planner/`.
- After each fix iteration: `./mvnw compile -q` must exit 0; existing tests must still pass.

## Report back
- One row per finding: what you changed, which file(s), one-line evidence screenshot path.
- Final per-finding status: FIXED / PARTIAL / DEFERRED (with reason).
- Compile + test exit codes.
- State of the dev server when you return: running on :8080 or stopped (the orchestrator will pick up from here).
```

After Visual-Fix returns:

1. Read the server state line; reclaim ownership (if stopped, start it; if running, leave it).
2. Set Phase 2 (Visual Verify) and Phase 2.5 (Visual Comparison) both back to `pending` — visual fixes can introduce functional regressions and the styling check needs a fresh comparison.
3. The orchestrator loop will re-run them in order.

## Phase 3 — AI Verify (Sonnet)

**Skip** this task (mark `done` with note "no AI block in UC ## Verification") if the UC file's `## Verification` section has no `#### AI` block.

`Agent` with `subagent_type=general-purpose`, `model=sonnet`. Sonnet is required here because grounding judgment (is the assistant fabricating a price? does the reasoning cite a real `MealEdit.reason`?) is the work.

Prompt skeleton:

```
You are verifying AI behaviour for UC-NNN. Use Playwright MCP for UI interaction and Read for cross-checking H2 data via the JPA layer's known seed YAML or recipe files.

## UC AI checklist
{paste the #### AI block from the UC file's ## Verification section}

## Global AI checks (from spec/verification.md §2)
- Conversation persistence across JVM restart.
- No fabrication: any cited price, kcal, or quantity must match the underlying recipe or stores/*.yaml.
- Latency: single-meal edit ≤ 2s; negotiation ≤ 5s. Note the model in use; record actual numbers.

## Method
- Drive the chat from Playwright; capture the assistant message text.
- For each numeric claim, locate the source YAML/recipe and compare.
- For the restart check: do the pre-restart half (send the message, capture state), then return with status `awaiting_restart`. The orchestrator will stop and restart the JVM and re-invoke you with `phase=post_restart` so you can verify history reloaded. Do not stop the JVM yourself.

## Report back
- Per-check pass/fail with evidence (assistant text, source value).
- Latency numbers measured.
- Any fabrication caught.
- If you stopped at `awaiting_restart`, list exactly what state you captured pre-restart so the post-restart pass can compare.
```

**Persistence-restart handling.** When Phase 3 returns `awaiting_restart`:

1. Kill the dev-server background shell.
2. **Do not wipe `data/`** — the point of the check is that state survives.
3. Restart `./mvnw -q` in the background; poll until ready.
4. Re-delegate Phase 3 with `phase=post_restart` and the prior subagent's pre-restart capture pasted in.

## Fix delegation

When Phase 1.5 (view-bug), Phase 2, or Phase 3 fails:

```
Agent: subagent_type=general-purpose, model=sonnet
```

Prompt skeleton:

```
You are fixing issues found while verifying UC-NNN. Implement only the fixes listed below.

## Issues
| Issue | Severity | Evidence |
|-------|----------|----------|
{paste the Issues table rows for this iteration}

## Original spec (for context only)
{paste the use case}

## Constraints
- Fix CRITICAL first, then MAJOR. Leave MINOR for follow-up unless trivial.
- After each fix: `./mvnw compile`, then `./mvnw test`, then (if any view changed) `./mvnw -Pit verify -Dit.test=<View>IT`. Report results.
- Do not refactor unrelated code.

## Report back
- What was fixed, per issue.
- Files modified.
- Compile + test + IT status.
```

After fix returns, set the failed verify task back to `pending` and let the loop re-run it (which re-runs the relevant phase, and the IT layer too if the phase preceding it is Phase 1.5).

## Stopping conditions (report to user, do not push through)

| Situation | Action |
|-----------|--------|
| Spec file or verification block missing | STOP, name what's missing |
| Required prior UC not implemented | STOP, list the gap |
| Compile or unit-test fail after Sonnet fix attempt | STOP, paste failing output |
| 3 fix iterations on the same task without pass | STOP, paste iteration log |
| Model endpoint unreachable and UC has AI block | STOP, suggest the user fix `MISE_MODEL_BASE_URL` |
| Dev server fails to come up after `./mvnw -q` (build error, port in use, hang past ~3 min) | STOP, paste the last ~50 lines of the server log |
| H2 wipe needed but `./data/` write fails (permissions, lock held) | STOP, surface the OS error |

## Final summary (per UC)

When a UC's progress file reaches all `done`:

```markdown
## UC-NNN summary

**Status:** COMPLETED
**Iterations:** Impl ×N, Verify ×N, Compare ×N, AI ×N
**Files touched:** {dedup across iterations}
**Test counts:** unit {n} pass, Playwright walkthrough {n} steps verified
**Open MINOR issues:** {list, may become follow-up tasks}
**Progress file:** docs/progress/uc_NNN_progress.md
```

When all input UCs are done, emit one combined summary table and stop.
