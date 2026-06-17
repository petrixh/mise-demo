# UC-012: Dynamic Reports — describe charts and the grid in natural language

> Replaces the original static chart row and leaderboard grid in the Reports panel with **dynamic** widgets driven by Vaadin's `ChartAIController` and `GridAIController` over a curated H2 reporting schema. Supersedes the AI-transform parts of UC-007 (the fixed transform tools are gone); UC-007's defaults remain the **initial** state of each widget.

---

**As a** home cook, **I want to** describe what each chart and grid in my Reports view should contain — *"show me a chart of how often I cook vegetarian dinners by month"*, *"in the leaderboard, rank by kcal-per-euro"* — **so that** the reports answer questions I actually have, instead of being pinned to the three default rollups.

**Status:** Implemented
**Date:** 2026-05-19 (implemented against Vaadin 25.2.0-beta1, 2026-06-10)

---

## Main Flow

### Default state on first visit
- I navigate to `/reports`. The KPI strip + insight callout are static (UC-007 / UC-009).
- The two charts and the leaderboard load with **default queries** baked into `ReportsWidgets`:
  - `trendChart` → weekly total cost over time (line, datetime x-axis)
  - `categoryChart` → cost by ingredient category for the viewed week (donut, category colors)
  - `leaderboard` → recipes by appearance count, descending
- Defaults are expressed as the same `ChartState`/`GridState` objects the AI produces, so default, AI-reshaped, and restored states all render through one path (`restoreState`).

### Reshape via shared chat
- I type *"Make the trend chart compare cost vs. kcal per week"* into the shared chat dock (UC-008).
- The assistant identifies the target widget, calls the controller tools (`update_chart_data_source` / `update_chart_configuration` / `update_grid_data`) with SQL against the reporting schema, and the widget re-renders at the end of the turn (the controllers defer rendering to the response boundary, on the UI thread).
- The new state is persisted to `ViewPreference` and a short "edited" highlight fades on the widget.

### Reset
- I type *"reset the leaderboard"* (→ `resetReportsWidget`) or click the per-widget reset icon. The `ViewPreference` row is deleted and the widget reverts to its default query.

### Refuse what isn't there
- *"Chart my carbon footprint per meal"* → the schema has no such data; the assistant says so and offers the closest reachable proxy (cost or kcal intensity).

