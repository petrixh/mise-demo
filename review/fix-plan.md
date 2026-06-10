# Fix Plan — verified baseline + staged work (2026-06-10)

## 0. Tooling & runtime verification — done, all green

| Check | Result |
|---|---|
| App boots (`./mvnw spring-boot:run`) | ✅ Serves on `localhost:8080`, redirects `/` → `/plan` (household seeded in H2) |
| LLM endpoint reachable | ✅ `http://ru-dolfs-macbook-pro.tailea4a5d.ts.net:8080/v1/models` responds (llama-swap, ~23 models) |
| Configured model exists | ✅ `Qwen3.6-35B-A3B-UD-Q4_K_XL-128k-coding-paralel-2` is in the model list |
| Full chat round-trip through the UI | ✅ Sent *"What's for dinner on Friday?"* via `vaadin-message-input`; assistant streamed a reply through `AIOrchestrator` → live LLM |
| Visual check | ✅ Plan view renders correctly (stats bar, meal grid, cost-by-category sidebar, insight callout with "Act on it", undo strip) |

Two side-findings from the round-trip, both already folded into the review docs:

1. **Bug:** the reply ("no meal planned for Friday yet") was wrong — `resolveDate()` resolves day names against the real-world week while the rest of the tool uses the viewed plan (week of May 18). Details in `spec-compliance.md` § UC-010.
2. **Demo papercut:** the ACTIVE plan is weeks stale (May 18 on Jun 10) because nothing promotes/rolls plans — UC-011's BR-06 will fix this properly.

Harness note: the Playwright **MCP** can't launch its `chrome-for-testing` channel in this sandbox (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` makes its installer a no-op). Workaround that works fine: drive the app with the globally-installed `playwright` Node library (CommonJS script, chromium from `/opt/playwright-browsers`).

## 1. What the official docs say about the controllers

