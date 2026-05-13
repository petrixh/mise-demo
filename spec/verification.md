# Verification

> Visual verification process using Playwright MCP, plus a per-use-case checklist.
> Copy section 3's template for any future use case.

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

## 3. Per-Use-Case Verification Checklist

> Copy this section for any new use case. Existing use cases each have their own block below.

### Template

**Use case spec:** [`use-cases/use-case-NNN-name.md`](use-cases/use-case-NNN-name.md)
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

- [ ] Component placement matches the mockup (header, side panels, chat dock anchored bottom, tabs at top)
- [ ] Color usage honors the design system (category colors stable across views, edited highlight uses the attention color, status colors not reused decoratively)
- [ ] Typography hierarchy matches (KPI headline / body / meta / tag scale; uppercase labels with tracking for structural headers)
- [ ] Spacing and density feel close (card padding, list-row padding, KPI gap, hairline borders)
- [ ] Recurring patterns are reused, not reinvented (meal row, save-elsewhere strip, recommendation card, AI insight callout, chat dock)

#### AI (where applicable)

- [ ] AI responses are grounded in real data (no fabricated prices / quantities / macros)
- [ ] Tool calls produce the expected DB writes
- [ ] Conversation persists across restart
- [ ] Latency within budget

#### Result

- **Status:** [Pass / Fail / Partial]
- **Notes:** [Any issues found or follow-up items]

---

### UC-001: Onboarding — chat-driven first run

**Use case spec:** [`use-cases/use-case-001-onboarding.md`](use-cases/use-case-001-onboarding.md)

#### Functional

- [ ] Fresh boot with empty H2 routes to `/welcome` and shows chat only (no nav)
- [ ] Plausible household description in chat produces a populated `/plan` within 10s
- [ ] BR-01 (existing household → skip onboarding), BR-02 (persona seed overridden by chat), BR-03 (missing fields → follow-up), BR-04 (≤ 3 turns), BR-05 (allergies hard / hates soft), BR-06 (≥ 4 seeded history weeks), BR-07 (`viewContext = ONBOARDING`) enforced
- [ ] Restart preserves all onboarding state

#### Visual

- [ ] `/welcome` has no nav drawer, no bottom bar, no insight banner
- [ ] Chat occupies full height; input is autofocused
- [ ] Looks coherent at 390 and 1920

#### Visual comparison

UC-001 (the `/welcome` view) has **no dedicated mockup**. Grade against the design system only:

- [ ] Chat dock uses the input-pill shape from the design system (rounded ~`20px`, plus / mic / send affordances tolerated absent)
- [ ] Typography hierarchy follows the chat-message rules (body 13px, looser line-height)
- [ ] Surface levels reasonable: page background, chat container on a one-step-darker surface, message bubbles on the primary surface
- [ ] No ad-hoc colors — Aura theme tokens drive every fill and border

After onboarding completes, the auto-navigated `/plan` view can be compared against [`Plan-desktop.png`](../ai-meal-planner/mise/Plan-desktop.png) and [`Plan-mobile.png`](../ai-meal-planner/mise/Plan-mobile.png), but only as a stub baseline — UC-002 owns the real Plan look-and-feel.

#### AI

- [ ] Asked allergies don't appear in any seeded meal
- [ ] Persona JSON cited values match what was inserted into `Household`

#### Result

- **Status:**
- **Notes:**

---

### UC-002: View the current week's plan

**Use case spec:** [`use-cases/use-case-002-view-current-plan.md`](use-cases/use-case-002-view-current-plan.md)

#### Functional

- [ ] `/plan` renders KPI strip, 7-row meal grid, and chat within 2s (excluding LLM)
- [ ] Weekly cost equals sum of priced meal items at current `PriceCatalog`
- [ ] Pin survives reload (`Meal.pinned`)
- [ ] BR-01..BR-06 enforced (single `ACTIVE` plan, empty-slot placeholders, stats recompute, edited pill window, no inline edits except pin/cooked/skipped, chat shared with other views)

#### Visual

- [ ] Three-column at ≥ 1024px, two-column at 640–1023, single-column at < 640
- [ ] Mobile shows chat FAB → Popover
- [ ] "Edited" pill matches Aura badge style

