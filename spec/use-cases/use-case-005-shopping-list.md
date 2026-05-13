# UC-005: Shopping list — consolidate, mark pantry, recommend store

> Covers the standard Shopping list view, pantry interaction, and store-mode toggle. Detour reasoning is a separate flow (UC-006).

---

**As a** home cook, **I want to** see a consolidated, aisle-grouped shopping list with a recommended store **so that** I can shop the week in as few stops as possible without overspending.

**Status:** Draft
**Date:** 2026-05-13

---

## Main Flow

- I navigate to `/shopping`.
- I see the shopping list **grouped by aisle** (Produce, Dairy, Pantry, Protein, etc.), with each item showing consolidated quantity (e.g., 600g of carrots across two recipes), unit price at the recommended store, and a check-off control.
- A header strip shows the **recommended store** (Prima by default) with the total cost and a "switch to cheapest mix" toggle.
- I see a **"You already have"** section at the top with my staple pantry items collapsed (olive oil, salt, garlic, etc.) — these are dropped from the active list.
- I can:
  - Tap a list item to mark it as *"already have"* — it moves to the pantry section and stops appearing in lists.
  - Tap the check-off control to mark the item as collected (visual strikethrough; list reflows so unchecked items stay near the top).
  - Toggle **One store** ↔ **Cheapest mix**. In *Cheapest mix*, items split across stores; per-item store labels appear.
  - Ask in chat: *"Add 200g extra cheese"*, *"What's already on hand?"*, *"Why is the list so long this week?"*

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | The list is **derived** from the current active `Plan` + `PantryItem` rows + the `PriceCatalog`. It is not stored in H2. Any change to a meal, pantry, or store catalog reflows the list. |
| BR-02 | Consolidation rules: ingredients with matching `name` and compatible `unit` are summed. Unit-incompatible items remain separate rows. |
| BR-03 | `PantryItem.isStaple = true` items always subtract from the list silently. Non-staple pantry items subtract only if the user explicitly marked them via *"already have"*. |
| BR-04 | Marking an item as *"already have"* creates a `PantryItem` (or increments quantity if matching). It is **not** a staple unless explicitly upgraded. |
| BR-05 | Default store recommendation prefers **fewest stops**. *One store* mode picks the lowest-total-cost single store. *Cheapest mix* picks per-item lowest. The toggle is a `ViewPreference (view = SHOPPING, widgetKey = 'storeMode')`. |
| BR-06 | When in *One store* mode and a meaningful per-item saving exists elsewhere, a small "saves €X.XX at Y" note appears under the item — but the item is not split out. |
| BR-07 | Check-off state is **session-local** (in memory) and resets when the active plan changes. It is not persisted to H2 in the demo. |
| BR-08 | The list reflows in real-time when meals change (UC-003); reflows complete within 2 seconds. |

---

## Acceptance Criteria

- [ ] Visiting `/shopping` with a seeded active plan shows a list of aisles, each with consolidated, priced items.
- [ ] Two recipes that both call for carrots produce a single carrot row with combined quantity in the appropriate unit.
- [ ] Adding olive oil as a staple pantry item suppresses it from the list across all weeks (until the staple flag is removed).
- [ ] Toggling *One store* → *Cheapest mix* changes per-item store labels and rebalances the totals; the toggle survives a page reload (`ViewPreference` persisted).
- [ ] Marking an item as *"already have"* removes it from the active list immediately and adds a `PantryItem` row.
- [ ] Editing a meal on `/plan` updates the shopping list within 2 seconds without a manual refresh.
- [ ] Asking *"what do I already have?"* lists the current pantry contents.

---

## UI / Routes

- **Mobile (< 640px):** the primary form factor for this view — large tap targets, sticky aisle headers, check-off via swipe or tap. Chat is in a `Popover`.
- **Desktop (≥ 1024px):** two-column — list on the left, recommended-store summary + cost breakdown on the right, chat docked.
- The "You already have" section is collapsed by default but expandable.
- Store-mode toggle is a segmented control in the header strip.

| Route | Access | Notes |
|-------|--------|-------|
| `/shopping` | public | `@Route("shopping")`. |

---

## Verification

#### Functional

- [ ] Two recipes sharing an ingredient consolidate to one row in correct unit (BR-02)
- [ ] Staple pantry items hidden (BR-03); non-staple needs explicit *"already have"* (BR-04)
- [ ] Store mode toggle persists to `ViewPreference` and survives reload (BR-05)
- [ ] Plan edit reflows shopping list within 2s (BR-08)
- [ ] Check-off state is session-local; resets on plan change (BR-07)

#### Visual

- [ ] Mobile is primary form factor — large tap targets, sticky aisle headers
- [ ] "You already have" section collapsed by default but expandable
- [ ] One-store mode shows per-item "saves €X at Y" notes where applicable (BR-06)

#### AI

- [ ] *"What do I already have?"* lists actual `PantryItem` rows
- [ ] *"Why is the list so long?"* references actual meal counts / ingredients

#### Result

- **Status:**
- **Notes:**
