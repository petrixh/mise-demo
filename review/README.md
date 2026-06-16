# Implementation Review — 2026-06-10

A sweep of the codebase (branch `review/uc-impl-wave-2`) against the spec tree, plus a code-quality pass and a live tooling/runtime verification. Documents:

| File | Angle |
|------|-------|
| [`spec-compliance.md`](spec-compliance.md) | Per-UC audit: what's implemented, what drifted, what's untested |
| [`ai-integration.md`](ai-integration.md) | How well the app showcases the Vaadin AI components — the demo's reason to exist |
| [`code-quality.md`](code-quality.md) | Simplicity / readability / compactness findings, ranked by impact |
| [`fix-plan.md`](fix-plan.md) | Verified runtime/tooling status + the staged plan for spec updates, version bump, and UC-011/012 |

> Correction note (2026-06-10, post-runtime-verification): an earlier draft claimed UC-009's insight placement violated the spec. It does not — `CostByCategoryPanel` renders the Plan sidebar callout (dismissable + "Act on it"), Reports has its in-panel block, and the spec explicitly sanctions the legacy `MainLayout` banner for Shopping only. Finding retracted; replaced with the live date-resolution bug below.

## TL;DR

**The orchestrator half of the demo is in good shape; the AI-controller half doesn't exist yet.** UC-001..006, 008, 010 are solidly implemented with real spec traceability (BR references in code comments, three test layers). But the headline premise — *"the AI can modify both the data **and the presentation**"* — is only half-delivered: every Reports "transform" is a hand-rolled `@Tool` writing `ViewPreference` JSON, and `GridAIController` / `ChartAIController` / `DatabaseProvider` appear **nowhere** in `src/`, even though the **implemented** UC-007's BR-02/BR-07 say the widgets are driven by those controllers. UC-011/012 being unimplemented is expected (spec-only commits); UC-007 claiming controllers it doesn't use is spec drift that needs resolving in one direction or the other.

## Top findings (ranked)

1. **UC-007 spec ↔ code contradiction on AI controllers.** Spec BR-02/BR-07 bound transforms to `GridAIController`/`ChartAIController` and require detach on `BeforeLeaveEvent`; the implementation uses neither. Either implement UC-012's controller layer and retrofit UC-007 onto it, or amend UC-007 to honestly describe the current tool-based design and keep the controller story exclusively in UC-012. The spec is declared the single source of truth — right now it lies about shipped behavior. → [`ai-integration.md`](ai-integration.md)
2. **Live bug (caught in a real chat round-trip): "What's for dinner on Friday?" answers against the wrong week.** `PlanTools.getActivePlan()` correctly resolves the *viewed* plan (UC-010 BR-06), but `PlanTools.resolveDate()` maps day names to the *real-world* current week via `LocalDate.now()`. With the viewed/active week being May 18, "Friday" resolves to Jun 12, the lookup misses, and the model replies "no meal planned for Friday yet" while Friday's meal is visibly on screen. → [`spec-compliance.md`](spec-compliance.md#uc-010)
3. **Three identical `*RefreshBroadcaster` classes** (`ui/plan/`, `ui/shopping/`, `ui/reports/`) — same CopyOnWriteArrayList-of-Runnables pattern triplicated, with drifting javadoc and parameter names. One generic `ViewRefreshBroadcaster` (or three named beans of one class) says the same thing in a third of the code. → [`code-quality.md`](code-quality.md)
4. **Single-implementation interfaces in `capabilities/`** (`RecipeCatalog`/`FilesystemRecipeCatalog`, `PriceCatalog`/`StubbedPriceCatalog`, `PersonaCatalog`/`FilesystemPersonaCatalog`). The project-context *does* promise a service-oriented seam, so keeping them is defensible — but for a teaching demo, consider whether the seam story is worth the two-file indirection per concept. Decide deliberately; don't keep them by inertia. → [`code-quality.md`](code-quality.md)
5. **One stray `--lumo-base-color` under the Aura theme** at `mise-reports.css:182` — the project's own sharp-edges doc says Lumo tokens silently don't resolve. Likely dead; remove. → [`code-quality.md`](code-quality.md)
6. **Demo headline moments are untested.** The "edit a seed YAML price → restart → detour verdict flips" teaching moment (UC-006 BR-06) and several BR-level behaviors (onboarding turn limit, store-mode persistence across reload) have no automated check. Acceptable for a demo if they're on a manual pre-demo checklist — but they aren't listed anywhere as such. → [`spec-compliance.md`](spec-compliance.md)

## Overall verdict on code quality

For a demo meant to be read: **good**. No inline-style violations, clean one-CSS-file-per-view discipline, audit-trailed mutations (`MealEdit`), a genuinely instructive 100-line system prompt, and view classes that are large but legible. The recurring weakness is *consistency drift between siblings* — three broadcasters, six tool classes with three different error-string styles, two null-handling idioms — exactly the kind of thing a newcomer reads as signal when it's actually noise.
