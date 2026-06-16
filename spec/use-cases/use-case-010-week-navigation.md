# UC-010: Navigate between weeks

> Activates the dormant prev / next controls and "Week of …" pill in the app header (see [`MainLayout.java`](../../src/main/java/com/example/mise/ui/MainLayout.java) `buildHeader`), and rebroadcasts the chosen week to Plan, Shopping, and Reports.

---

**As a** home cook, **I want to** step backward and forward through my weeks — and jump to a specific one via a date picker — **so that** I can review past plans and look ahead at any that are already planned.

**Status:** Draft
**Date:** 2026-05-19

---

## Main Flow

- I open the app and see the header pill *"Week of May 18"* between the prev (◀) and next (▶) chevrons.
- I click **◀**. The pill becomes *"Week of May 11"*, Plan / Shopping / Reports all re-render against that earlier `Plan`, and the chat dock's grounding context now refers to May 11–17.
- I click **◀** again until I am on the oldest `Plan` the household has. The prev button greys out (disabled, `aria-disabled="true"`); ▶ stays enabled.
- I click **▶** repeatedly. When I reach the newest `Plan`, the next button greys out.
- I click the **"Week of …"** pill. A Vaadin `DatePicker` opens beneath it, anchored to the badge. Its calendar grid only highlights/enables dates that fall within a `Plan` the household owns (`min` = oldest plan's Monday, `max` = newest plan's Monday — or, see BR-07, the picker accepts any day in range and snaps to that week's Monday).
- I pick a date. The picker closes, the pill updates to *"Week of {Monday-of-that-week}"*, and Plan / Shopping / Reports recompute against the matching `Plan`.
- At any time, the badge for the household's `ACTIVE` plan (today's real-world week) is visually distinguished from historical / future selections so I always know how to get "home" — clicking the **Mise** wordmark in the header is the shortcut.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | A "viewed week" is always a real `Plan` row owned by the household. The UI never lands on an empty calendar — if there is no `Plan` for a date, that date is not selectable. |
| BR-02 | Prev is disabled iff the viewed `Plan` is the household's oldest plan (lowest `weekStartDate`). Next is disabled iff the viewed plan is the newest plan. Both disabled states set `aria-disabled="true"` and stop the click handler. |
| BR-03 | Plan, Shopping, Reports, and the chat dock's grounding context all read from the **viewed week**, not necessarily the `ACTIVE` plan. Switching weeks updates all four within the same UI round-trip — there is no "stale view" period. |
| BR-04 | The `ACTIVE`-plan invariant from UC-002 BR-01 still holds: viewing a different week is purely a UI state change and never flips `Plan.status`. Only the system promotes a `Plan` to `ACTIVE` (when a new real-world week begins) and demotes the prior one to `HISTORICAL`. |
| BR-05 | The viewed week persists across same-session navigation between `/plan`, `/shopping`, `/reports`, and across a page reload (see UI / Routes for the mechanism). It does **not** persist across server restarts. |
| BR-06 | The chat panel's grounding context (used when answering *"What's on Friday?"* and similar) refers to the **viewed week**, including the week's Monday–Sunday dates and any meals that already exist on it. The full conversation history is unchanged by navigation (per UC-002 BR-06). **Date words resolve accordingly:** weekday names ("Friday", "tue") anchor to the viewed plan's Monday, while calendar-relative words ("today", "tomorrow", "yesterday") stay anchored to the real calendar — they name real days, and tools answer "no meal planned" honestly when those days fall outside the viewed week. (Regression note: found live 2026-06-10 — day names resolved against `LocalDate.now()`'s week, so "What's on Friday?" missed while viewing a past week; fixed in `PlanTools.resolveDate`, covered by `PlanToolsTest.resolveDate_dayName_anchorsToViewedWeek` and `WeekNavigationAIIT`.) |
| BR-07 | The `DatePicker` accepts any calendar day within `[oldestPlan.weekStartDate, newestPlan.weekStartDate + 6 days]`. On selection, the chosen date is snapped to that week's Monday (`with(previousOrSame(MONDAY))`) before resolving the `Plan`. |
| BR-08 | If `findByHouseholdIdAndStatus(ACTIVE)` returns empty (no household yet, pre-onboarding), the prev / next buttons and the pill are non-interactive and show the placeholder *"Week of {current real Monday}"* — same fallback as today's `buildWeekLabel`. |

---

## Acceptance Criteria

- [ ] With three seeded `Plan` rows (oldest `HISTORICAL`, one `HISTORICAL`, current `ACTIVE`) the header opens on the `ACTIVE` plan's week, prev is enabled, next is disabled.
- [ ] Clicking prev twice lands on the oldest plan; prev is now disabled and next is enabled.
- [ ] Clicking the pill opens a `DatePicker` whose `min` equals the oldest plan's `weekStartDate` and `max` equals the newest plan's `weekStartDate + 6` days.
- [ ] Picking a Wednesday from the picker resolves to that week's Monday and the pill renders *"Week of {that Monday formatted}"*.
- [ ] Picking a date inside the same week as the currently-viewed plan is a no-op (no re-render flicker).
- [ ] After switching to an older week, `/plan`'s KPI strip, meal grid, and category chart all reflect that older plan's meals; `/shopping` shows that week's list; `/reports` rolls up that week's stats.
- [ ] After switching to an older week and asking the chat *"What's on Friday?"*, the response names the Friday meal of the **viewed** week, not the active one.
- [ ] Reloading the page restores the previously viewed week (URL query param survives reload).
- [ ] Pre-onboarding (no household): header shows the placeholder label, prev / next are disabled, pill click is a no-op.
- [ ] Keyboard: tabbing reaches prev → pill → next in that order; ◀/▶ respond to Enter and Space; the pill opens the picker on Enter; Escape closes the picker without changing the viewed week.

