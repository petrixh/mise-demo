# Follow-ups

Small items that surfaced during UC implementation but don't fit cleanly inside a single UC's scope. Keep this list short — graduate items to real UCs or commits when they're picked up.

## App-shell polish (header)

Surfaced during UC-003 smoke test (post-implementation). The mockups at `ai-meal-planner/mise/Plan-{desktop,mobile}.png` show a richer header than the current minimal one in `MainLayout.buildHeader()`. Gaps:

- **Brand cutlery icon** next to "Mise" wordmark. Tabler or equivalent.
- **Week selector** — chevron-prefixed "Week of {date}" that opens a popover/dropdown to navigate to prior/next weeks. Currently a static `Span` badge. Implies a real prev/next-plan navigation feature: `PlanService.findActivePlan` would need a sibling `findPlanByWeek(householdId, weekStartDate)` and the view would route by query param. UC-007 needs week navigation in the Reports view; consider bundling the underlying `PlanService` API change with that UC.
- **Avatar / household identity** circle on the right side. Could open a popover for household settings (currently no settings surface — would need a small dialog or a `/settings` route). Plausibly bundles with a future "household settings" UC.
- **Desktop tabs inline with header**, not on a separate row (mobile keeps the separate row). Purely CSS / DOM restructuring inside `MainLayout`.

Not blocking any UC. Pick up when a related UC lands (week selector with UC-007; avatar with whatever first introduces settings) or as a standalone "app-shell polish" pass.

## Dark-theme typography & surface contrast

Surfaced during UC-003 Phase 2.5 desktop visual comparison. The KPI headline numbers, body text, secondary text, and labels all render at perceptually similar weights in the current dark theme — the *underlying CSS scale is correct* per `design-system.md` (10/11/14/18 px), but contrast and weight differentiation are flattened by the dark Aura tokens.

Likely fix area: `mise-dark.css` and Aura dark surface tokens (`--vaadin-background`, `--vaadin-background-container`, `--vaadin-body-text-color`, secondary text color). Bumping headline weight and lifting card surface contrast would resolve most of it.

Affects all views, not just Plan. Defer to a theme-polish pass.

## Chat history surface

The chat-history region in `.mise-chat-dock` currently uses `:focus-within` to expand 0 → 240px as an MVP stand-in. The spec calls for a drawer (desktop) / popover (mobile). Acceptable to defer; the prep handover doc (`/home/node/.claude/plans/so-the-current-testing-curried-stream.md`) flags this. Likely lands with UC-005 or UC-007.

## Cross-view chat — route-aware ViewContext

`HouseholdOrchestrator.onResponseComplete` currently hardcodes `ViewContext.PLAN` (see the `TODO (UC-008)` comment). When UC-008 (cross-view chat) is implemented, replace with a route-aware lookup so messages stamped from Shopping / Reports get the right view context.
