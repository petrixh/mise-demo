---
name: reference-dramafinder-messagelistelement
description: DramaFinder MessageListElement.get(locator) searches INSIDE the locator for vaadin-message-list — use the constructor directly when the testid IS the message-list element itself
metadata: 
  node_type: memory
  type: reference
  originSessionId: 4878e101-3f89-431f-ba3c-67e683989ad5
---

`MessageListElement.get(Locator)` (and `get(Page)`) in DramaFinder 1.1.0 performs a **nested search** for a `vaadin-message-list` element *inside* the provided scope. It does NOT wrap the scope locator as the message-list.

**Consequence:** if you put `data-testid="chat-message-list"` directly on the `<vaadin-message-list>` element (which is the natural place — the testid IS the component), this silently fails:

```java
// Returns a MessageListElement that points at zero elements:
MessageListElement.get(page.getByTestId("chat-message-list"));
```

The auto-wait then resolves nothing, the assertion times out 0 != N, and the failure message is unhelpful ("expected count 2, got 0").

**Fix:** use the constructor directly to wrap the locator as-is:

```java
new MessageListElement(page.getByTestId("chat-message-list"));
```

**Rule of thumb:** use `MessageListElement.get(locator)` when the locator is a **container** around the message list (e.g. `data-testid="chat-dock"` on a wrapping `Div`). Use `new MessageListElement(locator)` when the locator **is** the message list itself.

The same pattern likely applies to other DramaFinder element classes — verify before assuming.

Discovered while writing `PlanViewIT` for UC-002 (commit history on branch `app/mise-uc-001-002-course-correct`). See also [[reference-dramafinder]].