---

## UI / Routes

- **No new routes.** The existing `/plan`, `/shopping`, `/reports` routes each gain an optional `?week=YYYY-MM-DD` query parameter — value is the viewed week's Monday. When absent, the view falls back to the `ACTIVE` plan.
- **Why a URL param, not a session bean:** survives reload, shareable, and avoids cross-tab interference. The `MainLayout` reads it on every navigation event and updates the badge; views observe the same param through `BeforeEnterEvent`.
- **DatePicker formatting** — the open issue called out in the task:
  - The Vaadin `DatePicker` renders the selected value as a plain date string inside its own input field; it cannot natively prefix the value with literal text like *"Week of "*. The clean solution is to keep the **pill as the displayed label** (it already says *"Week of May 18"*) and let the `DatePicker` open as a **dropdown overlay** triggered by clicking the pill — the picker's own input field is hidden (`display: none`) and only its calendar overlay is shown.
  - Fallback if the overlay-only pattern fights Vaadin's component: drop the *"Week of "* prefix and show the badge as just the date (e.g. *"May 18"*) using the `DatePicker` directly with `setHelperText("Week of")` or an adjacent `<span>` prefix.
- **Visual state of the badge**:
  - When the viewed week is the `ACTIVE` plan: default badge styling (current `.mise-week-badge`).
  - When the viewed week is `HISTORICAL`: add `.mise-week-badge--past` (muted text, dotted underline) so the user knows they're looking backward.
  - When the viewed week is a future / `PLANNED` plan (introduced by the follow-up UC noted below): add `.mise-week-badge--future` (italic or accent border).
- **Enable the buttons.** Today, `MainLayout.java:234,244` calls `setEnabled(false)`. Those calls go away; the comment block at `MainLayout.java:230–231` is replaced with a real binding to a `ViewedWeekService` (or equivalent helper) that reads the URL param and the household's plans.

| Route | Access | Notes |
|-------|--------|-------|
| `/plan?week=YYYY-MM-DD` | public | Optional param; defaults to `ACTIVE` plan's Monday when absent. |
| `/shopping?week=YYYY-MM-DD` | public | Same semantics. |
| `/reports?week=YYYY-MM-DD` | public | Same semantics. |

---

## Verification

**Verified by:**
**Date:**

#### Functional

- [ ] Main flow works end-to-end with ≥ 3 seeded plans
- [ ] BR-01..BR-08 enforced
- [ ] Prev / next disable correctly at boundaries (both visual and `aria-disabled`)
- [ ] DatePicker `min` / `max` match the oldest / newest plan
- [ ] Wednesday-selection snaps to that week's Monday (BR-07)
- [ ] `?week=` survives reload (BR-05)
- [ ] Pre-onboarding fallback (BR-08) renders without errors

#### Visual

- [ ] Prev / next buttons match Aura ghost-icon style and the existing `.mise-header-week-nav-btn` rules; disabled state is unambiguous
- [ ] Pill remains visually a pill (badge) when the picker is closed; the picker overlay aligns under the pill on desktop and is full-width on mobile
- [ ] `.mise-week-badge--past` and `.mise-week-badge--future` modifiers are clearly distinct from the default (active) state
- [ ] Keyboard focus ring is visible on prev, pill, next
- [ ] Responsive at 390 (mobile) and 1920 (desktop): on mobile the picker overlay does not clip off-screen

#### AI

- [ ] *"What's on Friday?"* asked while viewing an older week names that week's Friday meal, not the active week's
- [ ] *"How much did last week cost?"* asked while viewing the current week returns the previous `Plan`'s cost, grounded in stored `Meal` rows (no fabrication)

#### Result

- **Status:**
- **Notes:**

---

## Follow-ups (not in this UC)

- **UC-011 (proposed): AI-generated future weeks.** "Generate next week", "generate the rest of May", "plan June" — the assistant creates one or more `Plan` rows with a new `Status.PLANNED` (or extends the enum), populated with meals via the existing meal-generation tooling. Once UC-011 lands, the navigation built here automatically lets the user step forward into those planned weeks, and the `.mise-week-badge--future` modifier defined above becomes load-bearing.
- The `Plan.Status` enum currently has only `ACTIVE` and `HISTORICAL` (see [`Plan.java:14`](../../src/main/java/com/example/mise/domain/plan/Plan.java)). UC-011 will need to add `PLANNED`; this UC does **not** require that change — it only navigates between plans that already exist.
