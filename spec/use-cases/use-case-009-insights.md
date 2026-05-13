# UC-009: Unprompted insights & their controls

> Maps to the "occasional unprompted insights" cross-cutting story.

---

**As a** home cook, **I want to** receive an occasional unprompted observation when the system notices something worth my attention **so that** I get useful nudges without being nagged.

**Status:** Draft
**Date:** 2026-05-13

---

## Main Flow

- Periodically (or on specific triggers — see BR-04), the system generates an `Insight` and surfaces it in a non-intrusive banner above the main view content.
  *e.g., "Your cheaper weeks all had three vegetarian dinners — worth locking in?"*
- I can:
  - **Dismiss** the insight (mark `dismissed = true`, banner disappears).
  - **Act on it** by replying in chat (*"yes, lock that in"*) — the assistant takes the suggested action through normal plan-edit tools (UC-003).
  - **Mute insights** in chat (*"mute insights"* or *"insights only weekly"*).
- Muted state and frequency are stored on `Household.insightsMuted` and `Household.insightFrequency`.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | Insights are **advisory** — they never auto-apply changes. Any action requires user confirmation. |
| BR-02 | At most **one undismissed insight** is visible at any time. New insights queue; the user sees the next one only after dismissing the current one. |
| BR-03 | An insight must be grounded in concrete `Plan` / `Meal` history (`evidenceRefs` populated). Insights with no evidence are not generated. |
| BR-04 | Triggers for insight generation: (a) at app startup if more than 7 days since the last insight; (b) after a plan is finalized (new week activated); (c) on explicit user request *"give me an insight"*. |
| BR-05 | If `Household.insightsMuted = true`, no insights are surfaced — but they may still be generated and stored so the user can browse "insights I missed" via chat. (`InsightService` keeps them; the UI hides them.) |
| BR-06 | Insight tone matches overall AI tone (warm, pragmatic, brief). Insights phrased as questions are preferred over directives. |
| BR-07 | Dismissing an insight does not delete it; it is retained for historical context (e.g., the model can avoid suggesting the same thing twice in a short window). |

---

## Acceptance Criteria

- [ ] On app start after the trigger window has elapsed, an insight banner appears above the active view's main content.
- [ ] Dismissing the banner sets `Insight.dismissed = true` and removes the banner without showing a replacement immediately.
- [ ] *"Mute insights"* in chat sets `Household.insightsMuted = true`. No new banners appear afterward; existing ones disappear on next render.
- [ ] *"Show me insights I missed"* lists undismissed (or all) insights even while muted.
- [ ] Acting on an insight via chat (*"lock that in"*) triggers a UC-003 plan edit with the appropriate `MealEdit` row.
- [ ] An insight's `evidenceRefs` list references real `Plan` / `Meal` IDs that exist in H2.

---

## UI / Routes

- The insight banner is rendered by `MainLayout` above the routed view content. It is **sticky-top on mobile**, **inline at the top on desktop**.
- The banner has: insight text, an "act on it" prompt that pre-fills the chat input, and a dismiss `×`.
- No insights are shown in `/welcome`.

| Route | Access | Notes |
|-------|--------|-------|
| any | public | Banner is part of `MainLayout`. |
