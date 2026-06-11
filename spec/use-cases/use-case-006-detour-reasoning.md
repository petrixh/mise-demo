# UC-006: Detour reasoning & alternative-store proposals

> Maps to demo **Scenario 6 (Stubbed-price reveal — headline moment)** and **Scenario 7 (AI offers an alternative)**.

---

**As a** home cook, **I want to** ask whether a specific second stop is worth it, and have the assistant propose plan-level alternatives if it isn't **so that** I optimize convenience vs cost on terms I name.

**Status:** Draft
**Date:** 2026-05-13

---

## Main Flow

### Should I bother with the detour?
- I'm on `/shopping`. The recommended store is Prima.
- I ask: *"Should I bother with Lido this week?"*
- The assistant computes the savings of moving Lido-cheaper items off Prima, weighs them against Lido's `detourMinutesFromRoute`, and replies with a reasoned verdict: *"Not really — €3 across two items doesn't justify a second stop."* It names the items and the savings.
- The recommended store does not change automatically — the user is choosing, not the AI.

### Tipping the threshold (the demo's headline)
- A developer edits `demo/data/stores/lido.yaml` and changes salmon from `2.70` to `1.50`. The app restarts; `StubbedPriceCatalog` reloads from disk.
- I ask the same question: *"Should I bother with Lido this week?"*
- The assistant now responds in favor — the new salmon price has crossed the savings threshold. It explains the change in terms of the data.

### "I don't want a second stop, but I want the savings"
- I reply: *"I don't want a second stop, but I want the savings."*
- The assistant proposes a plan-level alternative: swap the relevant meal so the ingredient mix shifts to Prima-favorable options. (E.g., swap Friday's salmon for trout from Prima.)
- This creates a `MealEdit` (per UC-003 rules). The plan, KPIs, and shopping list update.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | The assistant must ground its verdict in actual `StoreItem` prices and `Store.detourMinutesFromRoute` — it cannot invent prices or distances. If data is missing, it says so. |
| BR-02 | The decision threshold (savings worth a detour) is **not** a hard-coded constant. The assistant reasons about it given household preferences (a price-sensitive household tolerates smaller savings). It explains its reasoning in chat. |
| BR-03 | Detour questions do not auto-change the active store recommendation. Changing the recommendation requires either a `Cheapest mix` toggle (UC-005) or an explicit user instruction. |
| BR-04 | When proposing a plan-level alternative, the assistant follows UC-003 rules: produces `MealEdit` rows, respects pinned meals, respects allergies. |
| BR-05 | A "what changed?" mini-report in chat after a detour-driven swap lists: old meal → new meal, why it saves money, and the savings amount. |
| BR-06 | Editing a seed YAML file and restarting the app must produce a visibly different answer to the same question, because `StubbedPriceCatalog` reloads from disk on each startup (it does not cache into H2). This is a teaching requirement, not incidental. |

---

## Acceptance Criteria

- [ ] Asking *"Should I bother with Lido?"* with default seed data produces a "not worth it" verdict that names specific items and amounts.
- [ ] Changing a single price in `demo/data/stores/lido.yaml` (e.g., salmon `2.70` → `1.50`), restarting, and re-asking flips the verdict to "worth it" and the new reasoning cites the changed price.
- [ ] Asking *"I want the savings without the detour"* produces a plan-level swap and a `MealEdit` row that explains the savings.
- [ ] The detour verdict does not change the recommended store unless the user explicitly asks.
- [ ] If a price is missing from all stores for a requested ingredient, the assistant states *"I don't have a price for X"* rather than guessing.

---

## UI / Routes

- The interaction happens entirely in the chat on `/shopping`. The recommended store header may flash a "consider Lido?" hint in *Cheapest mix* mode when an obvious detour is favorable, but the verdict and the explanation live in chat.
- Plan-level alternatives, once accepted, surface on `/plan` exactly as UC-003 edits do (edited pill, undo, why).

| Route | Access | Notes |
|-------|--------|-------|
| `/shopping` | public | Same as UC-005. |
| `/plan` | public | Reflects alternative-swap outcomes. |

---

## Verification

#### Functional

- [ ] *"Should I bother with Lido?"* produces a verdict naming specific items and savings (default data: "not worth it")
- [ ] Editing `stores/lido.yaml` salmon price → restart → re-ask: verdict flips to "worth it" and cites the new price (BR-06 — the demo headline)
- [ ] *"Savings without the detour"* triggers a UC-003 plan swap with a documented `MealEdit`
- [ ] Detour verdicts never auto-change the recommended store (BR-03)
- [ ] Missing price → explicit "I don't have a price for X" (BR-01)

#### Visual

- [ ] *Cheapest mix* mode may flash a "consider Lido?" hint; verdict text lives in chat

#### AI

- [ ] Verdict reasoning references real prices and `detourMinutesFromRoute` values
- [ ] No invented prices or distances

#### Result

- **Status:**
- **Notes:**
