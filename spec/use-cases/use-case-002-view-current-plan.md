# UC-002: View the current week's plan

> Maps to demo **Scenario 1 (Cold open)**.

---

**As a** returning home cook, **I want to** open the app and immediately see a coherent week of dinners with rolled-up stats **so that** I never face an empty screen.

**Status:** Draft
**Date:** 2026-05-13

---

## Main Flow

- I open the app at `/` (or `/plan` directly).
- I see, simultaneously: a **weekly KPI strip** (total cost, total prep time, average kcal, dietary mix), a **meal grid** with one row per day from Monday to Sunday, a **cost-by-category** mini-chart, and the persistent **chat panel** with my prior conversation history.
- Each meal row shows the meal name, the recipe's main category tag, prep time, kcal, and estimated cost.
- Meals recently changed by the AI carry an "edited" pill for a short period.
- I can ask the chat *"Why is Thursday's curry so expensive this week?"* or *"What's on Friday?"* and receive a grounded answer.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | Exactly one `Plan` row with `status = ACTIVE` exists per household at any time. The Plan view always renders this plan. |
| BR-02 | The grid has 7 rows; if `Meal` rows are missing for any date, the grid shows an "empty slot" placeholder that is recoverable through chat ("fill the gaps"), not a generation form. |
| BR-03 | Weekly stats are computed from the meals currently shown, the recipes they reference, and the **current** price catalog. They update whenever a meal changes. |
| BR-04 | The "edited" pill is shown on any `Meal` with `lastEditedBy = AI` and `lastEditedAt` within the last 60 seconds *or* within the current chat session, whichever is longer. |
| BR-05 | The view is read-only via direct UI interaction (no inline editing); all mutation happens through the chat. The only direct affordances are: pin/unpin a meal, mark as cooked/skipped, view recipe details. |
| BR-06 | The chat panel shows the same `ConversationMessage` history as Shopping and Reports — switching views does not reset chat. |

---

## Acceptance Criteria

- [ ] Loading `/plan` with a seeded household renders the KPI strip, 7-row meal grid, and chat panel within 2 seconds (excluding LLM latency).
- [ ] Weekly cost in the KPI strip equals the sum of per-meal estimated costs derived from the current price catalog (Prima by default).
- [ ] Editing a store YAML file's price for an ingredient used this week and restarting the app changes the weekly cost KPI accordingly.
- [ ] Asking *"What's on Friday?"* returns the Friday meal's name and a one-line description grounded in the recipe data.
- [ ] Pinning a meal sets `Meal.pinned = true` and the pin is visible after a page reload.
- [ ] Switching to `/shopping` and back to `/plan` shows the same chat thread, in the same scroll position, with no message lost.

---

## UI / Routes

- **Desktop (≥ 1024px):** three-column layout — left: chat (docked drawer), center: meal grid, right: KPI strip + cost-by-category chart.
- **Tablet (640–1023px):** two-column — chat as a collapsible side drawer; meal grid + KPIs stacked in the main area.
- **Mobile (< 640px):** single-column — KPI strip at top (horizontally scrollable chips), meal grid below, chat in a `Popover` triggered by a floating chat button.
- "Edited" pill uses Aura's badge styling; pin uses an icon button on each row.

| Route | Access | Notes |
|-------|--------|-------|
| `/` | public (single-user demo) | Redirects to `/plan`. |
| `/plan` | public | `@Route("plan")`. Default landing view after onboarding. |
