# AI-Integration Review — does the demo showcase what it set out to showcase?

The project's stated reason to exist (project-context §2): *"a demonstration of what a deeply AI-integrated Vaadin business application looks like: chat, grids, charts, and dashboard widgets coordinated by one orchestrator over shared state."* And the user's framing: a demo of **the new Vaadin AI components, controllers, and the AI orchestrator**.

Scorecard:

| Showcase pillar | Status |
|---|---|
| `AIOrchestrator` + chat components | ✅ Genuinely well showcased |
| NL → **data** changes (tools mutate domain, views refresh) | ✅ Well showcased |
| NL → **presentation** changes via `GridAIController` / `ChartAIController` | ❌ Absent |
| `DatabaseProvider` / curated reporting schema | ❌ Absent |
| Explainability + reversibility (undo, "why?") | ✅ Showcased, and it's the demo's best material |

## What's genuinely good

- **Orchestrator wiring is the real thing, not a wrapper around a wrapper.** `AIConfig` exposes a prototype-scoped `SpringAILLMProvider`; `HouseholdOrchestrator` builds the Vaadin `AIOrchestrator` with history loaded via `withHistory()` from the persisted `ConversationService`, and `MainLayout` instantiates one per UI with all five tool beans. No homegrown plumbing duplicating what the Vaadin API provides. A reader learning the pattern sees the intended shape.
- **The system prompt (`HouseholdOrchestrator.java:43-103`) is a teaching artifact in itself** — per-view tool scoping, navigate-before-acting rule, concrete trigger phrases, anti-fabrication rules. Worth pointing at in the README.
- **Tool design is sound.** Allergy/pin constraints enforced inside the tools (not trusted to the model), undo as a forward-written audit row, `NavigationTools` with a hard-coded view map (no string injection), refusal paths that return text the model can relay. The AIIT layer tests the *model's* behavior against the production prompt — uncommon and valuable.
- **The mutation→UI path is clean:** tool → domain service → broadcaster → `UI.access` → view re-reads. The orchestrator never touches components, honoring the "orchestrator mediates, does not store" principle from project-context §7.

## The gap: the controller half of the story

`GridAIController`, `ChartAIController`, and `DatabaseProvider` appear **nowhere** in `src/`. Every "presentation transform" in Reports is a hand-rolled `@Tool` in `ReportsTools.java` (`addLeaderboardColumn`, `transformCategoryChart`, `resetWidget`) writing `ViewPreference` JSON, against a whitelist (`SUPPORTED_EXTRA_COLUMNS`). Charts are built with the plain Charts API via `MiseChart`.

Three consequences:

1. **The demo currently teaches "how to fake AI-driven UI with tools," not "how to use the AI controllers."** That's a perfectly respectable pattern — arguably the more robust one today, given the components are preview-gated — but it is not the advertised lesson. Anyone cloning the repo to see `GridAIController` in action finds nothing.
2. **UC-007's spec is wrong about its own implementation.** BR-02/BR-07 say transforms are bounded by what the controllers expose and that controllers detach on `BeforeLeaveEvent`. Neither is true of the shipped code. Since `spec/README.md` declares the spec the single source of truth, this must be resolved.
3. **`architecture.md` over-promises similarly** (controller/DatabaseProvider sections read as designed-and-present). A reader doing spec-first orientation will form a wrong model of the code.

### Recommendation

Pick one deliberately:

- **Option A — implement UC-012 next and retrofit UC-007 onto it.** The reporting-schema + `DatabaseProvider` + controller stack is precisely the missing showcase, and UC-012's spec already designs it. When it lands, delete the bespoke `ReportsTools` transform tools (or keep one as a "tool-based alternative" teaching contrast) and update UC-007 to match. This is the option that makes the demo deliver its premise.
- **Option B — amend UC-007/architecture.md now** to describe the tool+`ViewPreference` design as the *current* mechanism, with controllers explicitly deferred to UC-012. Cheap, honest, and keeps the spec trustworthy until A happens.

A and B aren't exclusive — do B immediately (it's a 20-minute spec edit), then A as the next implementation wave. What's not okay is the current state where an implemented UC's BRs describe machinery that doesn't exist.

## Smaller integration notes

- **Refresh timing:** `ReportsTools` fires `refreshBroadcaster.fireRefresh()` at tool-completion, while the chat turn may still be streaming; the view can repaint before the assistant's explanation lands. There's already an issue-comment trail at `ReportsTools.java:81`. Consider deferring widget refresh to the response-complete callback that `MainLayout` already owns (it fires all three broadcasters there anyway — making the per-tool fire redundant; pick one site).
- **All tools are registered globally; view scoping is prompt-text only.** `architecture.md` marks per-view controller attach/detach as planned. Fine for now, but worth a one-line code comment at the registration site (`MainLayout.java:163`) saying scoping is advisory, so readers don't assume the model is mechanically prevented from calling Plan tools from Reports.
- **Version pinning is handled well** — the pom comment explains the Spring AI M4 pin, the feature flag is set, and the sharp-edges docs cover the fragility. No action.
