# CLAUDE.md

Project-specific guidance for Claude Code. The user-facing README is [`README.md`](README.md); the spec is in [`spec/`](spec/).

## Project at a glance

Mise — a weekly meal planner that demonstrates a deeply AI-integrated Vaadin application. The full vision is in [`spec/project-context.md`](spec/project-context.md). Read [`spec/README.md`](spec/README.md) first when picking up work.

## Stack

- Java 21, Spring Boot 4, Maven (wrapper included)
- Vaadin Flow 25 (Aura theme) — server-side Java UI
- Vaadin AI components (preview) — `AIOrchestrator`, `GridAIController`, `ChartAIController` (the latter two drive the Reports widgets, UC-012); gated by `com.vaadin.experimental.aiComponents=true` in `src/main/resources/vaadin-featureflags.properties`
- Spring AI 2 with `spring-ai-starter-model-openai` — points at any OpenAI-compatible endpoint
- Exact pinned versions live in `pom.xml` (`vaadin.version`, `spring-ai.version`, the Spring Boot parent) — see the version-pinning note under "Sharp edges". The bullets above name the major lines only so they don't drift on every bump.
- Spring Data JPA + H2 in file mode (`./data/mise`), with the H2 console explicitly registered at `/h2-console`
- `@Push` is set on `Application` — required for streaming chat responses

## Build & Run Commands

```bash
./mvnw                            # Run in dev mode (default goal: spring-boot:run)
./mvnw clean package              # Production build (JAR in target/)
./mvnw test                       # Run unit + Browserless tests
./mvnw test -Dtest=ClassName      # Run a single test class
./mvnw -Pit verify                # Run *IT.java Playwright browser tests (opt-in)
./mvnw -Pit verify -Dit.test=X    # Run a single IT class
./mvnw -Pai-it verify             # Run *AIIT.java against the live LLM (2-fork parallel; opt-in)
./mvnw -Pai-it verify -Dit.test=X # Run a single AIIT class
```

The app runs on port 8080 (configurable via `PORT` env var). For the LLM endpoint, copy [`application-local.properties.example`](application-local.properties.example) → `application-local.properties` (gitignored, project root) and edit. The `local` Spring profile is always included (`spring.profiles.include=local` in both `application.properties` and `application-ai-it.properties`), so Spring Boot auto-loads the file for dev runs and AIIT alike. Env vars (`MISE_MODEL_BASE_URL`, `MISE_MODEL_API_KEY`, `MISE_MODEL_NAME`) still work for Docker / CI but aren't needed day-to-day.

There are three test layers, each with its own purpose:

