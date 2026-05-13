# UC-003: Edit the weekly plan via natural language

> Maps to demo **Scenario 2 (Single-meal swap)**, **Scenario 3 (Constraint negotiation)**, and the "pin a meal" user story.

---

**As a** home cook, **I want to** modify my week by talking to the assistant **so that** I can adjust meals without juggling forms, and so I can negotiate trade-offs in plain language.

**Status:** Draft
**Date:** 2026-05-13

---

## Main Flow

### Single swap
- I type *"Make Thursday vegetarian, kid is having a friend over."*
- The assistant proposes a specific replacement, briefly explains why ("matches your kid-friendly tag, under 30 min prep, fits the existing pantry"), and applies it.
- Thursday's row updates in the grid with an "edited" pill; the weekly KPIs adjust; the cost-by-category chart's Protein segment shrinks; the shopping list reflows.

### Constraint negotiation
- I type *"Get this week under €80 without dropping the Thursday curry."*
- The assistant applies multiple swaps, narrates the trade-offs in one concise turn (*"swapped salmon for mackerel, cut the Saturday cheese plate; kept Thursday's curry"*), and updates the grid all at once.
- The KPI strip total now reads `< €80`. Each changed row carries an "edited" pill.

### Pin a meal
- I type *"Pin Saturday — I'm hosting"*, or click the pin icon on Saturday's row.
- The meal becomes `pinned`. Subsequent chat edits to "the week" do not touch it.
- The assistant confirms in chat ("Saturday's pinned — won't touch it.").

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | Every AI mutation produces a `MealEdit` row capturing the previous `recipeRef`, `servings`, `status`, the timestamp, and the assistant's free-text `reason`. |
| BR-02 | Allergies (`Household.allergies`) are hard constraints — the model is instructed and tooled such that it cannot produce a `Meal` containing an allergic ingredient. Hated foods are soft — avoid but overridable if the user names them. |
| BR-03 | `Meal.pinned = true` excludes that meal from any tool-driven edit. The assistant must surface this as an explanation if the user asks for a change that would touch a pinned meal. |
| BR-04 | Multi-meal edits (constraint negotiation) are atomic from the user's perspective — either all proposed changes apply, or none do. Tool errors must roll back partial changes. |
| BR-05 | Free-text notes the user attaches to a request (e.g., "kid has friend over") may be stored on `Meal.note` if relevant to the meal, so future "why?" questions can reference them. |
| BR-06 | The assistant's chat response **summarizes** the changes; it does not narrate intent ("I will now…"). Tone: warm, brief. |
| BR-07 | If a request is infeasible (e.g., "under €30 this week"), the assistant proposes the closest feasible outcome and explains the gap rather than failing silently. |
| BR-08 | After any edit, `WeeklyStats`, the cost-by-category chart, and the derived shopping list are recomputed and pushed to all open views within 2 seconds (typical) / 5 seconds (multi-constraint negotiation). |

---

## Acceptance Criteria

- [ ] *"Make Thursday vegetarian"* replaces Thursday's meal with a vegetarian option and produces exactly one `MealEdit` row.
- [ ] *"Get the week under €80 without dropping Thursday's curry"* results in a total weekly cost ≤ €80 and Thursday's curry unchanged.
- [ ] Pinning a meal and then asking *"replan the week"* leaves the pinned meal untouched and the assistant mentions the pin in its response.
- [ ] An allergic ingredient (e.g., shellfish if shellfish is in `allergies`) never appears in any AI-proposed meal, including under explicit user prompting.
- [ ] If the assistant cannot meet a constraint, its chat reply explains the gap (e.g., "best I could do is €82 — dropping the curry would get us to €74").
- [ ] All changed `Meal` rows carry an "edited" pill on `/plan` for the configured window after the change.
- [ ] Asking *"why?"* immediately after a change returns an answer grounded in `MealEdit.reason` plus stated preferences (see UC-004).

---

## UI / Routes

- All edits originate from the chat input on the Plan view; the meal grid responds reactively.
- Pin/unpin is available both via chat and as an inline icon button (sole exception to the "no inline editing" rule from UC-002).
- "Edited" pill is identical styling to UC-002 BR-04.
- Progressive feedback in chat: for multi-constraint requests, the assistant streams an initial acknowledgement before structured changes apply.

| Route | Access | Notes |
|-------|--------|-------|
| `/plan` | public | Same view as UC-002; mutation paths are tool calls invoked by the orchestrator. |

---

## Verification

#### Functional

- [ ] Single swap produces exactly one `MealEdit`, the right row flips, KPIs & shopping list update
- [ ] Constraint negotiation result respects all named constraints atomically (BR-04)
- [ ] Pin prevents edits to that meal (BR-03)
- [ ] Infeasible request returns a "best I could do" explanation (BR-07)
- [ ] Allergic ingredients never appear in any AI-proposed meal, even under explicit prompting (BR-02)

#### Visual

- [ ] All changed rows show "edited" pill for the configured window
- [ ] Pin icon visible on every row; toggles state correctly

#### AI

- [ ] `MealEdit.reason` populated with a non-empty, plausible justification
- [ ] No fabricated kcal / cost values in chat replies (cross-check against the recipe & price catalog)
- [ ] Single-swap latency ≤ 2s; negotiation ≤ 5s with progressive feedback

#### Result

- **Status:**
- **Notes:**
