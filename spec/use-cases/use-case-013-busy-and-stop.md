# UC-013: Busy feedback and "stop" to cancel an in-flight turn

> Cross-cutting refinement of the chat dock (UC-008). Addresses the dead-air problem where a
> prompt typed during a long AI turn is silently dropped, and adds a way to interrupt a turn.

---

**As a** home cook, **I want to** know when Mise is busy and be able to stop a running request **so that** the chat never feels frozen and I'm not stuck waiting on a slow or unwanted answer.

**Status:** Draft
**Date:** 2026-06-25

---

## Main Flow

- I ask Mise something that takes a while; the chat shows the "AI working" indicator (UC-008 BR-10).
- Before it finishes, I type another message and send it.
- Instead of nothing happening, a lighter, italic note appears just above the input: *"Mise is still working — one moment. (Type "stop" to cancel.)"* My second message is not sent.
- When Mise's first reply lands, the note disappears on its own.
- Another time, a reply is taking too long (or going the wrong way), so I type **stop**.
- Mise halts immediately: whatever had streamed so far stays in the thread with a trailing *(stopped)* marker, the working indicator clears, and the input is ready for my next message.
- If I type **stop** when nothing is running, I get a brief *"Nothing is running."* note and no request is sent to the assistant.

---

## Business Rules

| ID | Rule |
|----|------|
| BR-01 | A prompt submitted while a turn is in flight is **not sent**. A transient, lighter-styled note appears in the chat dock and is cleared when the in-flight reply completes (or fails). The suppressed prompt is **not** echoed into the conversation. |
| BR-02 | The busy note appears **only** in response to a submit made while busy — it is never shown proactively. |
| BR-03 | Typing `stop` (case-insensitive, surrounding whitespace ignored) while a turn is in flight cancels that turn. Whatever text streamed so far is kept and marked with a trailing `(stopped)`; the working indicator clears and the input becomes usable immediately. |
| BR-04 | `stop` while no turn is running is a no-op that shows a brief note and is **never** sent to the LLM. |
| BR-05 | Busy notices and "stop" confirmations are **ephemeral** UI feedback: they are not written to `ConversationService` and do not survive a reload. (The partial reply from a stopped turn *is* persisted — it is real assistant output.) |
| BR-06 | Cancellation is a real interrupt: the in-flight `LLMProvider` stream is completed early, which releases the orchestrator's busy state so the next prompt is accepted without waiting for the original request to finish server-side. |

---

## Acceptance Criteria

- [ ] Submitting any prompt while a turn streams shows the busy note and does not send the prompt; the note clears when the reply completes.
- [ ] The busy note is absent during normal idle use and during the first turn until a second submit occurs.
- [ ] `stop` / `STOP` / `  Stop ` while busy halts the stream within ~1s; partial text remains with a `(stopped)` marker; the input is immediately usable.
- [ ] `stop` while idle shows the brief note and produces no LLM request (verified in logs).
- [ ] After a reload, no busy/stop note is present; a stopped turn's partial reply is still in the thread.
- [ ] Normal send / user-bubble echo / streaming and the dock's focus-expand behaviour (UC-008) are unaffected.

---

## UI / Routes

- Rendered by `MainLayout` as part of the chat dock; identical across all views (same mount point as UC-008).
- The note is a single lighter, italic line (`.mise-chat-busy-note`, info-blue / secondary text) placed just above the input pill, hidden unless active.
- The `(stopped)` marker is inline markdown italic appended to the partial assistant message in the `MessageList`.
- `MainLayout` owns the chat submit listener (the orchestrator's `MessageInput` is intentionally **not** bound via `withInput`) so it can suppress, cancel, or send. Cancellation runs through a `CancellableLLMProvider` wrapping the Spring AI provider.

| Route | Access | Notes |
|-------|--------|-------|
| any | public | The chat dock is on every route; this behaviour applies everywhere. |

---

## Verification

> Per-UC checklist. Methodology in `../verification.md`.

**Verified by:**
**Date:**

#### Functional

- [ ] Main flow works end-to-end as described
- [ ] All business rules enforced (BR-01..BR-06)
- [ ] All acceptance criteria pass
- [ ] Edge cases: empty/blank submit ignored; `stop` casing/whitespace; rapid double-submit while busy

#### Visual

- [ ] Busy note is visibly lighter/secondary, italic, and unobtrusive above the input
- [ ] Note appears and clears cleanly (no layout jump that hides the input)
- [ ] Readable at mobile (390) and desktop (1920) widths and in dark mode

#### AI

- [ ] A stopped turn's partial reply persists across restart; busy/stop notes do not
- [ ] `stop` while idle issues no LLM request
- [ ] Cancelling a turn frees the orchestrator so the next prompt is accepted immediately

#### Result

- **Status:**
- **Notes:**
