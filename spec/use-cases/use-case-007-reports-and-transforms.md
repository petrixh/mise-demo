# UC-007: Reports — default views & AI-driven transforms

> Maps to demo **Scenario 5 (Chart transform)** and the "add a derived column" user story.

---

**As a** home cook, **I want to** see standard cost and nutrition trends over multiple weeks and reshape them in natural language **so that** the reports answer the questions I actually have without me building dashboards.

**Status:** Draft
**Date:** 2026-05-13

---

## Main Flow

### Default
- I navigate to `/reports`.
- I see one **Reports panel** (single-panel pattern, per the design system) containing, top to bottom: a 4-cell KPI strip; a chart row with a **weekly cost trend** line chart and a **cost-by-category** chart (donut by default) side by side; a **per-meal leaderboard** grid (rank, meal name, frequency, average cost, average kcal); and a non-dismissable AI insight callout beneath the leaderboard.
- Data spans my seeded + accumulated plan history.

### Ask "why?"
- I ask: *"Why was last week cheaper than usual?"*
- The assistant grounds its answer in concrete meals from the prior plan: which were unusually cheap, which categories were absent, whether any sales coincided.

### Add a derived column
- I ask: *"Add a kcal-per-euro column to the leaderboard."*
- The grid (driven by `GridAIController`) gains a new column. The new column is highlighted briefly.
- The change is recorded in `ViewPreference (view = REPORTS, widgetKey = 'leaderboard', settings = {extraColumns: ['kcalPerEuro']})` and survives reload.

### Transform a chart
- I ask: *"Show the category breakdown as a horizontal bar instead of a donut."*
- The chart (driven by `ChartAIController`) reshapes in place. The transform is recorded in `ViewPreference (view = REPORTS, widgetKey = 'categoryBreakdown', settings = {chartType: 'bar', orientation: 'horizontal'})`.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | Default reports are computed from all `Plan` rows where `status` is `ACTIVE` **or** `HISTORICAL`. The current week is included. `PLANNED` plans (UC-011) are **excluded** from default reports — they are projections, not history. |
| BR-02 | The set of "transforms" the assistant can apply per widget is bounded by what `GridAIController` / `ChartAIController` expose. The orchestrator's system prompt for the Reports view enumerates these. (The **data-query surface** that drives those transforms — i.e., what data the AI can pull into a widget — is bounded separately by the curated reporting schema; see UC-012 BR-02 / BR-03.) |
| BR-03 | Adding a derived column requires the underlying values to be derivable from existing data (meal cost, kcal, etc.). If the user asks for a column that cannot be derived (e.g., "carbon footprint"), the assistant explicitly says so. |
| BR-04 | View transforms persist as `ViewPreference` rows keyed by `(householdId, view, widgetKey)`. Reopening Reports shows the transformed widget, not the default. |
| BR-05 | A "reset to defaults" affordance exists per widget and in chat ("reset the leaderboard"). Reset removes the corresponding `ViewPreference`. |
| BR-06 | "Why?" answers about a past week reference concrete meals and prices from that plan's `Meal` rows and the price catalog **as it was at the time** — but for the demo, this just means the current catalog (no historical price snapshots). The assistant must not fabricate historical prices it doesn't have. |
| BR-07 | Reports widgets connect to the shared `AIOrchestrator` via `GridAIController`/`ChartAIController` only while the view is active; controllers are detached on `BeforeLeaveEvent`. |

---

## Acceptance Criteria

- [ ] After onboarding, `/reports` shows three widgets populated with at least 4 weeks of data.
- [ ] *"Add a kcal-per-euro column"* causes a new column to appear in the leaderboard with values matching `(meal.kcal / meal.estimatedCost)`.
- [ ] After the column is added, reloading the page (or restarting the app) shows the leaderboard still containing the kcal-per-euro column.
- [ ] *"Show the category breakdown as a horizontal bar"* transforms the donut into a horizontal bar chart of the same data.
- [ ] Asking *"why was last week cheaper?"* names ≥ 1 specific meal or category from last week's plan and references real values.
- [ ] *"Reset the leaderboard"* removes the added column and any other customizations.
- [ ] A request for a column requiring data we don't have (e.g., "carbon footprint per meal") returns a refusal with reasoning, not a fabricated column.

---

## UI / Routes

- One single-panel layout (per the design system's "View panel" section): KPI strip → chart row (`1fr 1fr` on desktop, stacked below 1024px) → leaderboard → AI insight, all hairline-separated inside one filled `.mise-reports-panel`. No per-widget cards or gaps.
- Chart canvas + plot area are transparent so the panel background reads through. Series colors come from `--mise-category-*` per data point; axis lines, ticks, grid lines, and label text use the panel's hairline/secondary tokens. See "Charts (Reports)" in the design system.
- "Edited" highlighting on freshly transformed widgets fades over a few seconds. Highlight uses an inset shadow so the panel's section background stays consistent.
- Reset-to-defaults: a small icon button at the right of each widget's title row plus an in-chat capability.

| Route | Access | Notes |
|-------|--------|-------|
| `/reports` | public | `@Route("reports")`. |

---

## Verification

#### Functional

- [ ] Post-onboarding `/reports` shows ≥ 4 weeks of data in three default widgets
- [ ] *"Add kcal-per-euro column"* adds a column with values = `meal.kcal / meal.estimatedCost`; persisted via `ViewPreference`; survives reload (BR-04)
- [ ] *"Show as horizontal bar"* transforms donut → bar; persisted; survives reload
- [ ] *"Reset the leaderboard"* removes the customization
- [ ] Request for non-derivable column (e.g. carbon footprint) → explicit refusal (BR-03)
- [ ] Reports controllers attach on enter and detach on leave (BR-07)

#### Visual

- [ ] One single-panel Reports view with KPI strip, chart row, leaderboard, and in-panel AI insight, separated by hairlines
- [ ] Chart backgrounds transparent against the panel; donut renders a vertical right-side legend with category colors
- [ ] Transform highlight fades over a few seconds

#### AI

- [ ] "Why was last week cheaper?" names ≥ 1 concrete meal/category from that week
- [ ] No fabricated historical prices

#### Result

- **Status:**
- **Notes:**
