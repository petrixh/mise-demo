# UC-012: Dynamic Reports — describe charts and the grid in natural language

> Replaces the current **static** implementations of the chart row and leaderboard grid in the Reports panel with **dynamic** widgets driven by Vaadin's `ChartAIController` and `GridAIController` over a curated H2 reporting schema. Tightens and supersedes the AI-transform parts of UC-007 (column add / chart reshape); UC-007's defaults remain the **initial** state of each widget on first load.

---

**As a** home cook, **I want to** describe what each chart and grid in my Reports view should contain — *"show me a chart of how often I cook vegetarian dinners by month"*, *"in the leaderboard, rank by kcal-per-euro for hosting weeks only"* — **so that** the reports answer questions I actually have, instead of being pinned to the three default rollups.

**Status:** Draft
**Date:** 2026-05-19

---

## Main Flow

### Default state on first visit
- I navigate to `/reports`.
- The KPI strip + insight callout are unchanged (static, per UC-007).
- The two chart widgets and the leaderboard grid load with **default queries**:
  - Trend chart → "weekly total cost over time" (line)
  - Category chart → "average weekly cost by ingredient category" (donut)
  - Leaderboard grid → "recipes by appearance count, descending, top 10"
- Each widget is visibly an `AIDashboardWidget`-style controller-driven component, but stylistically still inside the single Reports panel from UC-007 (hairline-separated sections, no per-widget cards).

### Reshape via shared chat
- The chat dock at the bottom is the same one carried across views (UC-008). I type *"Make the trend chart compare cost vs. kcal per week, last 8 weeks"*.
- The assistant identifies the target widget (the trend chart), calls the chart-controller's tools to issue a new SQL query, and re-renders the chart in place as a multi-series line.
- The transform is persisted in `ViewPreference` keyed by `(householdId, view=REPORTS, widgetKey)`.
- A short "edited" highlight fades on the affected widget.

