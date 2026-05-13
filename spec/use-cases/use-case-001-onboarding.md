# UC-001: Onboarding — chat-driven first run

> Maps to demo scenarios: setup for **Scenario 1 (Cold open)**.

---

**As a** new home cook, **I want to** describe my household in a short chat exchange **so that** I immediately see a generated week of dinners without filling out a multi-step form.

**Status:** Draft
**Date:** 2026-05-13

---

## Main Flow

- I open the app for the first time. There is no plan yet, so the system routes me to `/welcome`.
- I see a single chat panel and a friendly opening line from the assistant: *"Hi — I'll set up your dinners. Quick: how many of you eat at home, anything you can't or won't eat, and a rough weekly budget?"* No form fields, no progress bar.
- I reply in natural language (e.g., *"Two adults, no fish for me, around €90 a week, occasional Saturday hosting"*).
- The assistant asks at most two clarifying follow-ups if anything material is missing (e.g., *"Allergies I should hard-block, or just preferences?"*).
- Within a few seconds I see a confirmation message in chat and the app navigates me to `/plan` with a populated week, the weekly KPIs, the cost-by-category strip, and the same chat continuing alongside.
- The shopping list and reports views are also populated when I visit them.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | If `Household` exists in H2 already, skip onboarding — go straight to `/plan`. |
| BR-02 | The active persona (`demo/data/active_persona.txt`) is used as the **seed** for the `Household` row; the user's chat answers override persona fields, not the other way around. |
| BR-03 | Onboarding cannot complete with empty `weeklyBudget`, missing `size`, or both `allergies` and `hatedFoods` left blank for *all* asked questions. The assistant must ask a follow-up rather than guessing. |
| BR-04 | At most **3 turns** are taken before generating a plan. Anything not captured can be refined later in conversation. |
| BR-05 | Allergies the user states are **hard constraints** — the planner cannot violate them. Hated foods are **soft constraints** — avoided but overridable on explicit request. |
| BR-06 | On completion, the system seeds **4 historical plans** (per persona `seedWeeks`) so Reports has something to display, plus the current active plan. |
| BR-07 | All chat messages from onboarding are persisted as `ConversationMessage` rows with `viewContext = ONBOARDING` and remain visible in chat history afterwards. |

---

## Acceptance Criteria

- [ ] First app launch routes to `/welcome` and shows the chat panel only.
- [ ] Entering household details in natural language produces a populated `/plan` within 10 seconds (model latency permitting).
- [ ] On reload after onboarding completes, the app routes directly to `/plan` and shows the same plan, KPIs, and the prior chat thread.
- [ ] Allergies stated during onboarding are reflected in `Household.allergies` and no meal in the seeded plan or history contains a blocked ingredient.
- [ ] The Reports view shows ≥ 4 weeks of history immediately after onboarding.
- [ ] Restarting the app (stopping and restarting the JVM) does not lose any onboarding state.

---

## UI / Routes

- Mobile-first single-column layout: header with brand, full-height chat (MessageList + MessageInput), no nav drawer or bottom bar until onboarding completes.
- Initial assistant message is pre-seeded; the user's text field has autofocus.
- No back button, no "skip" affordance — the assistant is the only way through.

| Route | Access | Notes |
|-------|--------|-------|
| `/welcome` | public (single-user demo) | `@Route("welcome")`. Redirects to `/plan` if `Household` row exists. |
