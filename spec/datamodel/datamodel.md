# Data Model

> Entity definitions and relationships. Evolves as features are added.

This project has two distinct sources of data:

1. **Application state in H2** — what the user and the AI mutate during use (household preferences, plans, meals, pantry, conversation, view preferences, insights). Modelled as JPA entities, persisted across restarts.
2. **Seed catalog data on disk** — what the demo reasons *over* but does not mutate (recipes, store prices, nutrition facts, personas). Loaded from YAML / JSON at startup behind the capability interfaces in [`architecture.md`](../architecture.md). Modelled as DTOs, not JPA entities.

Editing seed files and restarting must visibly change AI reasoning. Keeping them out of H2 is what makes that work.

---

## 1. Persistent entities (H2 / JPA)

| Entity | Key fields | Relationships |
|--------|-----------|---------------|
| **Household** | `id` (PK), `name`, `size`, `currency` (EUR), `weeklyBudget` (decimal), `dietaryConstraints` (JSON: list), `allergies` (JSON: list), `hatedFoods` (JSON: list), `lovedFoods` (JSON: list), `cuisinePrefs` (JSON: list), `hostingPattern` (JSON: free-form notes), `insightsMuted` (bool), `insightFrequency` (enum), `createdAt`, `updatedAt` | Has many `Plan`, `PantryItem`, `ConversationMessage`, `ViewPreference`, `Insight` |
| **Plan** | `id` (PK), `householdId` (FK), `weekStartDate` (Monday), `status` (`ACTIVE` \| `HISTORICAL` \| `PLANNED`), `notes` (text), `createdAt`, `updatedAt` | Belongs to `Household`. Has many `Meal`. |
| **Meal** | `id` (PK), `planId` (FK), `date`, `slot` (`DINNER` for demo), `recipeRef` (string — external ID from `RecipeCatalog`), `servings` (int), `status` (`PLANNED` \| `EDITED` \| `COOKED` \| `SKIPPED`), `pinned` (bool), `note` (text), `lastEditedAt`, `lastEditedBy` (`USER` \| `AI`) | Belongs to `Plan`. References a `Recipe` by `recipeRef` (no FK — recipes live outside H2). |
| **MealEdit** | `id` (PK), `mealId` (FK), `previousRecipeRef`, `previousServings`, `previousStatus`, `changedAt`, `changedBy` (`USER` \| `AI`), `reason` (text — AI-supplied justification) | Belongs to `Meal`. Powers undo and the "why did you swap that?" answer. |
| **PantryItem** | `id` (PK), `householdId` (FK), `ingredientName`, `quantity` (decimal, nullable), `unit` (string, nullable), `isStaple` (bool — "default staples" survive shopping-list generation), `updatedAt` | Belongs to `Household`. |
| **ConversationMessage** | `id` (PK), `householdId` (FK), `messageId` (nullable string — stable id from `com.vaadin.flow.component.ai.common.ChatMessage#messageId` when present), `role` (`USER` \| `ASSISTANT` \| `SYSTEM` \| `TOOL`), `content` (text/CLOB), `toolName` (nullable), `toolCallId` (nullable), `viewContext` (`PLAN` \| `SHOPPING` \| `REPORTS` \| `ONBOARDING`), `createdAt` | Belongs to `Household`. Serialized to and from `ChatMessage` for orchestrator rehydration. `messageId` is **null for assistant turns** (Vaadin's `AIOrchestrator` leaves it null in `streamResponseToMessage`), so `ConversationService` syncs by appending past `repository.count()` rather than deduping on messageId. |
| **ViewPreference** | `id` (PK), `householdId` (FK), `view` (`PLAN` \| `SHOPPING` \| `REPORTS`), `widgetKey` (string — stable identifier per widget), `settings` (JSON — e.g. `{ "chartType": "bar" }`, `{ "extraColumns": ["kcalPerEuro"] }`), `updatedAt` | Belongs to `Household`. One row per `(household, view, widget)`. |
| **Insight** | `id` (PK), `householdId` (FK), `body` (text), `evidenceRefs` (JSON — plan IDs, meal IDs the insight derives from), `createdAt`, `dismissed` (bool), `dismissedAt` (nullable) | Belongs to `Household`. |

### Indexes

- `Meal (planId, date)` — used on every plan render.
- `ConversationMessage (householdId, createdAt)` — chronological history load.
- `Plan (householdId, weekStartDate desc)` — current-week lookup and historical iteration for Reports.
- `Insight (householdId, dismissed, createdAt desc)` — surface only undismissed insights.

### Lifecycle notes

- **One active plan per household at a time** (`status = ACTIVE`). When a new week begins, the prior `ACTIVE` plan transitions to `HISTORICAL` (used by Reports). If a `PLANNED` plan exists for the new current week (UC-011), it is promoted to `ACTIVE` in the same transaction — preserving the single-`ACTIVE`-plan invariant.
- **Planned future weeks** (`status = PLANNED`) are produced by UC-011 ahead of the real-world date and are navigable via UC-010. They are excluded from default historical reports (UC-007 BR-01). A `PLANNED` plan is otherwise structurally identical to an `ACTIVE` plan — same 7 `Meal` rows, same eligibility filters from `Household`.
- **Undo scope.** `MealEdit` rows are retained indefinitely in the demo (small data). Undo affordances target the most recent `MealEdit` per meal.
- **Conversation rolling window.** All messages are persisted, but only the most recent N (configurable, default 50) are passed to the orchestrator on rehydration to bound context size. The full history remains queryable for "why?" questions.

---

## 2. Seed catalog data (YAML / JSON, in-memory)

These are **DTOs** loaded at startup. They are not JPA entities — they have no IDs in H2, no repositories, and the user cannot edit them through the UI.

| Type | Source | Key fields |
|------|--------|-----------|
| **Recipe** | `demo/data/recipes/<id>.yaml`, one file per recipe | `id` (filename-derived), `name`, `cuisine`, `categoryTags` (e.g. `vegetarian`, `kid-friendly`, `hosting`), `prepMinutes`, `defaultServings`, `ingredients` (list of `RecipeIngredient`), `macros` (`kcal`, `proteinG`, `carbG`, `fatG`, per serving), `estimatedCost` (decimal, optional — actual cost computed from `PriceCatalog`), `notes` |
| **RecipeIngredient** | nested in `Recipe.ingredients` | `name`, `quantity`, `unit`, `aisle` (e.g. `Produce`, `Dairy`, `Pantry`), `optional` (bool) |
| **Store** | `demo/data/stores/<id>.yaml`, one file per store | `id`, `name`, `detourMinutesFromRoute`, `defaultStore` (bool), `catalog` (list of `StoreItem`) |
| **StoreItem** | nested in `Store.catalog` | `ingredientName`, `price`, `unit`, `packSize`, `onSale` (bool), `saleUntil` (date, optional) |
| **Persona** | `demo/data/personas/<id>.json` | `id`, `name`, `size`, `dietaryConstraints`, `allergies`, `hatedFoods`, `lovedFoods`, `weeklyBudget`, `hostingPattern`, `defaultPantry` (list of staple names), `seedWeeks` (count of historical plans to seed for Reports) |
| **ActivePersona** | `demo/data/active_persona.txt` | Single line: the persona `id` to materialize into the `Household` row on first run. |
| **NutritionRecord** | derived from `Recipe.macros`, plus canned ingredient-level facts in `StubbedNutritionEstimator` | Returned by `NutritionEstimator.estimate(...)`; "unknown" sentinel when no data. |

### Seed-to-state translation

On first run (no rows in `Household`):

1. Read `active_persona.txt` → load persona JSON.
2. Insert one `Household` populated from the persona.
3. Insert `PantryItem` rows for each `defaultPantry` entry (flagged `isStaple = true`).
4. Generate `seedWeeks` historical plans (`status = HISTORICAL`) with meals drawn from the recipe library, honoring constraints, plus one current `ACTIVE` plan.
5. (Conversation, view preferences, insights start empty.)

Subsequent runs skip seeding; the H2 state is the source of truth.

---

## 3. Derived structures (computed on demand)

These are never persisted — they are produced by services on each request and rendered.

| Structure | Producer | Composition |
|-----------|----------|-------------|
| **ShoppingList** | `ShoppingListService.derive(planId)` | Aggregates ingredients across the plan's meals, subtracts `PantryItem`s (especially `isStaple`), consolidates by `(ingredientName, unit)`, groups by `aisle`, and joins to `PriceCatalog` to attach prices and store recommendations. |
| **StoreRecommendation** | `ShoppingListService` | The recommended store(s), the per-item "cheaper elsewhere" notes, and a "detour worth it?" verdict for each alternative store given the `detourMinutesFromRoute` and total savings. |
| **WeeklyStats** | `PlanService.stats(planId)` | Totals and averages for the active plan: cost, prep time, average kcal, cuisine variety index, dietary mix. |
| **Report** | `ReportService.report(householdId, range, viewPrefs)` | Multi-week aggregations (cost trend, category breakdown, per-meal value leaderboard), shaped by the household's `ViewPreference` entries (chart type, extra columns). |

---

## 3a. Persisted derived snapshots (H2)

These tables live in H2 alongside §1's JPA entities, but they are **derived** — never edited by the user, never written by the AI directly. They are denormalized read-models maintained by `ReportSnapshotService` to give Vaadin's `DatabaseProvider` (UC-012) a small, stable SQL surface. Introduced in UC-012.

| Table | Producer | Refresh triggers | Composition |
|-------|----------|------------------|-------------|
| **`meal_history`** | `ReportSnapshotService` | App startup; every `PlanService` mutation (`generateActivePlan`, `seedHistory`, `generatePlannedWeeks`, `swapMeal`, `negotiateWeek`, `undoLastEdit`, `markStatus`, `pinMeal`) | One row per `Meal`, joined with `Recipe` (DTO) and the current `PriceCatalog`. Columns include `meal_date`, `recipe_name`, `category_primary`, `est_cost_eur`, `kcal_per_serving`, `status`, `edited_by_ai`. See UC-012 §"Reporting schema" for the full DDL. |
| **`weekly_kpi`** | `ReportSnapshotService` | Same triggers as above | One row per `Plan` with weekly rollups: `total_cost_eur`, `total_prep_minutes`, `avg_kcal`, `veg_meal_count`, `edited_meal_count`. |
| **`meal_edit_history`** | `ReportSnapshotService` | Triggered when a `MealEdit` is written by `PlanService.swapMeal` / `negotiateWeek` / `undoLastEdit` | One row per `MealEdit` with `previous_recipe_id`, `new_recipe_id`, `changed_at`, `reason`. Powers "AI-edit retention" style queries. |

**Default visibility:** the three tables filter out `plan_status = 'PLANNED'` rows by default (consistent with UC-007 BR-01). Forward-looking variants `meal_history_with_planned` / `weekly_kpi_with_planned` exist and are surfaced to the AI only on explicit opt-in (UC-012 BR-11).

**Why not regular JPA entities?** They have no business identity — they're rebuilt from the entities in §1. Querying them through Spring Data would defeat the purpose; the whole point is to give `MiseDatabaseProvider.executeQuery(sql)` something small and SELECT-shaped to read. Refresh writes happen inside the same transaction as the triggering mutation so any AI tool call later in the same chat round-trip sees consistent state.

---

## 4. Entity relationship diagram (text)

```
Household 1 ──── N Plan ──── N Meal ──── N MealEdit
   │                          │
   │                          └── (recipeRef → Recipe DTO, by string id)
   │
   ├──── N PantryItem
   ├──── N ConversationMessage
   ├──── N ViewPreference
   └──── N Insight


(Outside H2 — seed data, in-memory)

RecipeCatalog ──── * Recipe ──── * RecipeIngredient
PriceCatalog  ──── * Store  ──── * StoreItem
Personas      ──── * Persona
```

---

## 5. Use-case to entity mapping (forward reference)

This will be filled in as use cases are written; it is the cross-check `spec-validate` will run.

| Use case | Reads | Writes |
|----------|-------|--------|
| UC-001 Onboarding | `Persona` (selected) | `Household`, `PantryItem`, `Plan` (active + seed history), `Meal`, `ConversationMessage` |
| UC-002 View current plan | `Plan`, `Meal`, `Recipe`, `PriceCatalog`, `NutritionEstimator` | `ConversationMessage` (if asked) |
| UC-003 Edit meals via chat (swap / negotiate / pin) | `Plan`, `Meal`, `Recipe`, `PriceCatalog`, `Household` | `Meal` (one or many), `MealEdit`, `ConversationMessage` |
| UC-004 Undo & explain ("why?") | `MealEdit`, `ConversationMessage`, `Household` | `Meal` (revert), `MealEdit` (revert row), `ConversationMessage` |
| UC-005 Shopping list (view / pantry / store mode) | `Plan`, `Meal`, `Recipe`, `PantryItem`, `Store`, `StoreItem` | `PantryItem`, `ViewPreference` (Shopping `storeMode`), `ConversationMessage` |
| UC-006 Detour reasoning & alternatives | `PriceCatalog`, `Plan`, `Meal`, `Household` | `Meal` (if alt-swap accepted), `MealEdit`, `ConversationMessage` |
| UC-007 Reports — defaults & AI transforms | `Plan` (history), `Meal`, `Recipe`, `PriceCatalog` | `ViewPreference` (Reports widgets), `ConversationMessage` |
| UC-008 Cross-view chat | (any) | `ConversationMessage` (with `viewContext`) |
| UC-009 Insights | `Plan` (history), `Meal` | `Insight`, `Household` (mute / frequency), `ConversationMessage` |
| UC-010 Week navigation | `Plan` (all statuses), `Meal`, `Household` | `ConversationMessage` (if chat answers about the viewed week) |
| UC-011 Generate future weeks | `Household`, `Recipe`, `PriceCatalog` | `Plan` (`status = PLANNED`), `Meal`, `ConversationMessage` |
| UC-012 Dynamic report widgets | `meal_history`, `weekly_kpi`, `meal_edit_history` (snapshot tables — see §3a), `ViewPreference` | `ViewPreference` (`settings.query` + `settings.controllerStateB64` per widget), reporting snapshot tables (rewritten on every plan/meal mutation), `ConversationMessage` |
