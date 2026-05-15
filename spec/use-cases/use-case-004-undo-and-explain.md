# UC-004: Undo an AI change & ask "why?"

> Maps to the "I can see what changed and ask why" cross-cutting story.

---

**As a** home cook, **I want to** undo any AI-driven change and ask for the reasoning behind it **so that** I'm in control and can trust the system's suggestions.

**Status:** Draft
**Date:** 2026-05-13

---

## Main Flow

### Undo
- After an AI edit, I see an "undo" affordance attached to the chat reply *and* near the changed row in the meal grid.
- I either click "undo" or type *"put Thursday's curry back"*.
- The previous meal is restored; a new `MealEdit` row is written documenting the revert; the "edited" pill flips to point at the restored state; KPIs and shopping list reflow.

### Why
- I ask *"why did you swap Thursday?"* (or click a "why?" inline button on the most recently edited row).
- The assistant replies with a grounded explanation: which preferences, allergies, constraints, or budget targets drove the swap; what alternatives it considered and rejected.
- The explanation references concrete data (recipe tags, prices, prep times) — not generic platitudes.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | Every AI-driven change can be undone within the same session. After session end, undo via chat is still possible as long as the relevant `MealEdit` rows exist (kept indefinitely in the demo). |
| BR-02 | "Undo" restores only the most recent `MealEdit` for the target meal. Multi-step undo is via repeated requests, not a stack. |
| BR-03 | Reverting also produces a `MealEdit` (with `changedBy = USER` or `AI` depending on origin) — undo is itself an edit. |
| BR-04 | "Why?" answers are constructed from: the relevant `MealEdit.reason`, the household's stated preferences/allergies, the recipe data, and (for cost-related swaps) the price catalog at the time of change. The model is instructed to refuse to make up reasons. |
| BR-05 | If the user asks "why?" about a change that is *not* the most recent, the assistant first clarifies which change they mean (citing date + meal) rather than guessing. |
| BR-06 | The "why?" answer is brief (≤ 3 sentences for a single swap, ≤ 5 for a multi-meal negotiation) and pragmatic. No apologies, no preamble. |

---

## Acceptance Criteria

- [ ] Clicking "undo" on a recent AI change reverts the meal to its prior `recipeRef` and `servings` and writes a new `MealEdit` documenting the revert.
- [ ] Typing *"put Thursday's curry back"* produces the same result as the undo button when the curry was the prior state.
- [ ] Asking *"why did you swap Thursday?"* returns a response that names at least one concrete factor (preference, allergy, price, prep time, pin status) drawn from `MealEdit.reason` or household state.
- [ ] If `MealEdit.reason` is missing (legacy data or a bug), the assistant explicitly says it doesn't have that reasoning recorded, rather than fabricating one.
- [ ] After undo, the weekly KPIs and shopping list reflect the restored state.

---

## UI / Routes

- "Undo" appears (a) as a compact button under any assistant message that produced an edit, and (b) as an inline action on rows with the "edited" pill, available for the configured window.
- "Why?" appears as an inline "why?" button on rows with the "edited" pill; clicking it pre-fills the chat input with *"why did you change [Day]?"* and submits.
- Both affordances disappear once the change is no longer the most-recent for that meal.

| Route | Access | Notes |
|-------|--------|-------|
| `/plan` | public | UI surfaces for undo + why live here. The "why" capability is reachable from any view via chat. |

---

## Verification

#### Functional

- [ ] Click "undo" reverts the most recent `MealEdit` and writes a new `MealEdit` documenting the revert (BR-03)
- [ ] *"Put X back"* in chat produces the same revert
- [ ] "Why?" answer names ≥ 1 concrete factor from `MealEdit.reason` / household state (BR-04)
- [ ] Missing `MealEdit.reason` → assistant says it doesn't have the reasoning (BR-04, no fabrication)
- [ ] Asking why about a non-most-recent change triggers a clarifying question (BR-05)

#### Visual

- [ ] "Undo" affordance under the assistant message and on the row's "edited" pill
- [ ] "Why?" affordance on the row's "edited" pill; pre-fills chat input and submits

#### AI

- [ ] Explanation length within bounds (≤ 3 sentences single, ≤ 5 negotiation)
- [ ] No apologies or preamble

#### Result

- **Status:**
- **Notes:**
