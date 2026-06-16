# Code Quality — simple, easy to review, compact without becoming cryptic?

Verdict first: **yes, mostly.** The code errs on the side of explicit over clever, which is right for a demo people read to learn a pattern. View classes are large but linear; domain services are small; there is exactly one "clever" spot in the codebase (the MutationObserver JS in `MainLayout`) and it's justified and commented. The conventions in CLAUDE.md are actually followed: zero inline-style violations found, one CSS file per view, selectors properly prefixed.

The weaknesses are not cryptic code — they're **siblings that drifted apart**. Three copies of the same broadcaster, six tool classes with three error-string styles, two null-handling idioms. In a teaching codebase, inconsistency between parallel structures reads as intentional signal ("why is this one different?") when it's actually accident. That's the theme of most findings below.

A note on the suggestions: this is a demo, so the fix for inconsistency is *pick the simplest variant and apply it everywhere* — not introduce formatter classes, builders, or output-format enums. Several "improvements" considered during this review were rejected for adding more machinery than they remove.

---

## High impact

### 1. Triplicated `*RefreshBroadcaster`
`ui/plan/PlanRefreshBroadcaster.java`, `ui/shopping/ShoppingRefreshBroadcaster.java`, `ui/reports/ReportsRefreshBroadcaster.java` — three structurally identical ~25-line classes (CopyOnWriteArrayList of `Runnable`, register/deregister/fireRefresh), already drifting: the Reports one has a one-line javadoc vs. the others' full threading-model docs, and names its parameter `hook` vs. `refreshHook`.

**Suggestion:** one `ViewRefreshBroadcaster` class, three named Spring beans (or a single broadcaster keyed by view enum). `MainLayout.java:156-161` already fires all three together after every AI turn, which hints they want to be one thing. Saves ~50 lines and removes a "spot the difference" trap.

### 2. Single-implementation interfaces in `capabilities/`
`RecipeCatalog`/`FilesystemRecipeCatalog`, `PriceCatalog`/`StubbedPriceCatalog`, `PersonaCatalog`/`FilesystemPersonaCatalog` — three interface/impl pairs, each with one implementation.

This one is a judgment call, not a defect: project-context §5 explicitly promises "capabilities sit behind interfaces with file-backed stub implementations… replaceable without touching the orchestrator or UI", so the seams are *specified*. But the cost is real for a reader: two files per concept, and the interface adds no information the class doesn't. Options, in order of preference:

- Keep the interfaces but make the teaching intent visible: one javadoc line on each interface saying *why* the seam exists ("replace with a live price feed without touching tools/UI — see project-context §5").
- Or collapse them and amend project-context. `MealCostCalculator`/`LiveMealCostCalculator` is the pair most worth keeping either way (a real alternative implementation is plausible in tests).

Decide deliberately; the current state looks like ceremony unless you know the spec paragraph.

### 3. Long multi-concern tool methods in `PlanTools`
`PlanTools.java` is ~620 lines with a few methods doing several jobs: `negotiateWeekChanges` (~70 lines: preflight pins, apply, format), `findCandidateRecipes` (~40 lines: five chained filters + inline formatting). The logic is correct and tested, but a reviewer holds too much at once.

**Suggestion:** extract named private predicates/helpers (`isAllergySafe(recipe)`, `formatCandidates(list)`) so each `@Tool` method reads as: validate → act → format. No new classes needed. If the file keeps growing with UC-011 (`planFutureWeeks` will land here), consider splitting query tools from mutation tools then — not before.

## Medium impact

### 4. Inconsistent error/refusal strings across tool classes
Tool methods return prose the LLM must interpret; across the six tool classes the failure shapes differ ("Could not…", "REFUSED:…", plain explanations). The model copes, but a newcomer adding a seventh tool has no pattern to copy.

**Suggestion:** don't build a formatter class — just pick one convention (e.g. refusals start `"Refused: <reason>"`, errors start `"Error: <context>"`), state it in a 3-line comment in one tool class (or the package-info), and align the existing strings. Half a day, mostly mechanical.

