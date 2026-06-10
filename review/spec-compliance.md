# Spec Compliance — UC-001 .. UC-012

Status summary (confidence: high unless noted):

| UC | Title | Status | Notable drift |
|----|-------|--------|---------------|
| 001 | Onboarding | ✅ Implemented | Turn limit is prompt-enforced only |
| 002 | View current plan | ✅ Implemented | — |
| 003 | Edit meals via chat | ✅ Implemented | — |
| 004 | Undo & explain | ✅ Implemented | — |
| 005 | Shopping list | ✅ Implemented | — |
| 006 | Detour reasoning | ✅ Implemented | Headline demo moment untested |
| 007 | Reports & transforms | ⚠️ Partial | Spec promises AI controllers; code uses custom tools |
| 008 | Cross-view chat | ✅ Implemented | — |
| 009 | Insights | ✅ Implemented | (earlier placement finding retracted — see below) |
| 010 | Week navigation | ⚠️ Bug found live | Day-name resolution ignores the viewed week (BR-06) |
| 011 | Generate future weeks | ❌ Spec only | Expected — spec committed `821497f`, no impl |
| 012 | Dynamic report widgets | ❌ Spec only | Expected — spec committed `efb1532`, no impl |

UC-011/012 being unimplemented is fine — both are recent spec-only commits and the user knows they're pending. The actionable items below are about the *implemented* UCs.

---

## UC-001 — Onboarding

Implemented end-to-end: `OnboardingView` + `OnboardingTools.recordHousehold` + persona merge + 4 seeded historical plans (BR-06).

- **BR-04 (≤3 turns) is prompt-enforced only.** The system prompt in `OnboardingView.java:40-55` instructs the model; nothing in code prevents a 4th turn or forces `recordHousehold`. Probably acceptable for a demo, but the spec phrases it as a rule, not a hope. Either soften the BR to "the prompt instructs…" or add a turn counter.
- **Test gap:** no test exercises BR-03 (clarifying follow-ups when fields are missing) or the turn limit. An AIIT that feeds a deliberately vague opener would cover both.

## UC-002 — View current plan

Implemented; no drift found. `MealGrid` handles the empty-slot placeholder (BR-02) and the 60-second "edited" pill window (BR-04, `MealGrid.java:98-105`).

- **Test gap:** nothing asserts BR-01 (exactly one ACTIVE plan) at the service level, nor BR-03 (weekly stats recompute after a meal change). Both are cheap unit tests on `PlanService`/`ReportService`.

## UC-003 — Edit via natural language

Implemented. `PlanTools.swapMealOnDay` / `negotiateWeekChanges` and `PlanService.swapMeal` enforce allergy hard constraints, pin checks (`PinnedMealException`), reason recording; `negotiateWeek` is `@Transactional` for atomic multi-change rollback.

- **Test gap:** BR-07 (infeasible request → best-effort proposal + explanation) has no AIIT. This is one of the more demo-visible behaviors; worth a test that asks for something impossible ("all meals free and under 5 minutes").

## UC-004 — Undo & explain

Implemented. Undo writes a new `MealEdit` row rather than deleting history (BR-03) — nice. `explainEdit` grounds answers in recorded reasons, and the AIIT (`PlanToolsAIIT.java:29-49`) verifies the model does **not** fabricate a reason when `MealEdit.reason` is null. That no-fabrication test is exactly the right kind of AI test.

- **Test gap:** BR-05 (clarify when asked about a non-most-recent change) and BR-06 (explanation length bounds) untested.

## UC-005 — Shopping list

Implemented: consolidation by unit (BR-02), staple subtraction (BR-03), non-staple needs explicit action (BR-04), store-mode toggle persisted via `ViewPreference` (BR-05), session-local check-offs (BR-07).

- **Test gap:** BR-05's *persistence across reload* isn't exercised end-to-end (only via service unit tests), and BR-06 (cheapest-alternative hints in one-store mode) has no test.

## UC-006 — Detour reasoning

Implemented: `DetourEvaluator` grounds verdicts in real `StoreItem` prices + `detourMinutesFromRoute` (BR-01), uses the €0.50/min time-value heuristic (BR-02), and never auto-switches the recommended store (BR-03). `DetourToolsAIIT` checks both grounding and the no-auto-apply rule.

- **The headline teaching moment (BR-06) is unverified anywhere:** edit a seed YAML price → restart → the verdict flips. The spec calls this a teaching requirement; it appears in no test and no manual checklist. At minimum, add it to a pre-demo manual checklist in `verification.md`; better, a unit test that constructs two `PriceCatalog` states and asserts the `DetourVerdict` flips (no restart needed to test the logic).
- BR-05 (what-changed mini-report after a detour-driven swap) untested.

## UC-007 — Reports & transforms

**Partial, with real spec↔code contradiction.** The user-visible behavior exists: leaderboard column add/remove, category chart bar↔donut, reset, persistence via `ViewPreference`. But:

- **BR-02 and BR-07 describe a different architecture than what shipped.** BR-02: transforms "bounded by what `GridAIController` / `ChartAIController` expose". BR-07: widgets connect via controllers, detached on `BeforeLeaveEvent`. Implementation: custom `@Tool` methods in `ReportsTools.java` mutating `ViewPreference` + a refresh broadcaster; zero controller usage. See [`ai-integration.md`](ai-integration.md) for the recommendation — this is the most consequential finding in the review.
- **Test gaps:** BR-03 refusal of non-derivable columns ("carbon footprint") untested; BR-06 (no fabricated historical prices) should be asserted in `ReportsToolsAIIT`; no test for the transient "edited" highlight.

## UC-008 — Cross-view chat

Implemented faithfully: one orchestrator per UI with history from `ConversationService` (BR-01), view-scoped tool guidance via `HouseholdOrchestrator.setCurrentView` (BR-02), `viewContext` stamping (BR-03), `NavigationTools.goToView` with a hard-coded view map — navigate-then-act in one turn (BR-04/05).

- **Minor:** BR-09 scroll-to-latest relies on `transitionend` firing (`MainLayout.java:409-417`) with no fallback. Cosmetic risk only.
- **Test gaps:** BR-06 rolling-window history and BR-07 message queueing while busy have no tests.

## UC-009 — Insights

**Implemented — earlier "placement drift" finding retracted after runtime verification.** Generation, dismissal, muting, "insights I missed", evidence refs, one-undismissed-at-a-time are all implemented and unit-tested (`InsightService`, `InsightTools`, `InsightStartupTrigger`).

- **Placement is per spec.** Plan renders the cross-view insight at the bottom of the cost-by-category sidebar with "Act on it" + dismiss (`CostByCategoryPanel.java:80-140`); Reports renders its quiet non-dismissable block (`ReportsView.java:191-244`); the `MainLayout` banner (`MainLayout.java:199-206`) is shown **only on Shopping**, which the spec explicitly sanctions as a temporary legacy arrangement ("hidden on /plan and /reports because those views now handle insights themselves"). Verified live: the sidebar callout with the pill renders on /plan; no top banner appears. The first review draft missed `CostByCategoryPanel` — retracted.
- **Follow-up worth tracking:** the spec's "until Shopping grows its own in-panel insights area" is an open TODO with no UC item; consider a small ticket so the legacy banner doesn't live forever.
- **Test gaps (still valid):** BR-04 triggers (a) startup-after-7-days and (b) plan-finalized are untested; BR-06 (insight phrased as a question) and the "Act on it" flow untested.

## UC-010 — Week navigation

Implemented across all BRs (boundary disabling, `?week=` param survival, Monday snapping, pre-onboarding fallback) — **but a live round-trip exposed a BR-06 bug.**

- **Bug (confirmed live 2026-06-10):** asked *"What's for dinner on Friday?"* while viewing the week of May 18 with Grilled Chicken Caesar Salad visibly planned for Friday, the assistant replied *"There's no meal planned for Friday yet."* Root cause: `PlanTools.getActivePlan()` resolves the **viewed** plan per BR-06, but `PlanTools.resolveDate()` maps day names against `LocalDate.now()`'s real-world week — "friday" → Jun 12, which falls outside the viewed plan's May 18–24 window, so the meal lookup misses. The javadoc on `getActivePlan` ("'what's on Friday?' answers relative to the viewed week") promises exactly the behavior `resolveDate` breaks. Fix: resolve day names against the viewed plan's Monday (and decide what "today"/"tomorrow" mean when viewing a non-current week — probably refuse or clarify). `WeekNavigationAIIT` passes because its scenario happens to view the current week; add a case that views a past week and asks a day-name question.
- Contributing demo-confusion factor: the ACTIVE plan is stale (week of May 18 on Jun 10) because nothing rolls plans over — that's UC-011 BR-06 territory, but until it lands, every fresh demo starts on a weeks-old "active" week.
- **Test gap:** the keyboard-navigation acceptance criterion (tab order prev → pill → next) has no test.

## UC-011 / UC-012 — not implemented (expected)

Spec-only. For UC-011 note the foundational dependency: `Plan.Status` has no `PLANNED` value yet (`Plan.java:14`), and `PlanService.findActivePlan` has no rollover-promotion sweep — start there. For UC-012 the entire stack is absent (`DatabaseProvider` impl, reporting-schema tables, `ReportSnapshotService`, controller wiring, system-prompt schema augmentation). UC-012 is also the natural place to *retroactively heal* UC-007's controller drift — see [`ai-integration.md`](ai-integration.md).

---

## Cross-cutting test observations

- The three-layer test strategy (unit / Browserless+IT / AIIT) is working and the AIIT no-fabrication checks are the most valuable AI tests in the suite. Keep writing those.
- The recurring gap pattern: **BRs about model behavior at the margins** (turn limits, clarification rules, length bounds, refusals) are specified but untested. Each is one short AIIT.
- `verification.md` methodology vs. reality: several per-UC verification checklist items (UC-006 BR-06 seed-edit flip, UC-009 placement, UC-010 keyboard) are checked in no automated or documented-manual process. Suggest adding a "manual pre-demo checklist" section so unautomated items are at least enumerated.
