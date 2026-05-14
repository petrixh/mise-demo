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
| BR-08 | The chat panel is a **two-state dock**: collapsed (input pill + single-line preview of the latest assistant reply) when blurred, expanded (scrollable message-history window above the input pill) on `:focus-within`. The transition is animated; both states keep the `MessageList` mounted in the DOM so the orchestrator can stream into it regardless of UI state. |
| BR-09 | On expand, the chat history auto-scrolls to the **latest** message so the user sees the most recent turn first. The scroll-to-latest fires both immediately on focus and again after the expansion transition completes (so it lands once the container has its final height). This is a per-expand reset; it deliberately overrides the cross-navigation scroll-position preservation in the "Manual view switching" acceptance criterion when the user blurs and re-focuses the dock. |
| BR-10 | While the orchestrator is generating a reply, the chat panel shows a visual **"AI working" indicator**: in the collapsed state, the wand icon in the single-line preview shimmers and shifts to the info-blue accent; in the expanded state, the in-progress assistant message's avatar carries a pulsing glow ring. The indicator is set on user submit and cleared when the orchestrator's `responseCompleteListener` fires. This is feedback, not state — it does not change the input's queueing semantics from BR-07. |

---

## Acceptance Criteria

- [ ] Sending *"Go to reports and add a kcal-per-euro column"* from `/plan` navigates to `/reports` and adds the column.
- [ ] Manually navigating between Plan / Shopping / Reports leaves the chat thread and content intact. Scroll position within the chat is preserved across navigation **while the dock stays focused**; an intentional blur-and-refocus cycle resets to the latest message (BR-09).
- [ ] Restarting the app preserves the chat thread (every prior `ConversationMessage` is queryable, last N reloaded into orchestrator state).
- [ ] The orchestrator can answer questions about prior turns ("what did I ask earlier?") within the rolling window.
- [ ] Each `ConversationMessage` row stores the correct `viewContext` based on the active view at message time.
- [ ] No tool that belongs to one view is exposed when the user is on a different view (no leak of `transformChart` when on `/plan`, except via `goToView` + retry).
- [ ] Clicking the chat input expands the dock; clicking away collapses it; the single-line preview shows the latest assistant reply when collapsed (BR-08).
- [ ] On expand, the message list is scrolled to the last message regardless of where it was before collapse (BR-09).
- [ ] Sending a message produces a visible "AI working" indicator within the chat dock that persists until the assistant's reply is complete (BR-10). The indicator manifests as a wand shimmer when the dock is collapsed and an avatar glow when the dock is expanded.

---

## UI / Routes

- The chat panel is rendered by `MainLayout` and is identical across views. Its mount point doesn't change on navigation.
- On mobile, the chat is in a `Popover` triggered from a persistent FAB present on every view.
- Navigation initiated by the assistant uses standard Vaadin `UI.navigate` so the user sees a normal route change.
- **Dock states (BR-08, BR-09, BR-10):**
  - **Collapsed** (default, no focus): a thin bar — single-line preview of the latest assistant reply prefixed with a wand icon, then the input pill. Maximum vertical footprint kept small so the underlying view stays prominent.
  - **Expanded** (`:focus-within`): the message-history list slides up above the input pill (~240px tall, scrollable), the single-line preview hides (the full history replaces it), and the underlying view's bottom padding lifts so its last rows aren't tucked under the expanded dock.
  - **AI working**: while the orchestrator streams a reply, the wand (collapsed) or the in-progress assistant avatar (expanded) animates in the info-blue accent.
  - The `MessageList` is kept mounted across state transitions (not `display: none`) because Vaadin's `AIOrchestrator` streams tokens into it on a background thread and the element must be live to receive them.

| Route | Access | Notes |
|-------|--------|-------|
| any | public | The chat is on every authenticated route in the app. |

---

## Verification

#### Functional

- [ ] *"Go to reports and add kcal-per-euro column"* from `/plan` navigates and applies the change in one chat turn (BR-05)
- [ ] Manual view switching preserves chat content & scroll position (BR-09 caveat: blur+refocus resets to latest)
- [ ] Restart preserves chat thread (last N reloaded into orchestrator) (BR-01, BR-06)
- [ ] `ConversationMessage.viewContext` correct per row (BR-03)
- [ ] Tools from view A are not exposed while on view B (BR-02)
- [ ] Focusing the input expands the dock; blurring collapses it (BR-08)
- [ ] On expand, the message list scrolls to the latest message (BR-09)
- [ ] Submitting a message turns on the AI-working indicator until the response completes (BR-10)

#### Visual

- [ ] Chat panel rendered by `MainLayout`, identical position across views
- [ ] Mobile FAB → Popover present on every view
- [ ] Collapsed dock shows wand-prefixed single-line preview + input pill only (BR-08)
- [ ] Expanded dock shows a scrollable history (~240px) above the input pill, with the single-line preview hidden (BR-08)
- [ ] AI-working: wand shimmers in collapsed state, info-blue glow ring pulses around the in-progress assistant avatar in expanded state (BR-10)

#### AI

- [ ] Assistant-initiated navigation visible in chat
- [ ] Orchestrator can answer "what did I ask earlier?" within the rolling window

#### Result

- **Status:**
- **Notes:**