#### Visual comparison

Compared against [`Plan-desktop.png`](../ai-meal-planner/mise/Plan-desktop.png) and [`Plan-mobile.png`](../ai-meal-planner/mise/Plan-mobile.png), plus the design system:

- [ ] **Dark theme** as the default (mockups are dark mode — Aura's `theme="dark"` or equivalent applied on the app shell)
- [ ] Tabs at top of the canvas (Plan / Shopping / Reports), active tab has a 2px bottom border in primary text color
- [ ] KPI strip with uppercase-tracking labels and large headline numbers; deltas (when present) use the success/attention status colors
- [ ] Meal grid uses the **meal row** pattern from the design system: day chip (uppercase, 11px) + meal name + "prep · cost · kcal" meta line + right-aligned tag/status
- [ ] "Cost by category" bars on the right (desktop) / below the grid (mobile) use the **category colors** (Protein purple, Produce green, Pantry orange, Dairy pink, Other gray)
- [ ] AI-edited row carries the **attention/amber** tint plus an "edited" pill; not arbitrary highlight
- [ ] Tag pills (`veg`, `fish`) use the Produce-light / Info-light tag palette per design-system §"Tags"
- [ ] Chat dock anchored at the bottom of the view at every form factor; identical between desktop and mobile per design-system §"Chat dock"
- [ ] Responsive reshape: KPI strip becomes 2×2 on mobile, side panel stacks below grid, tabs stay at top
- [ ] No ad-hoc inline hex — Aura tokens + `--mise-category-*` properties drive all color

#### AI

- [ ] *"What's on Friday?"* returns the actual Friday meal and one-line description
- [ ] Editing a `stores/*.yaml` price + restart changes the cost KPI

#### Result

- **Status:**
- **Notes:**

---

### UC-003: Edit the weekly plan via natural language

**Use case spec:** [`use-cases/use-case-003-edit-meals-via-chat.md`](use-cases/use-case-003-edit-meals-via-chat.md)

#### Functional

- [ ] Single swap produces exactly one `MealEdit`, the right row flips, KPIs & shopping list update
- [ ] Constraint negotiation result respects all named constraints atomically (BR-04)
- [ ] Pin prevents edits to that meal (BR-03)
- [ ] Infeasible request returns a "best I could do" explanation (BR-07)
- [ ] Allergic ingredients never appear in any AI-proposed meal, even under explicit prompting (BR-02)

#### Visual

- [ ] All changed rows show "edited" pill for the configured window
- [ ] Pin icon visible on every row; toggles state correctly

#### AI

- [ ] `MealEdit.reason` populated with a non-empty, plausible justification
- [ ] No fabricated kcal / cost values in chat replies (cross-check against the recipe & price catalog)
- [ ] Single-swap latency ≤ 2s; negotiation ≤ 5s with progressive feedback

#### Result

- **Status:**
- **Notes:**

---

### UC-004: Undo & explain

**Use case spec:** [`use-cases/use-case-004-undo-and-explain.md`](use-cases/use-case-004-undo-and-explain.md)

#### Functional

- [ ] Click "undo" reverts the most recent `MealEdit` and writes a new `MealEdit` documenting the revert (BR-03)
- [ ] *"Put X back"* in chat produces the same revert
- [ ] "Why?" answer names ≥ 1 concrete factor from `MealEdit.reason` / household state (BR-04)
- [ ] Missing `MealEdit.reason` → assistant says it doesn't have the reasoning (BR-04, no fabrication)
- [ ] Asking why about a non-most-recent change triggers a clarifying question (BR-05)

#### Visual

- [ ] "Undo" affordance under the assistant message and on the row's "edited" pill
- [ ] "Why?" affordance on the row's "edited" pill; pre-fills chat input and submits

#### AI

- [ ] Explanation length within bounds (≤ 3 sentences single, ≤ 5 negotiation)
- [ ] No apologies or preamble

#### Result

- **Status:**
- **Notes:**

---

### UC-005: Shopping list

**Use case spec:** [`use-cases/use-case-005-shopping-list.md`](use-cases/use-case-005-shopping-list.md)

#### Functional

- [ ] Two recipes sharing an ingredient consolidate to one row in correct unit (BR-02)
- [ ] Staple pantry items hidden (BR-03); non-staple needs explicit *"already have"* (BR-04)
- [ ] Store mode toggle persists to `ViewPreference` and survives reload (BR-05)
- [ ] Plan edit reflows shopping list within 2s (BR-08)
- [ ] Check-off state is session-local; resets on plan change (BR-07)

#### Visual

- [ ] Mobile is primary form factor — large tap targets, sticky aisle headers
- [ ] "You already have" section collapsed by default but expandable
- [ ] One-store mode shows per-item "saves €X at Y" notes where applicable (BR-06)

#### AI

- [ ] *"What do I already have?"* lists actual `PantryItem` rows
- [ ] *"Why is the list so long?"* references actual meal counts / ingredients

#### Result

- **Status:**
- **Notes:**

---

### UC-006: Detour reasoning & alternatives (demo headline)

**Use case spec:** [`use-cases/use-case-006-detour-reasoning.md`](use-cases/use-case-006-detour-reasoning.md)

#### Functional

- [ ] *"Should I bother with Lidl?"* produces a verdict naming specific items and savings (default data: "not worth it")
- [ ] Editing `stores/lidl.yaml` salmon price → restart → re-ask: verdict flips to "worth it" and cites the new price (BR-06 — the demo headline)
- [ ] *"Savings without the detour"* triggers a UC-003 plan swap with a documented `MealEdit`
- [ ] Detour verdicts never auto-change the recommended store (BR-03)
- [ ] Missing price → explicit "I don't have a price for X" (BR-01)

#### Visual

- [ ] *Cheapest mix* mode may flash a "consider Lidl?" hint; verdict text lives in chat

#### AI

- [ ] Verdict reasoning references real prices and `detourMinutesFromRoute` values
- [ ] No invented prices or distances

#### Result

- **Status:**
- **Notes:**

---

### UC-007: Reports — defaults & AI transforms

**Use case spec:** [`use-cases/use-case-007-reports-and-transforms.md`](use-cases/use-case-007-reports-and-transforms.md)

#### Functional

- [ ] Post-onboarding `/reports` shows ≥ 4 weeks of data in three default widgets
- [ ] *"Add kcal-per-euro column"* adds a column with values = `meal.kcal / meal.estimatedCost`; persisted via `ViewPreference`; survives reload (BR-04)
- [ ] *"Show as horizontal bar"* transforms donut → bar; persisted; survives reload
- [ ] *"Reset the leaderboard"* removes the customization
- [ ] Request for non-derivable column (e.g. carbon footprint) → explicit refusal (BR-03)
- [ ] Reports controllers attach on enter and detach on leave (BR-07)

#### Visual

- [ ] Vaadin `Dashboard` with three widgets; chat icon on each widget
- [ ] Transform highlight fades over a few seconds

#### AI

- [ ] "Why was last week cheaper?" names ≥ 1 concrete meal/category from that week
- [ ] No fabricated historical prices

#### Result

- **Status:**
- **Notes:**

---

### UC-008: Cross-view chat

**Use case spec:** [`use-cases/use-case-008-cross-view-chat.md`](use-cases/use-case-008-cross-view-chat.md)

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

---

### UC-009: Unprompted insights

**Use case spec:** [`use-cases/use-case-009-insights.md`](use-cases/use-case-009-insights.md)

#### Functional

- [ ] Banner appears at startup after trigger window elapses (BR-04)
- [ ] Dismiss sets `Insight.dismissed = true`; no replacement appears immediately (BR-02)
- [ ] *"Mute insights"* sets `Household.insightsMuted = true`; no new banners (BR-05)
- [ ] Acting on an insight (*"lock that in"*) triggers a UC-003 plan edit with a `MealEdit`
- [ ] `Insight.evidenceRefs` reference real `Plan` / `Meal` IDs (BR-03)

#### Visual

- [ ] Banner is sticky-top on mobile, inline at top on desktop
- [ ] No insights on `/welcome`

#### AI

- [ ] Insight text is concrete (cites real meals or weeks), not generic
- [ ] Phrased as a question (preferred per BR-06)

#### Result

- **Status:**
- **Notes:**