- **Unit + Browserless** (`./mvnw test`) — fast in-JVM tests. Unit tests live under their domain packages; Browserless tests use Vaadin's [`browserless-test-spring`](https://vaadin.com/docs/latest/flow/testing/browserless/getting-started) under `src/test/java/com/example/mise/browserless/` and assert on the live Vaadin component tree without a Chromium browser. Browserless is the right tool for click-handler / repository-side-effect tests with no CSS / layout concerns.
- **Playwright IT** (`./mvnw -Pit verify`) — real Chromium via [Microsoft Playwright](https://playwright.dev/java/) with [DramaFinder](https://github.com/parttio/dramafinder) wrappers for Vaadin locators. Headless by default. Lives under `src/test/java/com/example/mise/it/` and uses an in-memory H2 (`application-it.properties`). First run rebuilds the Vaadin frontend bundle (3–5 min); subsequent runs are ~25 s. Reach for it when CSS / layout / rendering / responsive breakpoints matter.
- **AI Tool IT** (`./mvnw -Pai-it verify`) — `*AIIT.java` under `src/test/java/com/example/mise/aiit/` that drive the production tool beans + system prompt through Spring AI's `ChatClient` against the **live LLM** endpoint. No Playwright, no dev server; each test seeds its own household + active plan in an in-memory H2 (`application-ai-it.properties`). Two failsafe forks run in parallel — match this with a model endpoint that advertises a `parallel-2` slot (the default Qwen-local model does). Use for tool-invocation correctness, no-fabrication checks, BR clarification rules, and reply tone / length — anything that's about the model's behaviour against real tool descriptions, not about UI integration.

> **Don't run two test layers (separate Maven/JVM processes) at the same time.** The `verify` lifecycle for `-Pit` and `-Pai-it` re-runs the unit/Browserless suite in its surefire phase, and those `@SpringBootTest`s hit the **file** H2 at `./data/mise` (`AUTO_SERVER=TRUE`, shared across JVMs). Two concurrent builds collide on that shared DB and one tears the session out from under the other — surfacing as spurious `Connection is broken: "session closed"` / `Database is already closed` errors (typically in `PlanServiceUC011Test`), not real failures. Run the layers sequentially. The clean fix is to put the unit `@SpringBootTest`s on an in-memory H2 like the IT/AIIT profiles already use — tracked as a future optimization, not done yet.

## Specifications

Project specs live in `spec/`. Read [`spec/README.md`](spec/README.md) first. Key files:

- [`project-context.md`](spec/project-context.md) — vision, scope, constraints
- [`architecture.md`](spec/architecture.md) — tech stack, package layout, AI wiring
- [`datamodel/datamodel.md`](spec/datamodel/datamodel.md) — persistent entities, seed-data DTOs, UC ↔ entity matrix
- [`use-cases/`](spec/use-cases/) — UC-001 through UC-009 (Draft)
- [`verification.md`](spec/verification.md) — verification methodology (visual / AI / automated baselines); per-UC checklists live in each [`use-cases/use-case-NNN-*.md`](spec/use-cases/) under its own `## Verification` section

## Skills available in this repo

The project ships custom skills under [`.claude/skills/`](.claude/skills/) — invoke them with `/<skill>` or by trigger phrase. Notable ones:

- **`implement-uc`** — orchestrates use-case implementation via subagents (impl, visual verify, AI verify) with file-based progress tracking.
- **`spec-*`** — `spec-status`, `spec-validate`, `spec-architect`, `spec-interview`, `spec-generate` for working with the spec tree.
- **`vaadin-playwright-test`** — generates `*IT.java` tests for a Vaadin view using DramaFinder element wrappers. Triggers on "write an IT test for X", "DramaFinder", "Playwright test". Vendored from [parttio/dramafinder](https://github.com/parttio/dramafinder) under Apache 2.

## Project agent memory

Shared, project-wide gotchas and conventions for agents live under [`.claude/memory/`](.claude/memory/) — each file is a single topic (codebase quirks, references, workflow feedback). `.claude/memory/MEMORY.md` is the index. Read the index when starting work on this repo; pull the linked files when their topic is in scope. Workers spinning up via `/ticket-worker` should treat that dir as load-bearing context, not optional reading.

## Sharp edges to know about

These are non-obvious behaviors discovered while wiring the prep branches; relevant when working on the AI backbone (more in [`.claude/memory/`](.claude/memory/)):

- **Spring Boot 4 dropped `H2ConsoleAutoConfiguration`.** The `spring.h2.console.*` properties are inert; `H2ConsoleConfig` registers `JakartaWebServlet` manually. If H2 ever stops responding under the Vaadin `/*` mapping, that config is the place to look.
- **Vaadin AI components pin the Spring AI milestone.** Keep `spring-ai.version` aligned with what `vaadin-ai-components-flow`'s POM declares for the pinned Vaadin version (alpha5 → M4, beta1 → M5, rc1/25.2.0 GA → 2.0.0 GA); a mismatch breaks `SpringAILLMProvider` at runtime with `NoSuchMethodError` (advisor builder APIs renamed between milestones). On **Vaadin 25.2.0 (stable) we track Spring AI 2.0.0 GA** (the matched pair), verified green across all three test layers. **Jackson gotcha:** GA adds an `<exclusion>` for `tools.jackson.dataformat:jackson-dataformat-yaml` on its `spring-ai-client-chat` → `com.networknt:json-schema-validator` edge (M5 left it un-excluded, which is how that artifact used to reach our classpath transitively). `StubbedPriceCatalog`/`FilesystemRecipeCatalog` import `tools.jackson.dataformat.yaml.YAMLMapper` directly, so `jackson-dataformat-yaml` is declared as a **direct** dependency in `pom.xml` (version BOM-managed) — without it the build won't compile under GA. This is *not* a Jackson 2/3 downgrade: Jackson 2.x (`com.fasterxml`) and Jackson 3 (`tools.jackson`) coexist under Spring Boot 4 on every milestone. Spring AI 1.x is incompatible with Spring Boot 4 entirely. Also: `AIOrchestrator.reconnect()` is deserialization-only — controllers/tools can NOT be attached or detached at runtime; everything registers at build time and is scoped via the system prompt.
- **The assistant `ChatMessage.messageId` is null.** Vaadin's `AIOrchestrator.streamResponseToMessage` builds the assistant turn with `messageId = null`. Anything that dedupes on messageId will silently drop the assistant message — `ConversationService` syncs by list index against `repository.count()` instead.
- **Vaadin's CSS bundler is parser-hostile to certain comments.** Multi-line `/* … */` comments inside `@media` blocks — especially ones containing square brackets `[ ]` — can cause Vaadin to truncate the entire `@media` block from the served bundle, silently dropping every rule after the comment. Use single-line comments inside `@media`. After CSS edits, verify with `curl http://localhost:8080/mise-<view>.css | grep <selector>` rather than trusting the source file.
- **Aura is the theme; Lumo `--lumo-*` custom properties do not resolve.** Several views were originally written against Lumo tokens (`--lumo-base-color`, `--lumo-space-m`, `--lumo-secondary-text-color`, etc.). Those tokens evaluate to nothing under Aura and the component renders unstyled. Use `--vaadin-background-container`, hard-coded px spacing, `--vaadin-secondary-text-color`, and the project's `--mise-*` category tokens instead.

## Conventions

### CSS

- **No inline CSS for styling.** Don't reach for `element.getStyle().set("foo", "bar")` to apply static styling. Add a class with `element.addClassName("mise-foo")` and put the rule in the matching view CSS file. Inline styles are only acceptable when the value is **computed at runtime per instance** — e.g. a progress bar's width from a percentage, a category color picked from data. For those, drop a one-line comment naming the dynamic source so a future reader doesn't refactor it into a static class.
- **One CSS file per view, linked from `styles.css`.** The master file at `src/main/resources/META-INF/resources/styles.css` grew past 400 lines as a single monolith and became hard to scan; new view styles live next to it as `mise-<view>.css` and are pulled in via plain CSS `@import "mise-<view>.css";` at the top of `styles.css` — the same pattern already used for `mise-dark.css`. The master file keeps: app-level custom properties (`--mise-category-*`, surface tokens), genuinely cross-view rules, and the `@import` roster. Per-view selectors (prefixed `mise-<view>-...`) live in their own file. When implementing a new view, create `mise-<view>.css`, add the `@import`, and put every selector for that view there.

## Guardrails

- Do not modify `pom.xml` without asking. (Necessary for stack changes; ask first.)
- Do not modify `vite.config.ts` without asking.
- Do not modify `spec/architecture.md` without asking. (Treat the whole `spec/` tree as the source of truth: keep it in sync when behavior changes, but ask before structural rewrites.)

## Implementation status

The first three commits carry the spec. Two prep branches build the runtime backbone and are Playwright-verified:

- `app/mise-prep` — project skeleton + persistent H2 + H2 console.
- `app/mise-prep-2` — AI backbone (Spring AI ↔ Vaadin `AIOrchestrator` ↔ persisted conversation) round-tripped against the live Qwen endpoint, then reloaded after restart.

Use cases UC-001..UC-009 are specified and ready to implement, starting with UC-001 (onboarding) to populate a household.
