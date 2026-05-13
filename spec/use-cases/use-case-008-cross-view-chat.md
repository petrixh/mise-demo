# UC-008: One conversation across all views

> Maps to demo **Scenario 4 (Cross-view chat)** and the "one chat thread that follows me across views" cross-cutting story.

---

**As a** home cook, **I want to** carry one conversation across Plan, Shopping, and Reports **so that** I can say *"go to reports and add a column"* from any view and have it work.

**Status:** Draft
**Date:** 2026-05-13

---

## Main Flow

- I'm on `/plan`. The chat shows my running conversation, including turns from earlier sessions.
- I type *"Go to reports and add a kcal-per-euro column to the leaderboard."*
- The app navigates to `/reports`; the leaderboard column appears; the chat thread is unchanged and includes the same message I just sent plus the assistant's response.
- I navigate back to `/plan` manually. The chat is still the same thread; the scroll position is preserved.
- I close the app, reopen it. The chat thread is still there (persisted via `ConversationService`), and the orchestrator picks up where we left off.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | A single `AIOrchestrator` is scoped per household for the entire app session, and its history is loaded from H2 on first activation and persisted on every turn. |
| BR-02 | The orchestrator's available tools change with the active view: navigating to `/reports` registers `GridAIController`/`ChartAIController` and Report-specific tools; navigating away unregisters them. The system prompt is updated to reflect the current view. |
| BR-03 | Each `ConversationMessage` records `viewContext` = the view the user was on when the message was produced. This is *not* a filter — chat is global — but it powers "what view was I on when I said X?" reasoning. |
| BR-04 | The assistant has a `goToView(view)` tool. Calling it navigates the user's UI to `/plan`, `/shopping`, or `/reports`. The view change is also visible in the conversation as an assistant action. |
| BR-05 | If the user issues a request that requires being in a different view (e.g., adding a Reports column from `/plan`), the assistant **first** navigates, **then** performs the action — both visible in one chat turn. |
| BR-06 | Chat history is paginated when very long. On rehydration, the orchestrator receives a configurable rolling window (default last 50 messages) plus a brief generated "summary so far" for context. |
| BR-07 | When the orchestrator is busy with an in-flight tool call, the chat input remains usable for queueing; the orchestrator processes turns sequentially. |

---

## Acceptance Criteria

- [ ] Sending *"Go to reports and add a kcal-per-euro column"* from `/plan` navigates to `/reports` and adds the column.
- [ ] Manually navigating between Plan / Shopping / Reports leaves the chat thread, content, and scroll position intact.
- [ ] Restarting the app preserves the chat thread (every prior `ConversationMessage` is queryable, last N reloaded into orchestrator state).
- [ ] The orchestrator can answer questions about prior turns ("what did I ask earlier?") within the rolling window.
- [ ] Each `ConversationMessage` row stores the correct `viewContext` based on the active view at message time.
- [ ] No tool that belongs to one view is exposed when the user is on a different view (no leak of `transformChart` when on `/plan`, except via `goToView` + retry).

---

## UI / Routes

- The chat panel is rendered by `MainLayout` and is identical across views. Its mount point doesn't change on navigation.
- On mobile, the chat is in a `Popover` triggered from a persistent FAB present on every view.
- Navigation initiated by the assistant uses standard Vaadin `UI.navigate` so the user sees a normal route change.

| Route | Access | Notes |
|-------|--------|-------|
| any | public | The chat is on every authenticated route in the app. |

---

## Verification

#### Functional

- [ ] *"Go to reports and add kcal-per-euro column"* from `/plan` navigates and applies the change in one chat turn (BR-05)
- [ ] Manual view switching preserves chat content & scroll position
- [ ] Restart preserves chat thread (last N reloaded into orchestrator) (BR-01, BR-06)
- [ ] `ConversationMessage.viewContext` correct per row (BR-03)
- [ ] Tools from view A are not exposed while on view B (BR-02)

#### Visual

- [ ] Chat panel rendered by `MainLayout`, identical position across views
- [ ] Mobile FAB → Popover present on every view

#### AI

- [ ] Assistant-initiated navigation visible in chat
- [ ] Orchestrator can answer "what did I ask earlier?" within the rolling window

#### Result

- **Status:**
- **Notes:**
