# UC-011: Generate future weeks via natural language

> Extends UC-010 (week navigation) by giving the user a way to *populate* the weeks they can navigate into. The assistant creates one or more future `Plan` rows on request, honoring the same household constraints as UC-001 / UC-003.

---

**As a** home cook, **I want to** ask Mise to plan upcoming weeks in plain language — *"plan next week"*, *"plan the rest of May"*, *"do June"* — **so that** I can look ahead and adjust before the real-world week begins, without filling out a form per week.

**Status:** Draft
**Date:** 2026-05-19

---

## Main Flow

### Generate one week ahead
- I type *"Plan next week."* in the chat.
- The assistant calls the planning tool, generates one new `Plan` with `status = PLANNED` for the Monday after the current `ACTIVE` plan's week (or, if I already have planned weeks, the Monday after the latest of those), and confirms in one short turn — *"Planned the week of May 25 — 7 dinners, est. €87, no shellfish."*
- The **next** chevron in the header (UC-010) now lights up; clicking it takes me to the newly-planned week, which renders with the `.mise-week-badge--future` styling.

### Generate a date range
- I type *"Plan the rest of May."* (current viewed week is May 18; today is May 19.)
- The assistant resolves the range to *"Monday May 25 only — that's the one remaining Monday in May"*, generates that `Plan`, and confirms.
- If I say *"Plan June"* it resolves to *"Mondays June 1, 8, 15, 22, 29 — 5 weeks"* and generates them in one tool call.
- The chat reply summarizes counts + budget envelope, e.g. *"Planned 5 weeks of June. Each within €90 (range €82–€91). Same allergy / hate filters as your active week."*

### Already-planned weeks are skipped
- I previously asked for *"plan June"*. Now I type *"plan the next four weeks"* and three of those four are already planned.
- The assistant generates only the **one missing** week and tells me the rest were already done — *"Planned June 29 (the other three were already on your calendar)."*

