# Verification

> Verification methodology. Per-use-case checklists live in each `use-cases/use-case-NNN-*.md` under a `## Verification` section — copy §3's template when adding a new UC.

---

## 1. Visual Verification Process

Use the Playwright MCP server to visually verify each use case after implementation.

### Default Browser Resolution

Unless the use case specifies a particular resolution or size, use **1920×1080** as the default browser resolution for desktop checks. Mobile-first use cases (UC-005 Shopping, and the responsive aspects of UC-002 / UC-007) also require a **390×844** (iPhone 14 baseline) check.

### Design system & mockups

Visual checks are graded against two artefacts:

- [`../ai-meal-planner/mise/design-system.md`](../ai-meal-planner/mise/design-system.md) — the **semantic** language: which color means which category, what the "edited by AI" highlight looks like, the recurring component patterns (KPI card, meal row, save-elsewhere hint, recommendation card, chat dock). Use it to grade *intent*, not hex values.
- [`../ai-meal-planner/mise/*.png`](../ai-meal-planner/mise/) — the **mockups**: Plan / Shopping / Reports at desktop and mobile. Use them for **look-and-feel parity** — layout, component placement, density, hierarchy — *not* pixel-matching. Data in the running app is dynamic and will not match the mockup values, so judge structure not content.

Not every use case has a corresponding mockup (UC-001 onboarding is chat-only; UC-006 detour is reasoning in the existing chat; UC-009 insights renders as a banner). When no mockup exists, fall back to design-system rules and the use case's UI/Routes section.

### Steps

1. **Ensure the application is running** (`./mvnw`).
2. **Confirm the AI model endpoint is reachable** — see §2 below. AI use cases will appear to silently hang otherwise.
3. **Navigate to the route** — open the page defined in the use case's UI / Routes section.
4. **Walk through the main flow** — perform each step from the use case's Main Flow.
5. **Take screenshots** — capture the page state at key interaction points (initial load, just before chat input, immediately after assistant response, after navigation).
6. **Check visual appearance:**
   - Layout matches expectations (spacing, alignment, sizing).
   - Typography is readable and consistent.
   - Interactive elements are clearly identifiable.
   - Responsive behavior works at the required breakpoints (mobile 390, tablet ~768, desktop 1920).
   - Aura theme tokens are honored (no ad-hoc inline colors).
7. **Inspect AI-specific behavior** — see §2.
8. **Record results** — note any visual or functional issues in the per-use-case checklist below.

---

## 2. AI-specific verification

Because most of this app's behavior is driven by an LLM, plain UI verification is insufficient. For every use case that involves chat:

### Model & endpoint sanity

- [ ] `application.properties` (or the active profile) points to a reachable model endpoint. Default: `http://192.168.1.196:8080` for the local Qwen.
- [ ] A `curl <base-url>/v1/models` returns a 2xx response before testing.
- [ ] The model name in `spring.ai.openai.chat.options.model` exists on the endpoint.

### Conversation persistence

- [ ] Send a message in any view. Stop and restart the JVM. Reopen the app. The message and its assistant reply are still in the chat thread.
- [ ] A new `ConversationMessage` row exists in H2 per turn (verify via H2 console or a quick repository query).

### Tool grounding (no fabrication)

- [ ] Inspect the chat for any AI-claimed prices, calorie counts, or quantities. Cross-check against the corresponding YAML / `Recipe.macros`. Any divergence is a regression — the AI must not invent numbers.
- [ ] For any AI explanation ("why did you swap?"), confirm the reasoning references a real `MealEdit.reason`, real preference, or real price — not a generic justification.

### Determinism of the demo headline (UC-006)

- [ ] Edit a single price in `demo/data/stores/lidl.yaml`, restart, re-ask the same detour question. The verdict must visibly change. If it does not, `StubbedPriceCatalog` is caching stale data and the demo is broken.

### Latency budget

- [ ] Single-meal edits surface in the UI within **2 seconds** of the chat input being submitted, measured locally against the Qwen endpoint.
- [ ] Multi-constraint negotiations complete within **5 seconds**, with progressive chat feedback meanwhile.
- [ ] If either budget is exceeded with the default model, document the gap and the test environment; the constraint may be model-dependent and acceptable to relax during dev runs.

---

## 2a. Automated baselines

A growing subset of the checks in §3 is covered by Playwright ITs under `src/test/java/com/example/mise/it/*IT.java`, runnable via `./mvnw -Pit verify`. The IT layer covers **deterministic surface** — route loads, page titles, component visibility, navigation, persisted state — but **does not replace the manual visual / AI checks below**: it does not screenshot-compare against the design system, does not call the real LLM, and runs against an in-memory H2 with no seed data.

Per-use-case IT mapping (added as ITs land):

| Use case | IT class | Covers |
|---|---|---|
| (smoke) | `HomeViewIT` | router boots, `MainLayout` renders, `/debug` shell loads |

---

## 3. Per-Use-Case Verification

Each use case carries its own `## Verification` section at the bottom of its `use-cases/use-case-NNN-*.md` file. Use the template below when adding a new UC. The methodology in §§1–2a applies to every UC; the per-UC block records what *that* UC needs.

---

### Template (copy into the UC file as `## Verification`)

**Verified by:** [Name/Agent]
**Date:** [YYYY-MM-DD]

#### Functional

- [ ] Main flow works end-to-end as described in the spec
- [ ] All business rules are enforced (list BR-IDs: [BR-01, BR-02, ...])
- [ ] All acceptance criteria pass
- [ ] Error/edge cases handled appropriately

#### Visual

- [ ] Page layout matches expectations
- [ ] Interactive elements respond correctly (hover, focus, click)
- [ ] Loading states and transitions are smooth
- [ ] Responsive at mobile (390) and desktop (1920) widths

#### Visual comparison (where a mockup exists)

- [ ] Component placement matches the mockup
- [ ] Color usage honors the design system
- [ ] Typography hierarchy matches
- [ ] Spacing and density feel close
- [ ] Recurring patterns are reused, not reinvented

#### AI (where applicable)

- [ ] AI responses are grounded in real data (no fabricated prices / quantities / macros)
- [ ] Tool calls produce the expected DB writes
- [ ] Conversation persists across restart
- [ ] Latency within budget

#### Result

- **Status:** [Pass / Fail / Partial]
- **Notes:** [Any issues found or follow-up items]

---

## Manual pre-demo checklist

These items can't be (or aren't) covered by automated tests — run them by hand before any on-stage demo. Each takes under two minutes against the live local model.

- [ ] **UC-006 seed-edit flip (the teaching moment):** note the current detour verdict for a store (*"is Lidl worth a stop?"*), edit that store's YAML price for a named item, restart the app, re-ask — the verdict (or its € figure) must visibly change.
- [ ] **UC-012 live reshape:** on /reports, ask *"rank the leaderboard by kcal per euro"* — the grid reshapes in place and survives a page reload.
- [ ] **UC-012 refusal:** *"chart my carbon footprint per meal"* → refusal + proxy offer, no invented numbers.
- [ ] **UC-010 keyboard pass:** tab order reaches prev → week pill → next; Enter/Space on the pill opens the date picker.
- [ ] **UC-008 cross-view single turn:** from /plan, ask a Reports reshape — assistant navigates and applies it in one turn.
- [ ] **Endpoint sanity:** chat round-trip streams within the latency budget on the demo network (the configured model name must appear in `GET <base-url>/v1/models`).