### Describe a brand-new query
- I type *"In the leaderboard, only count meals that the AI edited — rank by how often I kept them anyway"*.
- The assistant interprets the request, writes the matching SQL against the reporting schema, and the grid columns + rows reshape — `meal_name | times_ai_edited | times_kept_after_edit | retention_pct`. The grid title (set via the controller's `updateTitle` tool) updates to *"AI-edit retention"*.

### Reset
- I type *"reset the leaderboard"* (or click the per-widget reset icon).
- The assistant deletes the matching `ViewPreference` row; the widget reverts to its default query.

### Refuse what isn't there
- I type *"chart my carbon footprint per meal"*.
- The schema has no carbon-footprint data. The assistant explicitly says so and offers the closest reachable answer (e.g., "I can show kcal or cost intensity per meal — those are the proxies I have").

### Save / restore on reload
- I reload the page. Each widget restores from its persisted `ViewPreference`, with the same query, columns, and chart shape.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | Each dynamic widget is wrapped by a Vaadin **controller** — `ChartAIController` for charts, `GridAIController` for the leaderboard — bound to the shared `AIOrchestrator` while the user is on `/reports`. Controllers are detached on `BeforeLeave` (UC-007 BR-07 generalizes here). |
| BR-02 | A single **`MiseDatabaseProvider`** implements `com.vaadin.flow.component.ai.provider.DatabaseProvider`. It exposes a curated **reporting schema** (see UI / Routes §Schema) — not the raw JPA tables — so the AI's SQL surface is small, denormalized, and stable. The AI cannot reach beyond this schema. |
| BR-03 | The reporting schema is **read-only from the AI's perspective**. `executeQuery` rejects anything but `SELECT` (whitespace-trimmed, case-insensitive). DML / DDL is impossible. |
| BR-04 | The reporting schema is rebuilt from the JPA tables + catalogs at the points where underlying data changes: app startup, plan generation (UC-001 / UC-011), and any `Meal` mutation (UC-003 / UC-004). It is **not** queried live against JPA on every AI request — it's a snapshot table that any SQL hits. Snapshot freshness within a chat round-trip is guaranteed by writing the snapshot in the same transaction as the underlying change. |
| BR-05 | Each widget has a stable **`widgetKey`** in `ViewPreference` (e.g. `trendChart`, `categoryChart`, `leaderboard`). The persisted `settings` JSON has two top-level keys: `query` (the SQL the controller most recently ran, plain string) and `controllerStateB64` (the `ChartState` / `GridState` from the controller, **base64-encoded Java-serialized bytes**). The reference Vaadin AI example treats these state objects as opaque `Serializable` blobs — their internal JSON shape is not part of the public AI-components API and may change between alpha releases, so we round-trip them as bytes rather than trying to introspect them. Both the `query` and the `controllerStateB64` survive page reload **and** JVM restart because they live in H2 via `ViewPreference`, not in `VaadinSession` (which is where the reference example's state lives and is consequently lost on restart). |
| BR-06 | The orchestrator's system-prompt augmentation while on `/reports` includes (a) a one-line description of each widget, (b) the reporting-schema DDL via `DatabaseProvider.getSchema()`, and (c) the "no fabrication" rule from UC-007 BR-06. The widget descriptions name each widget so the AI can disambiguate *"in the trend chart"* vs *"in the leaderboard"*. |
| BR-07 | If the user's request does not name a widget and intent is ambiguous, the assistant asks one clarifying question (*"which one — the trend, the category breakdown, or the leaderboard?"*) before mutating anything. At most one clarification turn. |
| BR-08 | The assistant must refuse a request whose required data isn't in the reporting schema and propose the closest reachable proxy. No fabricated columns, no invented values. (UC-007 BR-03 generalized.) |
| BR-09 | "Edited" highlight + the `updateTitle` controller tool from the reference pattern are both used: after a successful reshape the widget's title is rewritten to reflect the new content (e.g. *"Vegetarian dinners by month"*) and a brief inset-shadow highlight fades out. |
| BR-10 | `ViewPreference` rows persist the per-widget `query` and `controllerState`. Reloading the page (or restarting the JVM) restores each widget to its last state. A "reset" deletes the row; the widget falls back to the default query baked into `ReportsView`. |
| BR-11 | The reporting schema **excludes `PLANNED` plans** by default (consistent with UC-007 BR-01). A view variant — `meal_history_with_planned` — exists for forward-looking questions but is only included in the AI's schema description when the user opts in (e.g., *"include planned weeks"*). Cuts the risk of the AI mixing projections into "what happened" answers. |

---

## Acceptance Criteria

- [ ] On a freshly seeded household, `/reports` loads with three dynamic widgets at their default queries — visually equivalent to the current UC-007 implementation.
- [ ] *"Make the trend chart compare cost vs. kcal per week, last 8 weeks"* causes the trend chart to re-render with two series over an 8-point x-axis, sourced via `executeQuery` against the reporting schema.
- [ ] *"In the leaderboard, rank by kcal-per-euro for hosting weeks only"* changes the leaderboard columns, rows, and sort. The new sort matches `kcal / cost` computed from the schema's columns.
- [ ] The widget's `ViewPreference` row contains a valid SELECT in `settings.query` and a non-empty `settings.controllerStateB64` (base64 Java-serialized bytes).
- [ ] Stopping and restarting the JVM, then reloading `/reports`, restores every customized widget to its exact prior state — query, columns, chart shape, title — sourced from H2, not session memory.
- [ ] A reload restores the customized state — same query, same chart shape, same columns, same title.
- [ ] *"Reset the leaderboard"* deletes the `ViewPreference` row and the widget reverts to its default query.
- [ ] *"Chart my carbon footprint per meal"* is refused with a one-sentence explanation and one concrete proxy offer.
- [ ] An ambiguous *"change the chart to a bar"* with two chart widgets present produces exactly one clarification turn naming both chart widgets.
- [ ] The `executeQuery` in `MiseDatabaseProvider` rejects any non-SELECT input with a clear error surfaced to the model (so it can self-correct rather than apply DML).
- [ ] By default, no row from a `Plan` with `status = PLANNED` appears in any widget's data. Asking *"include planned weeks"* enables the planned-aware view for the current session/widget; default returns on reset.
- [ ] Single-widget reshape latency ≤ 3 s; full Reports load (three widgets restoring state) ≤ 2 s excluding model latency.

---

## UI / Routes

- **No new routes.** `/reports` is the same single-panel layout as UC-007 — KPI strip → chart row → leaderboard → insight. The chart row and leaderboard sections become controller-driven.
- **Per-widget affordances** inside the panel header for each widget: a small **reset** icon (clears the `ViewPreference`) and a subtle **chat target** indicator (visual cue that this widget can be addressed in chat — e.g., a `data-testid="ai-target"` chip in the title row).
- **Shared chat dock** at the bottom (UC-008) — *not* per-widget popovers like the Vaadin reference. Targeting is by name in natural language (BR-07).

### Reporting schema (DDL the AI sees via `DatabaseProvider.getSchema()`)

A denormalized, read-only set of H2 tables maintained by `ReportService`. The shape is deliberately small.

```sql
-- One row per meal, joined with recipe and current price catalog.
CREATE TABLE meal_history (
  meal_id            BIGINT       PRIMARY KEY,
  plan_id            BIGINT       NOT NULL,
  week_start_date    DATE         NOT NULL,           -- Monday
  meal_date          DATE         NOT NULL,
  day_of_week        VARCHAR(9)   NOT NULL,           -- 'Monday'..'Sunday'
  recipe_id          VARCHAR(64)  NOT NULL,
  recipe_name        VARCHAR(200) NOT NULL,
  category_primary   VARCHAR(32)  NOT NULL,           -- 'Protein','Produce','Pantry','Dairy','Other'
  category_tags      VARCHAR(255) NOT NULL,           -- comma-separated: 'vegetarian,kid-friendly'
  cuisine            VARCHAR(64),
  servings           INT          NOT NULL,
  prep_minutes       INT          NOT NULL,
  kcal_per_serving   INT,
  est_cost_eur       DECIMAL(8,2) NOT NULL,           -- meal-total at current catalog
  status             VARCHAR(16)  NOT NULL,           -- 'PLANNED','EDITED','COOKED','SKIPPED'
  pinned             BOOLEAN      NOT NULL,
  edited_by_ai       BOOLEAN      NOT NULL,
  last_edited_at     TIMESTAMP
);

-- One row per plan with weekly rollups.
CREATE TABLE weekly_kpi (
  plan_id            BIGINT       PRIMARY KEY,
  week_start_date    DATE         NOT NULL,
  plan_status        VARCHAR(16)  NOT NULL,           -- 'ACTIVE','HISTORICAL','PLANNED'
  total_cost_eur     DECIMAL(8,2) NOT NULL,
  total_prep_minutes INT          NOT NULL,
  avg_kcal           INT,
  veg_meal_count     INT          NOT NULL,
  edited_meal_count  INT          NOT NULL
);

-- One row per AI edit (for "kept-after-edit" analyses).
CREATE TABLE meal_edit_history (
  edit_id            BIGINT       PRIMARY KEY,
  meal_id            BIGINT       NOT NULL,
  changed_at         TIMESTAMP    NOT NULL,
  previous_recipe_id VARCHAR(64),
  new_recipe_id      VARCHAR(64),
  reason             VARCHAR(500)
);
```

NOTES (appended to `getSchema()` so the AI sees them):
- `meal_history` and `weekly_kpi` are pre-aggregated for fast queries.
- By default, only `plan_status IN ('ACTIVE','HISTORICAL')` rows are visible. `PLANNED` rows live in `meal_history_with_planned` / `weekly_kpi_with_planned` and are exposed only when the user explicitly asks for forward-looking analysis.
- Day ordering: use `meal_date` for chronological sort, not `day_of_week`.
- Currency is EUR. Do not invent prices — every `est_cost_eur` is sourced from the live `PriceCatalog`.
- Reserved-word warning: H2 reserves `VALUE`, `KEY`, `ORDER`; alias accordingly.

### Snapshot maintenance

`ReportService.snapshot(...)` is the single writer. Triggered:
- On app startup after seed-loading (UC-001 BR-06).
- At the end of any `PlanService` mutating transaction (`generateActivePlan`, `seedHistory`, `generatePlannedWeeks`, `swapMeal`, `negotiateWeek`, `undoLastEdit`, `markStatus`, `pinMeal`).
- The snapshot writes are done in the same transaction as the trigger, so any AI tool call inside the same chat round-trip sees consistent state.

### System-prompt augmentation (sketch)

When `ReportsView` is entered, `MainLayout` registers a Reports-specific system message:

```
You are currently in the Reports view. Three widgets are available, addressable
by name:
  - trendChart: a line/column chart, by default "weekly total cost over time"
  - categoryChart: a chart, by default "average weekly cost by category"
  - leaderboard: a data grid, by default "recipes by appearance count"
Use the chart and grid controller tools to update them. When asked, query the
reporting schema:

{getSchema() output appended verbatim}

Rules:
- Issue SELECT-only SQL against the schema above. No DML/DDL.
- Do not invent columns or values. If the user asks for something not in the
  schema, say so and offer the closest available proxy.
- If the user's request does not name a widget and intent is ambiguous, ask
  which widget once before acting.
```

| Route | Access | Notes |
|-------|--------|-------|
| `/reports` | public | Same view as UC-007. New controllers attached on `AfterNavigation`, detached on `BeforeLeave`. |

---

## Verification

**Verified by:**
**Date:**

#### Functional

- [ ] Three default widgets render on fresh `/reports` load; data equivalent to UC-007 defaults
- [ ] Reshape via chat (chart and grid) updates the right widget and persists `ViewPreference`
- [ ] Reload restores `query` + `controllerState` for each customized widget (BR-05, BR-10)
- [ ] Reset deletes `ViewPreference` and reverts to default (BR-10)
- [ ] Ambiguous request → exactly one clarification turn naming the candidates (BR-07)
- [ ] Non-SELECT input to `MiseDatabaseProvider.executeQuery` is rejected with a model-actionable error (BR-03)
- [ ] PLANNED plans excluded by default; opt-in works for the session and clears on reset (BR-11)
- [ ] Snapshot is rewritten inside the same transaction as every plan/meal mutation listed in the schema section (BR-04)
- [ ] Reports controllers attach on enter, detach on leave (BR-01)

#### Visual

- [ ] Widgets render inside the single Reports panel from UC-007 — hairline separators, no per-widget cards
- [ ] "Edited" highlight fades after a few seconds; widget title updates to reflect new content
- [ ] Per-widget reset icon visible and reachable by keyboard
- [ ] Layout is unchanged at 1920 desktop and 390 mobile from UC-007's current implementation

#### AI

- [ ] Cost / kcal values in any rendered chart or grid cell match `est_cost_eur` / `kcal_per_serving` in `meal_history` (no fabrication)
- [ ] "Chart my carbon footprint per meal" refuses + offers a real proxy (BR-08)
- [ ] Single-widget reshape latency ≤ 3 s with the default model
- [ ] Full restore on `/reports` reload ≤ 2 s excluding model latency
- [ ] Conversation history (UC-008) records both the user's reshape requests and the resulting tool calls

#### Result

- **Status:**
- **Notes:**

---

## Implementation notes

### New / changed classes

- `com.example.mise.ai.MiseDatabaseProvider` — implements `DatabaseProvider`; backed by the same H2 instance, but reads only from the reporting schema; rejects non-SELECT in `executeQuery`. Bean.
- `com.example.mise.domain.reports.ReportSnapshotService` — owns the reporting tables. `@Transactional` `snapshot()` rebuilds all rows for a household; `snapshotPlan(planId)` rewrites rows for one plan when a meal mutates. Subscribed to `MealMutationEvent` / called inline from `PlanService` paths.
- `com.example.mise.ui.reports.ReportsView` — rebuilt around three `AIDashboardWidget`-style components, each wrapping its `Chart` / `Grid` with the appropriate controller. The single-panel layout, KPI strip, and insight callout from UC-007 stay.
- `HouseholdOrchestrator` — gains an "attach Reports controllers" path triggered from `ReportsView.onAttach`, augmenting the system prompt with the schema + widget descriptions; detached in `onDetach`.
- `ViewPreferenceService` — gains typed helpers for the new `{query, controllerStateB64}` JSON shape. Helpers wrap `ObjectOutputStream` / `ObjectInputStream` + `Base64` so callers deal in typed `ChartState` / `GridState` objects, not bytes. The CLOB column already in `ViewPreference.settings` is wide enough for the encoded payload; no schema change.

### Out of scope (for this UC)

- Adding **new widgets** at runtime (the reference example does this via toolbar). Mise keeps the fixed three-widget layout for now; "add a widget" is a candidate follow-up.
- Per-widget popover chats (the reference's UX). Mise keeps one shared chat dock per UC-008.
- Reshaping the **KPI strip** or the **insight callout** in chat — they remain static surfaces sourced from `WeeklyStats` (UC-002) and `InsightService` (UC-009).
- Reshaping widgets on `/plan` or `/shopping`. UC-012 is scoped to `/reports`; the same pattern is a natural follow-up for the other views once the schema-and-controller plumbing is shaken out here.
- Exposing arbitrary JPA tables. Only the curated reporting schema is reachable by SQL; the conversation, household, and pantry tables remain firmly outside the AI's SQL surface.
- Long-running / streaming queries. The reporting schema is small (single-household demo), so simple `executeQuery` is fine.

### Migration from UC-007

- UC-007 BR-02 and BR-04 still hold — the controller-bounded transforms and `ViewPreference` persistence — but the wording will be tightened on UC-007 once UC-012 is approved, so UC-007 reads as the *visual + behavioral* default and UC-012 reads as the *dynamic-shape* layer on top. No conflict expected: UC-012's "default queries" produce UC-007's "default widgets".