### Refuse the impossible
- I type *"plan last week"* or *"plan the week of April 6"* (already in the past).
- The assistant refuses politely and explains: *"I can only plan weeks from {next Monday} onward — past weeks are already history. Want me to look at them with you instead?"*

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | A new `Plan.Status.PLANNED` value is introduced. Plans created by this UC are written with `status = PLANNED`. `ACTIVE` continues to mean "today's real-world week"; `HISTORICAL` means "past". |
| BR-02 | Planning only moves forward in time. The tool refuses any `weekStartDate` < (the Monday following the current `ACTIVE` plan's week). For a fresh household where the `ACTIVE` plan covers the current week, that is "next Monday or later". |
| BR-03 | Planning is **idempotent per week**. If a `Plan` already exists for a target Monday (any status), it is left untouched and the assistant reports it as "already planned". The tool never overwrites or duplicates. |
| BR-04 | Each generated week uses the **same eligible-recipe pipeline** as `PlanService.generateActivePlan`: hard allergy filter from `Household.allergies`, soft avoid of `hatedFoods`, 7 distinct dinners, `Meal.status = PLANNED`, `Meal.Editor = AI` on `lastEditedBy`. Budget is **best-effort**, not a hard constraint (matches `generateActivePlan` behavior today). |
| BR-05 | A single tool call may generate **at most 8 weeks**. Larger requests (*"plan the next quarter"*) are partially fulfilled (first 8 weeks) and the assistant says so and offers to continue. This bounds latency and avoids runaway generation. |
| BR-06 | When the real-world week rolls over and the current date enters a week that has a `PLANNED` plan, that plan is promoted to `ACTIVE` and the prior `ACTIVE` plan is demoted to `HISTORICAL`. Promotion happens **on demand** — the first call into `findActivePlan` after a date rollover triggers the sweep — not via a scheduled job. The invariant *"exactly one ACTIVE plan per household"* (UC-002 BR-01) is preserved at all times. |
| BR-07 | Generated plans inherit the household's constraints **as of generation time**. If the household's allergies / budget / size change later, existing `PLANNED` rows are not retroactively regenerated; the user can edit them via UC-003 chat. |
| BR-08 | The chat reply summarizes the generated weeks: count, date range, estimated cost range, and any noteworthy filter outcomes ("no shellfish, no salmon"). It does **not** narrate intent or tool plumbing — same tone rules as UC-003 BR-06. No fabricated cost / kcal values; numbers come from the `PriceCatalog` and `Recipe` rows that were actually picked. |
| BR-09 | All chat messages from this flow are persisted as `ConversationMessage` rows (per UC-008). No view context change — the user can be on any view when they ask. |

---

## Acceptance Criteria

- [ ] *"Plan next week"* creates exactly one new `Plan` row with `status = PLANNED` and `weekStartDate` = (current ACTIVE plan's Monday + 7 days); 7 `Meal` rows are written under it.
- [ ] *"Plan the rest of May"* on a date like 2026-05-19 (current ACTIVE = week of May 18) creates exactly one new `Plan` for the Monday of May 25.
- [ ] *"Plan June"* on the same date creates 5 new `Plan` rows (June 1, 8, 15, 22, 29), each with 7 meals.
- [ ] Repeating *"Plan June"* a second time creates zero new rows; the assistant reports "already planned".
- [ ] *"Plan last week"* / any past-week request creates zero rows; the assistant explains the refusal.
- [ ] A request for *"the next 12 weeks"* generates exactly 8 weeks (BR-05 cap), the assistant says so, and offers to continue.
- [ ] An allergy-blocked ingredient (e.g., shellfish in `Household.allergies`) never appears in any meal of any generated `PLANNED` week.
- [ ] After a generated `PLANNED` week becomes the current week (by date rollover), `findActivePlan(householdId)` returns it and the prior `ACTIVE` plan is now `HISTORICAL` — verified by jumping the system clock or seeding a `PLANNED` plan for the current Monday and reading state.
- [ ] Generating ≤ 4 weeks completes within 3 seconds total (excluding model thinking); 8 weeks within 6 seconds.
- [ ] After successful generation, the UC-010 **next** chevron is enabled and clicking it navigates to the new week with `.mise-week-badge--future` applied.
- [ ] The chat reply for a multi-week generation contains an estimated-cost range that matches the sum-of-priced-meals computation for each generated plan (no fabrication).

---

## UI / Routes

- **No new routes; no new views.** All interaction happens in the chat dock; the visible side effect is on the header (UC-010 chevrons enable / disable) and on the Plan / Shopping / Reports views once the user navigates into a planned week.
- **No confirmation dialog.** Generation is reversible — the user can edit individual meals via UC-003, or (future UC) delete a planned week. A modal here would slow the demo flow.
- **Progressive feedback in chat:** for ≥ 3-week generations, the assistant streams a short *"Planning 5 weeks…"* acknowledgement before the structured summary. Same pattern as UC-003 multi-constraint negotiation.
- **Plan toolbox addition.** A new `@Tool`-annotated method on a new bean (or on `PlanTools` directly):

  ```java
  @Tool(description = "Generate one or more future weekly plans, starting from a given Monday. " +
                      "Refuses past or current weeks; skips weeks that already exist. " +
                      "Honors household allergies (hard) and hated foods (soft). " +
                      "Generates at most 8 weeks per call.")
  String planFutureWeeks(
      @ToolParam(description = "First Monday to plan (ISO date, e.g. 2026-05-25). Must be > current ACTIVE plan's Monday.") String fromMonday,
      @ToolParam(description = "Inclusive last Monday to plan (ISO date). For a single-week request, equal to fromMonday.") String throughMonday)
  ```

  The matching `PlanService` API:

  ```java
  @Transactional
  public List<Plan> generatePlannedWeeks(Household household, LocalDate fromMonday,
                                         LocalDate throughMonday, RecipeCatalog catalog);
  ```

  Reuses the existing private `generatePlan(..., Plan.Status.PLANNED, Meal.Status.PLANNED)` path.
- **Status-promotion hook.** A small helper called from `PlanService.findActivePlan` (or a `@PostConstruct` + a method invoked at the top of the chat round-trip): if `ACTIVE.weekStartDate + 7 days <= today` **and** a `PLANNED` plan exists whose `weekStartDate <= today < weekStartDate + 7`, swap statuses inside one transaction. Otherwise no-op.

| Route | Access | Notes |
|-------|--------|-------|
| `/plan` / `/shopping` / `/reports` | public | Same routes as UC-010. After generation, `?week=YYYY-MM-DD` can target any new `PLANNED` week. |

---

## Verification

**Verified by:**
**Date:**

#### Functional

- [ ] Main flow (single week / range / month) creates the expected number of `Plan` rows with `status = PLANNED`
- [ ] BR-01..BR-09 enforced
- [ ] Idempotence: re-asking the same range creates zero new rows (BR-03)
- [ ] Past-week refusal returns no DB writes (BR-02)
- [ ] 8-week cap is honored on large requests (BR-05)
- [ ] Date-rollover promotion of `PLANNED` → `ACTIVE` works without violating UC-002 BR-01 (BR-06)
- [ ] Allergy / hate filters applied per BR-04

#### Visual

- [ ] After generation, UC-010 next chevron lights up; pill carries `.mise-week-badge--future` on the new weeks
- [ ] DatePicker `max` (UC-010) extends to cover newly planned weeks
- [ ] Chat reply renders as a single concise turn — no tool-plumbing leakage in the message text

#### AI

- [ ] Cost / kcal values in the chat summary match `sum(priced meals)` of the generated plans (no fabrication)
- [ ] Tool call uses ISO dates that resolve to the **Monday** of each target week (no off-by-one)
- [ ] *"Plan the rest of May"* and *"Plan June"* resolve to the right Mondays for the current real date
- [ ] No `PLANNED` plan ever contains an allergic ingredient, even under adversarial prompting (*"add shellfish to next week's Wednesday"* must still be blocked by UC-003 BR-02 tooling)
- [ ] Multi-week generation latency ≤ 6 s for 8 weeks, ≤ 3 s for ≤ 4 weeks
- [ ] Conversation history (UC-008) shows the request and the assistant's summary after reload

#### Result

- **Status:**
- **Notes:**

---

## Out of scope (for this UC)

- **Deleting / cancelling a planned week.** No *"drop next week"* tool — the user can clear meals individually via UC-003. Bulk delete is a candidate follow-up.
- **AI-driven *editing* of multiple weeks at once** (*"make next month vegetarian"*). UC-003 already covers single-week edits; multi-week edit is a follow-up that would reuse the same plan-edit tools across a date range.
- **Background regeneration when the household profile changes.** Per BR-07, profile changes do not retroactively regenerate `PLANNED` weeks.
- **Calendar-aware planning** (skip a week because the household is travelling, etc.). Worth a follow-up but adds significant scope around household calendar / context modeling.
