# UC-009: Unprompted insights & their controls

> Maps to the "occasional unprompted insights" cross-cutting story.

---

**As a** home cook, **I want to** receive an occasional unprompted observation when the system notices something worth my attention **so that** I get useful nudges without being nagged.

**Status:** Draft
**Date:** 2026-05-13

---

## Main Flow

- Periodically (or on specific triggers — see BR-04), the system generates an `Insight` and surfaces it in a non-intrusive callout inside the active view's panel.
  *e.g., "Your cheaper weeks all had three vegetarian dinners — worth locking in?"*
- I can:
  - **Dismiss** the insight (mark `dismissed = true`, the callout disappears).
  - **Act on it** by replying in chat (*"yes, lock that in"*) — the assistant takes the suggested action through normal plan-edit tools (UC-003). The callout exposes an inline "Act on it" pill that pre-fills this for me.
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

- [ ] On app start after the trigger window has elapsed, an insight callout appears in the active view's panel.
- [ ] Dismissing the callout sets `Insight.dismissed = true` and removes it without showing a replacement immediately.
- [ ] *"Mute insights"* in chat sets `Household.insightsMuted = true`. No new callouts appear afterward; existing ones disappear on next render.
- [ ] *"Show me insights I missed"* lists undismissed (or all) insights even while muted.
- [ ] Acting on an insight via chat (*"lock that in"*) triggers a UC-003 plan edit with the appropriate `MealEdit` row.
- [ ] An insight's `evidenceRefs` list references real `Plan` / `Meal` IDs that exist in H2.

---

## UI / Routes

The insight callout lives **inside the view panel**, not as a top banner. Each view picks the spot inside its own panel that makes the insight feel like an annotation of what the user is already looking at:

- **Plan** — bottom of the cost-by-category sidebar (or stacked below the meal list on mobile), beneath the local cost-summary line. Dismissable + actionable.
- **Reports** — bottom of the panel, beneath the per-meal leaderboard. Quiet annotation flavour (label + bulb icon + body, no dismiss).
- **Shopping** — temporarily still uses the legacy top-of-view banner from `MainLayout` until it grows its own in-panel insights area. That banner is hidden on `/plan` and `/reports` because those views now handle insights themselves.
- **`/welcome`** — no insights at all.

See the design system's "AI insight callout" entry for shape and tone. Each view exposes an inline "Act on it" pill where applicable, which submits a derived phrase (e.g. "lock in 3 vegetarian dinners this week") to the shared orchestrator via `MainLayout.submitChatMessage`.

| Route | Access | Notes |
|-------|--------|-------|
| /plan | public | Callout in the cost-by-category sidebar. |
| /reports | public | Callout at the bottom of the Reports panel. |
| /shopping | public | Legacy top banner via `MainLayout` until Shopping grows its own area. |
| /welcome | public | No insights shown. |

---

## Verification

#### Functional

- [ ] Callout appears at startup after trigger window elapses (BR-04)
- [ ] Dismiss sets `Insight.dismissed = true`; no replacement appears immediately (BR-02)
- [ ] *"Mute insights"* sets `Household.insightsMuted = true`; no new callouts (BR-05)
- [ ] Acting on an insight (*"lock that in"*) triggers a UC-003 plan edit with a `MealEdit`
- [ ] `Insight.evidenceRefs` reference real `Plan` / `Meal` IDs (BR-03)

#### Visual

- [ ] Callout renders inside the active view panel (Plan sidebar, Reports below leaderboard), not as a top-of-view banner
- [ ] Plan callout is dismissable + actionable; Reports callout is quiet (no dismiss)
- [ ] No insights on `/welcome`

#### AI

- [ ] Insight text is concrete (cites real meals or weeks), not generic
- [ ] Phrased as a question (preferred per BR-06)

#### Result

- **Status:**
- **Notes:**
