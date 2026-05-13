# CLAUDE.md

Project-specific guidance for Claude Code. The user-facing README is [`README.md`](README.md); the spec is in [`spec/`](spec/).

## Project at a glance

Mise — a weekly meal planner that demonstrates a deeply AI-integrated Vaadin application. The full vision is in [`spec/project-context.md`](spec/project-context.md). Read [`spec/README.md`](spec/README.md) first when picking up work.

## Stack

- Java 21, Spring Boot 4.0.6, Maven (wrapper included)
- Vaadin Flow **25.2.0-alpha5** (Aura theme) — server-side Java UI
- Vaadin AI components (preview) — `AIOrchestrator`, `GridAIController`, `ChartAIController`; gated by `com.vaadin.experimental.aiComponents=true` in `src/main/resources/vaadin-featureflags.properties`
- Spring AI **2.0.0-M4** with `spring-ai-starter-model-openai` — points at any OpenAI-compatible endpoint
- Spring Data JPA + H2 in file mode (`./data/mise`), with the H2 console explicitly registered at `/h2-console`
- `@Push` is set on `Application` — required for streaming chat responses

## Build & Run Commands

```bash
./mvnw                            # Run in dev mode (default goal: spring-boot:run)
./mvnw clean package              # Production build (JAR in target/)
./mvnw test                       # Run unit + browserless-component tests
./mvnw test -Dtest=ClassName      # Run a single test class
./mvnw -Pit verify                # Run *IT.java Playwright browser tests (opt-in)
./mvnw -Pit verify -Dit.test=X    # Run a single IT class
```

The app runs on port 8080 (configurable via `PORT` env var). Default chat model points at the local Qwen at `http://192.168.1.196:8080`; override with `MISE_MODEL_BASE_URL`, `MISE_MODEL_API_KEY`, `MISE_MODEL_NAME`.

Integration tests run a real Chromium via [Microsoft Playwright](https://playwright.dev/java/) with [DramaFinder](https://github.com/parttio/dramafinder) wrappers for Vaadin locators. They live under `src/test/java/com/example/mise/it/`, default to headless (works in the dev container), and isolate themselves from the dev DB via an in-memory H2 in `src/test/resources/application-it.properties`. First run rebuilds the Vaadin frontend bundle (3–5 min); subsequent runs are ~25s. See the **Running integration tests** section in [`README.md`](README.md) for headed-mode usage.

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

## Sharp edges to know about

These are non-obvious behaviors discovered while wiring the prep branches; relevant when working on the AI backbone:

- **Spring Boot 4 dropped `H2ConsoleAutoConfiguration`.** The `spring.h2.console.*` properties are inert; `H2ConsoleConfig` registers `JakartaWebServlet` manually. If H2 ever stops responding under the Vaadin `/*` mapping, that config is the place to look.
- **Vaadin AI components are pinned to Spring AI 2.0.0-M4.** Newer milestones (M5/M6) renamed `MessageChatMemoryAdvisor$Builder.conversationId(...)` and break `SpringAILLMProvider` at runtime with `NoSuchMethodError`. Spring AI 1.x is incompatible with Spring Boot 4 entirely. Bump only when Vaadin republishes.
- **The assistant `ChatMessage.messageId` is null.** Vaadin's `AIOrchestrator.streamResponseToMessage` builds the assistant turn with `messageId = null`. Anything that dedupes on messageId will silently drop the assistant message — `ConversationService` syncs by list index against `repository.count()` instead.

## Guardrails

- Do not modify `pom.xml` without asking. (Necessary for stack changes; ask first.)
- Do not modify `vite.config.ts` without asking.
- Do not modify `spec/architecture.md` without asking. (Treat the whole `spec/` tree as the source of truth: keep it in sync when behavior changes, but ask before structural rewrites.)

## Implementation status

The first three commits carry the spec. Two prep branches build the runtime backbone and are Playwright-verified:

- `app/mise-prep` — project skeleton + persistent H2 + H2 console.
- `app/mise-prep-2` — AI backbone (Spring AI ↔ Vaadin `AIOrchestrator` ↔ persisted conversation) round-tripped against the live Qwen endpoint, then reloaded after restart.

Use cases UC-001..UC-009 are specified and ready to implement, starting with UC-001 (onboarding) to populate a household.