### 5. Inconsistent null handling
`PlanTools` mixes `orElse(null)` + if-checks with `Optional.map().orElse(...)` chains; `InsightTools` is consistently functional. **Suggestion:** standardize on the simpler explicit style for multi-step logic and `map/orElse` for one-liners — and apply it uniformly. (Direction matters less than uniformity.)

### 6. `MainLayout` constructor and `updateWeekNav`
`MainLayout.java` is 734 lines; the constructor (~110 lines) mixes chat-dock wiring, shell layout, insight banner, and week nav; `updateWeekNav()` is ~72 lines of fallbacks + badge styling + button state. Both are readable top-to-bottom but hard to navigate. **Suggestion:** extract `buildChatDock()`, `buildShell()`, `updateWeekBadge()` private methods — pure moves, no behavior change. This is the file every reader opens first (it's where the orchestrator lives), so it pays the most rent.

### 7. `ReportsView.loadAndRender()` and chart-building duplication
`ReportsView.java:150-197` resolves household, fetches three datasets, and builds three widgets in one method; `CostByCategoryPanel.java:225-298` builds a ~74-line chart config that overlaps with `ReportsView`'s chart builders. `MiseChart` already exists as the shared abstraction — push more of the common config (margins, axis defaults, category colors) into it rather than growing parallel configs. (If UC-012's `ChartAIController` lands, much of this code is replaced anyway — cheap fix now, or fold into that work.)

### 8. Date-resolution logic lives only in `PlanTools`
`PlanTools.resolveDate()` (~30 lines: day names, today/tomorrow, ISO) is useful to any tool that takes a date-ish string; `ShoppingTools` works around it. When a second consumer appears, move it to a small shared helper (e.g. `ai/tools/ToolDates`). Flagging now so it's a known item, not urging a pre-emptive move.

## Low impact

- **`mise-reports.css:182`** — `--lumo-base-color: transparent;` under Aura. Per the project's own sharp-edges notes, Lumo tokens don't resolve; the line above already sets `--vaadin-grid-background-color`. Delete the Lumo line (or comment why it's intentionally kept for grid-internal styles, if it is).
- **Silent catch in `MainLayout.buildWeekLabel()`** (~line 302) — falls back to "Week of [today]" with no log. Add a `log.warn` so a broken week resolution isn't invisible.
- **Magic heuristics lack rationale comments:** `DetourEvaluator.java:36` (€0.50/min time value — this one *is* a spec'd BR, say so in a comment), `ShoppingTools.java:30` (€0.50 cheapest-alternative threshold), `InsightTools` list cap. One sentence each.
- **Nested ternary in `ReportsTools.java:153-155`** (chart-type description) — a small `switch` reads better.
- **`ShoppingView.java:369-376`** — nested stream/reduce for aisle price sums; a tiny `sumAislePrice(items)` helper or plain loop would read faster.

## Done well (keep doing these)

- **`MealEdit` audit trail.** Every change writes who/what/why/when; undo is a forward-written row, not a delete. This single design choice powers UC-004's "why?" and the no-fabrication AIIT — it's the best teaching moment in the codebase.
- **`MiseChart.solidColor()`** — defensive factory with a comment explaining the JSON-serialization bug it prevents. Exactly the right ratio of cleverness to documentation.
- **CSS discipline.** The one-file-per-view + `@import` roster convention is followed to the letter; no inline styles anywhere; `--mise-*` tokens used consistently.
- **Spec traceability in code.** Comments like `// UC-009: …` and `R-F-01:` markers make the spec↔code mapping greppable in both directions. (Ironically this is how the UC-009 placement drift was easy to find — keep the markers.)
- **Test helpers in `LiveMealCostCalculatorTest`** (`meal()`, `recipeWith()`, `ing()`) keep 40+ cases readable; a good template for future test classes.
