---
name: reference-dramafinder
description: DramaFinder is a Vaadin add-on with Playwright element wrappers + a Claude skill for generating IT tests; coordinates and useful facts
metadata: 
  node_type: memory
  type: reference
  originSessionId: 4878e101-3f89-431f-ba3c-67e683989ad5
---

**DramaFinder** — Playwright utility classes for Vaadin, by parttio (JC Gueriaud).

- Repo: https://github.com/parttio/dramafinder
- Released coordinates (test scope):
  ```xml
  <dependency>
    <groupId>org.vaadin.addons</groupId>
    <artifactId>dramafinder</artifactId>
    <version>1.1.0</version>
    <scope>test</scope>
  </dependency>
  ```
- Resolved from the **Vaadin Directory** repo (see [[reference-vaadin-directory]]).
- Apache 2 license, Java 21, master branch tracks `1.1.2-SNAPSHOT`.
- Built/tested against Vaadin 25.1.3 + Spring Boot 4.0.5 — small skew vs newer Vaadin 25.2 alphas is expected to be fine since selectors target stable `vaadin-*` tag names.

**What's in the jar:**
- `org.vaadin.addons.dramafinder.AbstractBasePlaywrightIT` — base class with Playwright + Browser + Page lifecycle and a `WAIT_FOR_VAADIN_SCRIPT` that waits for Flow client quiescence.
- `org.vaadin.addons.dramafinder.element.*` — element wrappers for Button, TextField, ComboBox, Grid, Dialog, MessageInput, MessageList, Notification, etc. ~50 components covered.

**What's NOT in the jar:**
- `SpringPlaywrightIT` — lives in dramafinder's own `src/test/java`, so consumers must write their own Spring-Boot-aware base class extending `AbstractBasePlaywrightIT` (inject `@LocalServerPort`, implement `getUrl()`).

**Companion Claude skill:** `skills/vaadin-playwright-test/` in the same repo. Contains `SKILL.md`, `TESTING.md`, `setup.md`, `element-mapping.md`. Designed to be dropped into `~/.claude/skills/` so Claude can generate IT tests from a view file given DramaFinder is on the classpath. Skill enforces conventions: one-test-one-assert, user-facing locators, no `Thread.sleep`, prefer DramaFinder wrappers over raw Playwright locators.

**API maturity:** the README explicitly says "The API is in early stage of development." Expect to file upstream issues if a component wrapper is missing.