### Save / restore on reload
- Reloading the page — or restarting the JVM — restores each widget from its persisted `ViewPreference`.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | Each dynamic widget is wrapped by a Vaadin controller — `ChartAIController` for the charts, `GridAIController` for the leaderboard. Controllers (and their Chart/Grid components) live in a per-UI `ReportsWidgets` holder owned by `MainLayout` and are registered on the shared `AIOrchestrator` **at build time**; `ReportsView` adopts the components into its layout on attach. (Runtime attach/detach was the original design, but `AIOrchestrator.reconnect` is deserialization-only — and build-time registration turns out better: cross-view "go to reports and reshape X" works in one turn, consistent with UC-008.) View scoping is by system prompt, like all other tools. **One-controller constraint:** the orchestrator holds exactly one `AIController` (a later `withController` replaces the earlier), so `ReportsAIController` composes the three real controllers into one — the leaderboard's tools pass through, the two charts' colliding tool names are namespaced `trend_*` / `category_*` (the convention `ToolSpec`'s javadoc recommends), duplicate helper tools are deduped, and `onRequest`/`onResponse` fan out so every widget's deferred render applies at end of turn. |
| BR-02 | A single `MiseDatabaseProvider` implements `DatabaseProvider`. The schema it **describes** to the AI (`getSchema()`) contains only the curated reporting tables — the model never learns the JPA table names. (The H2 instance is shared, so this is prompt-surface curation plus the BR-03 guard, not a DB-level grant; a production deployment would back the provider with a restricted DB user.) |
| BR-03 | The reporting surface is read-only: `executeQuery` rejects anything but a single `SELECT` (whitespace-trimmed, case-insensitive, no statement chaining) with an error the model can read and self-correct from. |
| BR-04 | The reporting tables are **snapshots** rebuilt from the JPA tables + catalogs by `ReportSnapshotService`. Freshness: every `executeQuery` first compares a cheap fingerprint of the source tables (row counts + max `last_edited_at`) and rebuilds when it changed — so a meal mutation earlier in the same chat turn is always visible, with zero coupling into `PlanService`. Demo-scale data makes the full rebuild cheap; there is no incremental path. |
| BR-05 | Each widget has a stable `widgetKey` in `ViewPreference` (`trendChart`, `categoryChart`, `leaderboard`). The leaderboard persists `{query}` (a `GridState` *is* its SQL string). The charts persist `{query, controllerStateB64}` — `ChartState` bundles the queries with a Charts `Configuration`, which is not a stable public JSON shape, so it round-trips as base64 Java-serialized bytes. State lives in H2, not `VaadinSession`, so it survives JVM restarts. A corrupt/incompatible saved state falls back to the default query (logged, not fatal). |
| BR-06 | The orchestrator's system prompt names the three widgets and their namespaced tools so the AI can disambiguate (*"in the trend chart"* → `trend_*` tools); the schema itself reaches the model on demand through `get_database_schema`, and the full per-widget workflow through `get_reports_widget_instructions`, rather than being inlined into the prompt. |
| BR-07 | If a reshape request does not name a widget and intent is ambiguous, the assistant asks one clarifying question before mutating anything. At most one clarification turn. |
| BR-08 | The assistant must refuse a request whose required data isn't in the reporting schema and propose the closest reachable proxy. No fabricated columns, no invented values. |
| BR-09 | After a successful AI reshape, a brief inset-shadow highlight fades on the affected widget (driven by the controller's state-change listener, which does not fire on restore — so reloads don't flash). Widget titles are static; beta1's controllers have no title tool. |
| BR-10 | Reload (or JVM restart) restores each customized widget; reset deletes the row and the widget falls back to the default query baked into `ReportsWidgets`. |
| BR-11 | UC-011 introduced `PLANNED` plans. The default snapshot tables (`meal_history`, `meal_category_cost`, `weekly_kpi`) **exclude** `PLANNED` rows so past/current reporting is unaffected. Forward-looking variants `meal_history_with_planned` / `weekly_kpi_with_planned` include all weeks and are surfaced to the AI for opt-in use only — the system prompt instructs the model to query them solely for explicit forward-looking questions ("what will next month cost?"). Implemented in `ReportSnapshotService` (UC-011). |

---

## Acceptance Criteria

- [ ] On a freshly seeded household, `/reports` loads with three dynamic widgets at their default queries — visually equivalent to the original UC-007 implementation.
- [ ] A reshape request (e.g. *"rank the leaderboard by kcal per euro"*) changes the widget via `executeQuery` against the reporting schema, and the new state is persisted.
- [ ] The widget's `ViewPreference` row contains a valid SELECT in `settings.query` (charts also a non-empty `settings.controllerStateB64`).
- [ ] Restarting the JVM and reloading `/reports` restores every customized widget — query, columns, chart shape.
- [ ] *"Reset the leaderboard"* deletes the `ViewPreference` row and the widget reverts to its default query.
- [ ] *"Chart my carbon footprint per meal"* is refused with a one-sentence explanation and one concrete proxy offer.
- [ ] An ambiguous *"change the chart to a bar"* with two chart widgets present produces exactly one clarification turn naming the candidates.
- [ ] `MiseDatabaseProvider.executeQuery` rejects any non-SELECT input with a clear error surfaced to the model.

---

## UI / Routes

- **No new routes.** `/reports` keeps UC-007's single-panel layout; the chart row and leaderboard sections are the controller-driven components adopted from `ReportsWidgets`.
- **Per-widget affordances:** a small reset icon in each widget's title row; targeting is by name in natural language through the shared chat dock (no per-widget popovers).

### Reporting schema (DDL the AI sees via `getSchema()`)

The DDL lives as the single source of truth in `ReportSnapshotService.SCHEMA_DDL` — four denormalized tables:

- `meal_history` — one row per meal across all plans, joined with recipe metadata and current catalog prices (`recipe_name`, `meal_date`, `day_of_week`, `category_tags`, `cuisine`, `servings`, `prep_minutes`, `kcal_per_serving`, `est_cost_eur`, `status`, `pinned`, `edited_by_ai`, …).
- `meal_category_cost` — per-meal ingredient cost bucketed into the five canonical categories (`Protein`/`Produce`/`Pantry`/`Dairy`/`Other`); powers the category chart's default and any per-category analysis.
- `weekly_kpi` — one row per plan with weekly rollups (`total_cost_eur`, `total_prep_minutes`, `avg_kcal`, `veg_meal_count`, `edited_meal_count`).
- `meal_edit_history` — one row per recorded edit (for "kept after AI edit" analyses).

Plain-language notes (H2 dialect, SELECT-only, EUR, "don't invent values") are appended to the DDL.

### Chat data questions

Alongside the widget controllers, one Spring AI tool — `ReportingTools.queryReportingData(sql)` — gives the model the same read-only SQL surface for **answering questions in chat** (*"why was last week more expensive?"*), from any view. This replaces the old fixed `explainWeekVsAverage` analysis: the model writes the analysis query itself.

| Route | Access | Notes |
|-------|--------|-------|
| `/reports` | public | Same view as UC-007; widgets adopted from the per-UI `ReportsWidgets`. |

---

## Verification

**Verified by:** Claude (unit + IT suites; live LLM round-trip)
**Date:** 2026-06-10

#### Functional

- [x] Three default widgets render on fresh `/reports` load (`ReportsViewIT`)
- [x] Persisted query restores into the leaderboard on load; survives reload (`ReportsViewIT.persistedLeaderboardQueryRestoresOnLoad`)
- [x] Reset deletes `ViewPreference` and reverts to default (`ReportsViewIT.resetWidgetRevertsToDefaultQuery`, `ReportingToolsAIIT.resetLeaderboardDeletesViewPreference`)
- [x] Non-SELECT input rejected with model-actionable error (`ReportSnapshotServiceTest.executeQueryRejectsNonSelect`)
- [x] Snapshot reflects meal mutations on the next query (`ReportSnapshotServiceTest.snapshotRefreshesAfterMealMutation`)
- [x] Schema description contains only reporting tables (`ReportSnapshotServiceTest.schemaDescribesOnlyReportingTables`)

#### AI

- [x] Live reshape via chat updates the right widget and persists (browser round-trip, 2026-06-10: "rank the meals by kcal per euro" → Recipe/Kcal/Cost/Kcal-per-€ grid, survives reload)
- [x] "Why was last week cheaper?" grounded in query results (`ReportingToolsAIIT`, live LLM)
- [x] Carbon-footprint request refused with proxy (`ReportingToolsAIIT`, live LLM)

#### Result

- **Status:** Pass — implemented and verified (unit + IT suites, AIIT against live LLM, live browser reshape round-trip)
- **Notes:** —

---

## Out of scope (unchanged)

- Adding new widgets at runtime; per-widget popover chats; reshaping the KPI strip / insight callout; reshaping widgets on `/plan` or `/shopping`; exposing arbitrary JPA tables; long-running queries.
