# UC-007: Reports — default views & AI-driven transforms

> Maps to demo **Scenario 5 (Chart transform)** and the "add a derived column" user story.
> The transform mechanics are natural-language-driven via the Vaadin AI controllers — see UC-012 for the schema/controller plumbing. This UC owns the **default** Reports experience and the user-visible transform behaviors.

---

**As a** home cook, **I want to** see standard cost and nutrition trends over multiple weeks and reshape them in natural language **so that** the reports answer the questions I actually have without me building dashboards.

**Status:** Implemented
**Date:** 2026-05-13 (transforms reworked onto controllers 2026-06-10)

---

## Main Flow

### Default
- I navigate to `/reports`.
- I see one **Reports panel** (single-panel pattern, per the design system) containing, top to bottom: a 4-cell KPI strip; a chart row with a **weekly cost trend** line chart and a **cost-by-category** chart (donut by default) side by side; a **per-meal leaderboard** grid (meal name, times cooked, average cost); and a non-dismissable AI insight callout beneath the leaderboard.
- The three widgets are controller-driven (UC-012) with **default queries** baked into the app; data spans my seeded + accumulated plan history.

### Ask "why?"
- I ask: *"Why was last week cheaper than usual?"*
- The assistant writes a `SELECT` against the reporting schema (`queryReportingData`), and grounds its answer in the rows returned: which meals were unusually cheap, which categories were absent. No fixed analysis routine — the model decides what to query.

### Add a derived column
- I ask: *"Add a kcal-per-euro column to the leaderboard."*
- The assistant rewrites the leaderboard's SQL (via `GridAIController`'s `update_grid_data` tool) to include the derived column; the grid re-renders with it and the widget is highlighted briefly.
- The new query is recorded in `ViewPreference (view = REPORTS, widgetKey = 'leaderboard', settings = {query: '<the SELECT>'})` and survives reload and JVM restart.

### Transform a chart
- I ask: *"Show the category breakdown as a horizontal bar instead of a donut."*
- The assistant reconfigures the chart (via `ChartAIController`'s `update_chart_configuration` tool); the change is recorded in `ViewPreference (view = REPORTS, widgetKey = 'categoryChart', settings = {query, controllerStateB64})`.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | Default reports are computed from all `Plan` rows where `status` is `ACTIVE` **or** `HISTORICAL`. The current week is included. `PLANNED` plans (UC-011) are **excluded** from default reports — they are projections, not history. |
| BR-02 | There are **no fixed transform tools**. The assistant reshapes widgets exclusively through the controller tools (`update_grid_data`, `update_chart_data_source`, `update_chart_configuration`) with SQL it writes against the curated reporting schema (UC-012 BR-02/BR-03). Anything expressible as a SELECT over that schema is a valid transform; nothing else is. |
| BR-03 | If the user asks for data the schema cannot derive (e.g., "carbon footprint"), the assistant explicitly says so and offers the closest real proxy (cost or kcal intensity). No fabricated columns or values. |
| BR-04 | Widget transforms persist as `ViewPreference` rows keyed by `(householdId, view, widgetKey)`. Reopening Reports shows the transformed widget, not the default. |
| BR-05 | A "reset to defaults" affordance exists per widget and in chat (*"reset the leaderboard"* → `resetReportsWidget`). Reset removes the corresponding `ViewPreference`; the widget falls back to its built-in default query. |
| BR-06 | "Why?" answers about a past week reference concrete meals and amounts returned by `queryReportingData` — costs come from the current price catalog (no historical price snapshots). The assistant must not fabricate values that aren't in the query result. |
| BR-07 | The chart/grid controllers are registered on the per-UI `AIOrchestrator` **at build time** alongside the tool beans, and scoped to `/reports` by the system prompt — the same UC-008 pattern as every other view's tools. (Runtime attach/detach is not possible: `AIOrchestrator.reconnect` exists only for the post-deserialization path.) A welcome side effect: *"go to reports and add a kcal column"* works in a single turn from any view. |

---

## Acceptance Criteria

- [ ] After onboarding, `/reports` shows three widgets populated with at least 4 weeks of data.
- [ ] *"Add a kcal-per-euro column"* causes a new column to appear in the leaderboard with values derived from the schema's `kcal_per_serving` / `est_cost_eur`.
- [ ] After the column is added, reloading the page (or restarting the app) shows the leaderboard still containing the column.
- [ ] *"Show the category breakdown as a horizontal bar"* transforms the donut into a bar chart of the same data.
- [ ] Asking *"why was last week cheaper?"* names ≥ 1 specific meal or category from last week's plan and references real values.
- [ ] *"Reset the leaderboard"* removes the customization.
- [ ] A request for a column requiring data we don't have (e.g., "carbon footprint per meal") returns a refusal with reasoning, not a fabricated column.

---

## UI / Routes

- One single-panel layout (per the design system's "View panel" section): KPI strip → chart row (`1fr 1fr` on desktop, stacked below 1024px) → leaderboard → AI insight, all hairline-separated inside one filled `.mise-reports-panel`. No per-widget cards or gaps.
- Chart canvas + plot area are transparent so the panel background reads through; the default queries supply `--mise-category-*` colors per data point via the converter's `_color` column. AI-restyled charts may use the model's own styling choices.
- "Edited" highlighting on freshly transformed widgets fades over a few seconds (inset shadow).
- Reset-to-defaults: a small icon button at the right of each widget's title row plus the in-chat `resetReportsWidget` tool.
- The leaderboard is one dynamic Grid at every width (columns are query-driven); on mobile it scrolls horizontally inside the panel.

| Route | Access | Notes |
|-------|--------|-------|
| `/reports` | public | `@Route("reports")`. |

---

## Verification

**Verified by:** Claude (live LLM round-trip + unit/IT suites)
**Date:** 2026-06-10

#### Functional

- [x] Post-onboarding `/reports` shows ≥ 4 weeks of data in three default widgets (`ReportsViewIT`)
- [x] *"rank the meals by kcal per euro"* reshapes the leaderboard via `update_grid_data`; persisted via `ViewPreference`; survives reload (BR-04, live round-trip 2026-06-10)
- [ ] *"Show as horizontal bar"* transforms donut → bar; persisted; survives reload
- [x] Persisted custom query restores into the grid on load; reset reverts to the default (`ReportsViewIT`)
- [x] Request for non-derivable column (e.g. carbon footprint) → explicit refusal (BR-03, `ReportingToolsAIIT`)
- [x] Controllers registered at orchestrator build; prompt-scoped to /reports (BR-07)

#### Visual

- [ ] One single-panel Reports view with KPI strip, chart row, leaderboard, and in-panel AI insight, separated by hairlines
- [ ] Default donut renders a vertical right-side legend with category colors
- [ ] Transform highlight fades over a few seconds

#### AI

- [x] "Why was last week cheaper?" names ≥ 1 concrete meal/category from that week (`ReportingToolsAIIT`)
- [ ] No fabricated historical prices

#### Result

- **Status:** Pass — re-verified after the controller rework (remaining unchecked visual items unchanged from prior UC-007 sign-off)
- **Notes:** transforms moved from bespoke `@Tool`s to `ChartAIController`/`GridAIController` (UC-012); unchecked items above are pending the live round-trip / AIIT run.
