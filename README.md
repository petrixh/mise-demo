# Mise — Spec-Driven Demo

Mise is a weekly meal planner used as a working demo of a **deeply AI-integrated Vaadin business application**: chat, grid, charts, and dashboard widgets coordinated by one orchestrator over shared state, not a chat bot bolted onto a form.

The product concept and rough mockups live under [`ai-meal-planner/mise/`](ai-meal-planner/mise/). The full specification is in [`spec/`](spec/) — read [`spec/README.md`](spec/README.md) first.

## Stack

- **Java 21** · **Spring Boot 4.0.6** · **Maven** (wrapper included)
- **Vaadin Flow 25.2.0-beta1** with the **Aura** theme — server-side Java UI
- **Vaadin AI components (preview)** — `AIOrchestrator`, `GridAIController`, `ChartAIController`, gated by the `com.vaadin.experimental.aiComponents` feature flag
- **Spring AI 2.0.0-M5** with the OpenAI starter — default chat model is a local **Qwen3.6-35B-A3B-UD-Q5_K_XL** instance; point at any OpenAI-compatible endpoint by copying [`application-local.properties.example`](application-local.properties.example) → `application-local.properties` (gitignored) and editing the URL / model
- **Spring Data JPA + H2** (file mode at `./data/mise`) — conversation history and application state persist across restarts; the **H2 console** is reachable at `/h2-console` (also linked from the side drawer)

## Run

```bash
./mvnw                            # dev mode (default goal: spring-boot:run)
./mvnw clean package              # production build (JAR in target/)
./mvnw test                       # all tests
./mvnw test -Dtest=ClassName      # single test class
```

Port defaults to **8080** (override with `PORT`). Open <http://localhost:8080> in a browser; the chat panel and the H2 console link live in the side drawer.

New to Mise? The **[user manual](docs/manual/mise-manual.pdf)** covers setup, day-to-day usage with example queries, and how to add your own recipes, stores, and personas. (Source: [`docs/manual/mise-manual.typ`](docs/manual/mise-manual.typ) — rebuild with `typst compile docs/manual/mise-manual.typ docs/manual/mise-manual.pdf`.)

### Run with Docker

No JDK, Maven, or checkout needed — a multi-arch image (linux/amd64 + linux/arm64) is published to GHCR on every release tag:

```bash
docker pull ghcr.io/petrixh/mise-demo:latest
```

The only mandatory configuration is the LLM endpoint (any OpenAI-compatible API). A full run with persistence:

```bash
docker run -p 8080:8080 \
  -e MISE_MODEL_BASE_URL=https://api.openai.com/v1 \
  -e MISE_MODEL_API_KEY=sk-... \
  -e MISE_MODEL_NAME=gpt-4o-mini \
  -e MISE_MODEL_MAX_TOKENS=16384 \
  -v mise-data:/data \
  ghcr.io/petrixh/mise-demo:latest
```

- `MISE_MODEL_BASE_URL` **must include the `/v1` path segment** (the OpenAI SDK appends `/chat/completions` directly to it).
- `MISE_MODEL_MAX_TOKENS` defaults to 16384; `PORT` (default 8080) changes the in-container listen port — remember to adjust `-p` to match.
- The H2 database lives at `/data` inside the container; the `-v mise-data:/data` mount keeps plans, pantry, preferences, and conversation history across container restarts. Drop the volume (`docker volume rm mise-data`) to reset to factory state.
- Seed catalogs (recipes, stores, personas) ship inside the image at `/app/demo/data`; mount your own directory over that path to customize them without rebuilding.

To build the image yourself from a checkout (full Vaadin production build inside the container, no Vaadin keys required):

```bash
docker build -t mise .
```

Releasing: pushing a `v*` tag (e.g. `v0.1.0`) triggers [`release.yml`](.github/workflows/release.yml), which builds the JAR once natively and publishes the multi-arch image as `ghcr.io/petrixh/mise-demo:<version>` and `:latest`.

### Run the JAR (no Docker)

Requires **Java 21 or newer** — that's the only prerequisite. Each release workflow run stores a `mise-demo-<version>-jar` artifact (Actions → the Release run for the tag → Artifacts; a GitHub login is needed to download, and artifacts expire after 90 days). The artifact zip contains the runnable JAR plus the `demo/` seed-catalog directory it needs — extract it and run from the extracted directory:

```bash
MISE_MODEL_BASE_URL=https://api.openai.com/v1 \
MISE_MODEL_API_KEY=sk-... \
MISE_MODEL_NAME=gpt-4o-mini \
java -jar mise-demo-<version>.jar
```

The same `MISE_MODEL_*` / `PORT` variables as the Docker run apply (including the `/v1` gotcha). The app serves on <http://localhost:8080>, reads the seed catalogs from `./demo/data`, and persists to `./data` relative to the working directory — re-running from the same directory resumes your data, and deleting `./data` is the factory reset. A JAR built without a Vaadin license shows a watermark banner (vaadin-chart is commercial); that's expected, not a bug.

### Integration (browser) tests

End-to-end tests live next to the unit tests in `src/test/java/.../it/` and are named `*IT.java`. They run a real Chromium under [Microsoft Playwright](https://playwright.dev/java/) against a real Spring Boot server, using [DramaFinder](https://github.com/parttio/dramafinder) wrappers for Vaadin component locators. They are **opt-in via the `it` Maven profile** so plain `./mvnw test` stays fast.

```bash
# one-time per machine: download the Chromium browser
./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.args="install chromium" -Dexec.classpathScope=test

# run all IT tests (headless — works in CI, dev containers, anywhere)
./mvnw -Pit verify

# run a single IT test
./mvnw -Pit verify -Dit.test=HomeViewIT
```

> First run rebuilds the Vaadin frontend bundle (3–5 minutes). Subsequent runs are ~25 seconds.

#### Headed (visible browser) mode

The `debug-ui` profile flips Playwright out of headless mode so you can watch the browser drive itself. **A graphical display is required** — this won't work inside the dev container by default. Run it from a host with a desktop, or wire up Xvfb / a VNC display first.

```bash
./mvnw -Pit -Pdebug-ui verify -Dit.test=HomeViewIT
```

### AI tool tests

`*AIIT.java` tests under `src/test/java/.../aiit/` drive the production tool beans and system prompt through Spring AI's `ChatClient` against the **live LLM** endpoint (no browser, no dev server). They are **opt-in via the `ai-it` Maven profile** and run in two parallel forks, so point at a model endpoint that advertises a `parallel-2` slot. Configure the endpoint in `application-local.properties` first (see [`application-local.properties.example`](application-local.properties.example)).

```bash
# run all AI tool tests against the configured live model
./mvnw -Pai-it verify

# run a single AI tool test
./mvnw -Pai-it verify -Dit.test=PlanToolsAIIT
```

### Pointing at a different chat model

The default model and endpoint are baked into `application.properties` but can be overridden without recompiling:

```bash
MISE_MODEL_BASE_URL=https://api.openai.com/v1 \
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
  use-cases/            — UC-001..UC-012 (+ UC-098 local/Docker run, UC-099 user manual)
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

The spec (`spec/`) covers use cases **UC-001..UC-012** plus UC-098 (local/Docker run) and UC-099 (user manual). On top of the runtime backbone (persistent H2 + H2 console; the Spring AI ↔ Vaadin `AIOrchestrator` ↔ persisted-conversation wiring), the application implements the core meal-planner flows: onboarding, viewing and editing the weekly plan via chat, undo/explain, the shopping list with detour reasoning, dynamic Reports widgets, insights, week navigation, and generating future weeks.

Behaviour is covered across three test layers — unit + browserless (`./mvnw test`), Playwright IT (`./mvnw -Pit verify`), and AI tool IT against the live model (`./mvnw -Pai-it verify`).

## Getting started with Vaadin

The [Vaadin Quick Start](https://vaadin.com/docs/v25/getting-started/quick-start) is a good 10-minute orientation for anyone new to Vaadin Flow.

## License & attributions

This project is licensed under the [Apache License 2.0](LICENSE.md).

Third-party material (see [NOTICE.md](NOTICE.md) for details):

- **Logo & icons** — the Mise logo is the [`tools-kitchen-2`](https://tabler.io/icons/icon/tools-kitchen-2) icon from [Tabler Icons](https://tabler.io/icons) by Paweł Kuna, licensed under the [MIT License](https://github.com/tabler/tabler-icons/blob/main/LICENSE). The design mockups also use Tabler outline icons.
- **DramaFinder** — Playwright element wrappers for Vaadin from [parttio/dramafinder](https://github.com/parttio/dramafinder) (Apache 2.0), used as a test dependency; parts of its documentation are vendored into the [`vaadin-playwright-test`](.claude/skills/vaadin-playwright-test/) skill.