From [ai-powered-grid](https://vaadin.com/docs/next/flow/ai-support/ai-powered-grid) and [ai-powered-chart](https://vaadin.com/docs/next/flow/ai-support/ai-powered-chart) (Vaadin 25.2 pre-release docs):

```java
Grid<AIDataRow> grid = new Grid<>();                       // framework-owned row type
DatabaseProvider db = new JdbcDatabaseProvider(dataSource); // or a custom DatabaseProvider
GridAIController gridCtl = new GridAIController(grid, db);
ChartAIController chartCtl = new ChartAIController(chart, db);

AIOrchestrator.builder(provider, systemPrompt)
        .withInput(messageInput)
        .withController(gridCtl)        // ← attach point
        .build();
```

Key facts for our design:

- **State persistence:** `controller.addStateChangeListener(state -> …)` emits a `GridState` / `ChartState` (essentially the SQL query + config); `controller.restoreState(saved)` restores. Maps directly onto UC-012's `ViewPreference.settings = {query, controllerStateB64}` plan.
- **Controllers are not serializable** — after session deserialization, recreate and `orchestrator.reconnect(provider).withController(ctl).apply()`. Relevant since our orchestrator is rebuilt per `MainLayout` anyway, so this is the pattern we'd use on every view attach/detach (matching UC-007 BR-07's detach-on-leave requirement).
- **`DatabaseProvider` is the sandbox.** `JdbcDatabaseProvider` wraps a `DataSource` and exposes its schema to the LLM. UC-012's curated reporting schema (`meal_history`, `weekly_kpi`, `meal_edit_history`) means we should **not** hand it the main H2 datasource — either a custom `MiseDatabaseProvider` that exposes only the curated DDL, or a second H2 schema holding only the snapshot tables fed by `ReportSnapshotService`.
- **Chart caveat:** `ChartAIController` does not support OpenAI strict tool-calling mode (Spring AI's default is non-strict — fine for the Qwen endpoint).
- **`DataConverter`** hook exists for custom SQL→series mapping if the defaults don't fit our category-color tokens.

## 2. Version bump target

- Current: `vaadin.version = 25.2.0-alpha5` (from `maven.vaadin.com/vaadin-prereleases`).
- **Latest pre-release: `25.2.0-beta1`** (GitHub release 2026-05-28), available on **Maven Central** (not yet in the prereleases repo). Alpha8 is also available, but beta1 is newer and its notes call out "AI-powered Charts and Grids (Pro) — preview release", i.e. exactly the components we need.
- Latest stable remains 25.1.7 (no AI controllers there).

**Known risk to verify during the bump** (sharp edge from `.claude/memory/`): Vaadin AI components alpha5 are pinned to **Spring AI 2.0.0-M4**; M5/M6 broke `SpringAILLMProvider` (`MessageChatMemoryAdvisor$Builder.conversationId` rename). Beta1 may have moved its Spring AI baseline — check `vaadin-ai-components` (or equivalent artifact) beta1's POM before touching our `spring-ai.version`, and re-run the AIIT suite after the bump.

## 3. Staged plan

### Stage A — spec updates (do first; mostly editing, sets the target)

1. **UC-007 amendment:** rewrite BR-02/BR-07 to describe the *current* mechanism honestly (bespoke `@Tool`s + `ViewPreference` + refresh broadcaster), and state explicitly that controller-driven widgets arrive with UC-012, which supersedes those tools. Update UC-007's `## Verification` accordingly.
2. **`architecture.md` alignment** — mark the controller/`DatabaseProvider` sections as "designed, lands with UC-012", and record the beta1 + Spring-AI-baseline decision once known. ⚠️ Guardrail: requires explicit owner approval before editing.
3. **UC-012 spec refresh against the real API:** the spec predates the docs we just read — fold in `GridState`/`ChartState` (replaces hand-waved `controllerStateB64` semantics), `Grid<AIDataRow>` (the leaderboard becomes a framework-typed grid when AI-driven), the reconnect-after-deserialize pattern, and the strict-mode caveat. Decide custom `MiseDatabaseProvider` vs. `JdbcDatabaseProvider`-over-snapshot-schema and write it down.
4. **UC-010 spec note + UC-011 dependency:** document the viewed-week day-name resolution rule (what "Friday", "today", "tomorrow" mean when viewing a non-current week) — the bug fix needs a spec'd answer, not an ad-hoc one.
5. **`verification.md`:** add a "manual pre-demo checklist" section for the unautomatable teaching moments (UC-006 seed-price flip, insight callout placement, week-badge styling).

### Stage B — quick bug fixes (independent of the bump; can run parallel to A)

1. `PlanTools.resolveDate()` → resolve day names against the **viewed plan's Monday**; clarify/refuse "today/tomorrow" when viewing another week (per the Stage-A-4 spec decision). Unit test + an AIIT case that views a past week.
2. The small code-quality items from `code-quality.md` worth doing before new work piles on: stray `--lumo-base-color` (mise-reports.css:182), silent catch in `MainLayout.buildWeekLabel`, broadcaster consolidation (one class, three beans) since UC-012 will add a fourth refresh consumer otherwise.

### Stage C — Vaadin 25.2.0-beta1 bump (gate for UC-012)

1. Branch; bump `vaadin.version` to `25.2.0-beta1`. ⚠️ Guardrail: `pom.xml` change — owner already signalled intent, but confirm before merging. Check whether the prereleases repo declaration still resolves everything or Maven Central suffices.
2. Check the AI components' Spring AI baseline in beta1; bump `spring-ai.version` **only** if Vaadin's artifact demands it.
3. Verify ladder: `./mvnw test` → `./mvnw -Pit verify` (expect one slow frontend rebuild) → one AIIT class against the live endpoint → manual chat round-trip (the script from §0 works as a smoke test).
4. Watch for the beta1 breaking changes called out in release notes (`@StyleSheet` relative-path resolution — we use `@import` inside `styles.css`, likely unaffected, but verify the served bundle with the usual `curl | grep` trick).

### Stage D — UC-012: the controller showcase (the point of the demo)

1. `ReportSnapshotService` + curated reporting tables (rebuild on startup/plan-mutation, same transaction as trigger per BR-04).
2. `DatabaseProvider` implementation per the Stage-A-3 decision.
3. `ChartAIController` on the cost/category widgets, `GridAIController` on the leaderboard; attach via `withController()` on view attach, detach on `BeforeLeaveEvent` (finally making UC-007 BR-07 true); persist `GridState`/`ChartState` through `ViewPreference`.
4. Retire (or demote to a documented "tool-based alternative" contrast) the bespoke `ReportsTools` transform tools.
5. Tests: unit (snapshot service), IT (widgets render from restored state), AIIT ("show cost per kcal by week as a line chart" actually reshapes).

### Stage E — UC-011: future weeks + rollover

`Plan.Status.PLANNED`, `planFutureWeeks` tool, date-range resolution, idempotence, 8-week cap, and the `findActivePlan` promotion sweep — which also permanently fixes the stale-week papercut from §0.

## 4. Open decisions for the owner

1. **Approve Stage A-2** (`architecture.md` edits) and **Stage C-1** (`pom.xml` bump to beta1) — both behind ask-first guardrails.
2. **UC-007 tools after UC-012:** delete the bespoke transform tools, or keep one as a teaching contrast ("tool-based" vs "controller-based")? Recommendation: delete; the contrast is documented better in prose than in shipped dual code paths.
3. **DatabaseProvider shape:** custom `MiseDatabaseProvider` (full control of exposed DDL, more code) vs `JdbcDatabaseProvider` over a dedicated snapshot schema (less code, sandbox enforced by schema separation). Recommendation: the latter, demo-simplicity first — revisit only if the LLM strays outside the snapshot tables.
