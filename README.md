# Mise — Spec-Driven Demo

Mise is a weekly meal planner used as a working demo of a **deeply AI-integrated Vaadin business application**: chat, grid, charts, and dashboard widgets coordinated by one orchestrator over shared state, not a chat bot bolted onto a form.

The product concept and rough mockups live under [`ai-meal-planner/mise/`](ai-meal-planner/mise/). The full specification is in [`spec/`](spec/) — read [`spec/README.md`](spec/README.md) first.

## Stack

- **Java 21** · **Spring Boot 4.0.6** · **Maven** (wrapper included)
- **Vaadin Flow 25.2.0-alpha5** with the **Aura** theme — server-side Java UI
- **Vaadin AI components (preview)** — `AIOrchestrator`, `GridAIController`, `ChartAIController`, gated by the `com.vaadin.experimental.aiComponents` feature flag
- **Spring AI 2.0.0-M4** with the OpenAI starter — default chat model is the local **Qwen3.6-35B-A3B-UD-Q5_K_XL** at `http://192.168.1.196:8080`; any OpenAI-compatible endpoint works
- **Spring Data JPA + H2** (file mode at `./data/mise`) — conversation history and application state persist across restarts; the **H2 console** is reachable at `/h2-console` (also linked from the side drawer)

## Run

```bash
./mvnw                            # dev mode (default goal: spring-boot:run)
./mvnw clean package              # production build (JAR in target/)
./mvnw test                       # all tests
./mvnw test -Dtest=ClassName      # single test class
```

Port defaults to **8080** (override with `PORT`). Open <http://localhost:8080> in a browser; the chat panel and the H2 console link live in the side drawer.

### Pointing at a different chat model

The default model and endpoint are baked into `application.properties` but can be overridden without recompiling:

```bash
MISE_MODEL_BASE_URL=https://api.openai.com \
MISE_MODEL_API_KEY=sk-... \
MISE_MODEL_NAME=gpt-4o-mini \
./mvnw
```

These map to `spring.ai.openai.base-url`, `spring.ai.openai.api-key`, and `spring.ai.openai.chat.options.model` respectively.

### H2 console

The console is registered explicitly (Spring Boot 4 no longer auto-registers it — see [`spec/architecture.md`](spec/architecture.md)).

JDBC URL to type when connecting:

```
jdbc:h2:file:./data/mise;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1
```

User `sa`, no password.

## Repository layout

```
ai-meal-planner/mise/   — product concept + mockups
spec/                   — spec-driven development docs (single source of truth)
  README.md             — process and file overview
  project-context.md
  architecture.md
  datamodel/datamodel.md
  use-cases/            — UC-001..UC-009
  verification.md
src/main/java/com/example/mise/
  Application.java
  ai/                   — HouseholdOrchestrator wrapping Vaadin AIOrchestrator
  config/               — AIConfig (Spring AI → Vaadin LLMProvider) + H2ConsoleConfig
  domain/conversation/  — ConversationMessage JPA + ConversationService
  ui/                   — MainLayout (shared chat panel) + views
src/main/resources/
  application.properties
  vaadin-featureflags.properties  — AI components enabled
data/                   — H2 file-mode database (gitignored)
```

## Current state

The first three commits on `dev-main` carry the spec. Two prep branches build the runtime backbone:

- **`app/mise-prep`** — project skeleton + persistent H2 + H2 console, Playwright-verified.
- **`app/mise-prep-2`** — AI backbone (Spring AI ↔ Vaadin `AIOrchestrator` ↔ persisted conversation), Playwright-verified against the live Qwen endpoint.

Use cases (UC-001..UC-009) are specified and ready to implement.

## Getting started with Vaadin

The [Vaadin Quick Start](https://vaadin.com/docs/v25/getting-started/quick-start) is a good 10-minute orientation for anyone new to Vaadin Flow.
