# Architecture

> Technology stack and application structure. `pom.xml` is the source of truth for versions. Do not modify `pom.xml` without asking.

---

## 1. Technology Stack

### Runtime

- **Java 21**
- **Spring Boot 4.x** — auto-configuration, embedded Tomcat
- **Vaadin Flow 25.2 (pre-release)** with the **Aura theme** — server-side Java UI. Currently `25.2.0-beta1` (Maven Central) — the first pre-release that ships the AI-powered Charts and Grids preview used by UC-012.
- **Maven** (wrapper included). Default goal: `spring-boot:run`.

### AI

- **Vaadin Flow AI components (preview)** — `com.vaadin.flow.component.ai.*`
  - `orchestrator.AIOrchestrator` — non-visual coordination engine; one per household, wired to a `MessageList`, `MessageInput`, optional `UploadManager`, and a set of tools.
  - `provider.LLMProvider` — interface for LLM communication.
  - `provider.SpringAILLMProvider` — concrete provider backed by Spring AI's `ChatClient`. (This project uses Spring AI, **not** the LangChain4J variant from the reference example.)
  - `provider.DatabaseProvider` — the AI's SQL surface (`getSchema()` + `executeQuery(sql)`). Implemented by `MiseDatabaseProvider` over the curated reporting snapshot (UC-012); also backs `queryReportingData` for ad-hoc data questions in chat.
  - `grid.GridAIController` + `chart.ChartAIController` — AI-driven Grid and Chart controllers powering the Reports widgets (UC-012). Registered on the orchestrator at build time via `Builder.withController(...)`; their components live in the per-UI `ReportsWidgets` holder and are adopted by `ReportsView` on attach.
  - `common.ChatMessage` — used to serialize and restore conversation history.
- **Spring AI 2** (`spring-ai-starter-model-openai`; exact version pinned in `pom.xml` as `spring-ai.version`) — provides a `ChatClient`/`ChatModel` configured against any OpenAI-compatible endpoint. Wraps the Qwen local model by default. **Pinned to the release the Vaadin AI components are compiled against** (alpha5 → M4, beta1 → M5, rc1 → 2.0.0 GA; check `vaadin-ai-components-flow`'s POM when bumping) — a mismatched release breaks `SpringAILLMProvider` at runtime because advisor builder APIs were renamed between milestones. Spring AI 1.x is incompatible with Spring Boot 4 entirely (references a removed `RestClientAutoConfiguration` class). Bump in lockstep with Vaadin AI releases only.
- **Feature flag:** `com.vaadin.experimental.aiComponents=true` in `src/main/resources/vaadin-featureflags.properties`.
- **Push:** `@Push` on `Application` is required for streaming chat responses.

### Persistence

- **H2 in file mode** at `./data/mise.mv.db` (configurable via `spring.datasource.url`).
- **Spring Data JPA** — repositories and JPA entities for application state (household, plans, conversation messages, pantry, view preferences, insights).
- **Schema management:** `spring.jpa.hibernate.ddl-auto=update` for the demo. (No Flyway/Liquibase — overkill for the demo's scope.)
- **H2 web console** is accessible at `/h2-console` while the app is running and is linked from the side drawer. Spring Boot 4 removed the bundled `H2ConsoleAutoConfiguration`, so `H2ConsoleConfig` registers the `JakartaWebServlet` explicitly at `${spring.h2.console.path}/*` — the bare `spring.h2.console.*` properties on their own are inert.
- Recipes and store catalogs are **read-only seed data** loaded from YAML at startup into in-memory caches behind their service interfaces; they are not stored in H2 (editing a YAML file and restarting must visibly change AI reasoning — see `concept_demo_notes.md`).

### Testing

- **JUnit 5** (via `spring-boot-starter-test`)
- **Vaadin Browserless Test framework** (`browserless-test-junit6`) for view tests
- **DramaFinder + Microsoft Playwright** (test scope, opt-in via the `-Pit` Maven profile) for end-to-end browser tests. ITs live in `src/test/java/com/example/mise/it/*IT.java`, extend `MisePlaywrightIT` (Spring Boot + DramaFinder `AbstractBasePlaywrightIT`), run headless by default, and isolate themselves from the dev DB via an in-memory H2 in `src/test/resources/application-it.properties`. Accidental LLM calls during tests fail fast (endpoint pointed at `http://127.0.0.1:1`).
- **Playwright MCP** for visual verification (see `verification.md`)

---

## 2. Architectural Style

**Service-oriented, layered.** Domain capabilities sit behind Spring `@Service` interfaces; the orchestrator and the UI depend on the interfaces, never the implementations.

```
┌────────────────────────────────────────────────────────────┐
│  Vaadin Flow views (Plan / Shopping / Reports / Onboard)   │
│  + shared chat panel (MessageList + MessageInput)          │
└───────────────┬────────────────────────────────────────────┘
                │ subscribe (Signals / listeners)
┌───────────────▼────────────────────────────────────────────┐
│  AIOrchestrator  (one per household, session-scoped bean)  │
│  • tools = method handles on domain services               │
│  • LLMProvider = SpringAILLMProvider(ChatClient)           │
│  • history persisted via ConversationService               │
└───────────────┬────────────────────────────────────────────┘
                │ tool calls
┌───────────────▼────────────────────────────────────────────┐
│  Domain services (Spring @Service, interfaces)             │
│  PlanService · ShoppingListService · PantryService         │
│  HouseholdService · ConversationService · InsightService   │
│  ViewPreferenceService · ReportService                     │
└───────────────┬────────────────────────────────────────────┘
                │
┌───────────────▼────────────┐   ┌──────────────────────────┐
│  Spring Data JPA (H2 file) │   │  Capability adapters     │
│  household, plan, meal,    │   │  RecipeCatalog (YAML)    │
│  pantry, conversation,     │   │  PriceCatalog (YAML)     │
│  view-prefs, insights      │   │  NutritionEstimator (stub)│
└────────────────────────────┘   └──────────────────────────┘
```

### Principles

- **Orchestrator mediates; it does not store.** All durable state lives in domain services and H2. The orchestrator carries only the in-flight conversation and the tool bindings.
- **One conversation, many views.** A single `AIOrchestrator` drives one shared `MessageList`/`MessageInput` placed in `MainLayout`. The conversation is persisted to H2 via `ConversationService` and rehydrated on app restart through `AIOrchestrator.builder(...).withHistory(...)`. All tools **and** the Reports controllers are registered at orchestrator build time (the API has no runtime attach/detach — `reconnect()` is deserialization-only); the system prompt scopes them to their views, and cross-view requests work in a single turn via `goToView`.
  - **Scope is per-UI** in the demo (the orchestrator binds to the UI's `MessageList`/`MessageInput` in `MainLayout`'s constructor). Conversation persistence via H2 means a second browser tab sees the same chat history — they just get separate live orchestrator instances. Multi-UI conflict is not a stage-1 concern for the single-user demo.
- **AI output is structured data.** Tools return DTOs. The UI reacts to service-level state changes via Vaadin Signals or repository-event listeners, not by parsing chat text.
- **Persist by list position, not by `messageId`.** Vaadin's `AIOrchestrator` builds the assistant `ChatMessage` with `messageId = null` (verified in the orchestrator bytecode at `streamResponseToMessage`). `ConversationService` syncs by appending anything beyond `repository.count()` rather than deduping on messageId, so the assistant turn isn't silently dropped.
- **Capabilities are bounded.** Tools are declared explicitly with `@org.springframework.ai.tool.annotation.Tool`. The AI cannot reach beyond them.
- **Pluggable adapters.** `RecipeCatalog`, `PriceCatalog`, `NutritionEstimator` have file-backed stub implementations. Production replacements (real APIs) drop in behind the same interface without changes to services, orchestrator, or UI.
- **Mobile-first.** `MainLayout` is a single chrome-surface header (brand + week nav + tabs) above one filled per-view panel, with the chat dock fixed to the bottom of the viewport. The header wraps tabs to a second row on mobile; the chat dock stays bottom-anchored at every viewport size. Each routed view (Plan, Shopping, Reports) renders its content as one panel with internal hairline separators rather than a stack of cards — see the design system's "View panel" section. The legacy top-of-view insight banner has been replaced by in-panel insights inside each view (UC-009).

---

## 3. Application Structure

```
com.example.mise/
  Application.java                       — Spring Boot entry + @Push + @StyleSheet(Aura)
  config/
    AIConfig.java                        — SpringAILLMProvider bean wiring (prototype-scoped)
    H2ConsoleConfig.java                 — explicit JakartaWebServlet registration (Spring Boot 4)
    ModelProperties.java                 — @ConfigurationProperties("mise.model")  [planned]
    SeedDataLoader.java                  — CommandLineRunner: load YAML seeds on first run  [planned]

  ui/
    MainLayout.java                      — chrome header (brand logo + wordmark, week nav,
                                           tabs) + view outlet + fixed chat dock; renders
                                           legacy insight banner on /shopping only
    plan/
      PlanView.java                      — @Route("plan") + @Route(""); builds the Plan panel
      WeeklyStatsBar.java                — KPI strip (cost / prep time / kcal / variety)
      MealGrid.java                      — reusable component (one row per day)
      CostByCategoryPanel.java           — sidebar bars + in-panel AI insight (UC-009)
    shopping/
      ShoppingView.java                  — @Route("shopping")
      ShoppingListPanel.java             — grouped by aisle, pantry toggle, check-off
      StoreRecommendation.java           — selected store + "worth detour?" hint
    reports/
      ReportsView.java                   — @Route("reports"); single Reports panel with KPI
                                           strip, chart row, leaderboard, AI insight. Chart
                                           theming lives in applyMiseChartTheme(chart) so all
                                           charts (existing + future) share one canvas/axis/
                                           legend look.
    onboarding/
      OnboardingView.java                — @Route("welcome") + chat-only first run
    chat/
      ChatPanel.java                     — shared MessageList + MessageInput (in MainLayout)
      ChatLayouts.java                   — layout factory (mirrors the reference example)

  ai/
    HouseholdOrchestrator.java           — per-UI wrapper around AIOrchestrator (tools + controllers at build time)
    MiseDatabaseProvider.java            — DatabaseProvider impl over the curated reporting schema (UC-012)
    tools/
      PlanTools.java                     — @Tool methods: swapMeal, pinMeal, negotiateWeek, undo, explain, ...
      ShoppingTools.java                 — @Tool methods: addPantryItem, evaluateDetour, ...
      ReportingTools.java                — @Tool methods: queryReportingData (NL→SQL data questions), resetReportsWidget
      InsightTools.java                  — @Tool methods: muteInsights, dismissCurrentInsight, ...
      NavigationTools.java               — @Tool: goToView (cross-view chat)

  domain/
    household/
      Household.java                     — JPA entity
      HouseholdService.java + impl
      HouseholdRepository.java
    plan/
      Plan.java, Meal.java               — JPA entities
      PlanService.java + impl            — generate, edit, undo
      PlanRepository.java, MealRepository.java
    shopping/
      ShoppingListService.java + impl    — derives list from plan + pantry + stores
      PantryItem.java                    — JPA entity
      PantryService.java + impl
    reports/
      ReportService.java                 — KPI-strip aggregations (cost trend, weekly average)
      ReportSnapshotService.java         — owns + rebuilds the denormalized H2 reporting tables (UC-012)
    preferences/
      ViewPreference.java                — JPA entity (per-widget query + controller state)
      ViewPreferenceService.java
    conversation/
      ConversationMessage.java           — JPA entity
      ConversationService.java + impl    — persist + load history per household
    insights/
      Insight.java                       — JPA entity
      InsightService.java + impl

  capabilities/
    recipes/
      RecipeCatalog.java                 — interface
      Recipe.java, Ingredient.java       — DTOs (not JPA — these are seed-only)
      FileSystemRecipeCatalog.java       — reads demo/data/recipes/*.yaml
    pricing/
      PriceCatalog.java                  — interface
      Store.java, StoreItem.java         — DTOs
      StubbedPriceCatalog.java           — reads demo/data/stores/*.yaml
    nutrition/
      NutritionEstimator.java            — interface
      StubbedNutritionEstimator.java     — canned values; "unknown" otherwise

src/main/resources/
  application.properties                 — Spring + Spring AI config
  application-hosted.properties          — profile: hosted OpenAI-compatible endpoint
  vaadin-featureflags.properties         — com.vaadin.experimental.aiComponents=true
  META-INF/resources/styles.css          — application CSS

demo/data/
  recipes/*.yaml                         — ~80 recipes
  stores/*.yaml                          — Prima, Lido, Local Market
  personas/*.json                        — three sample households
  active_persona.txt                     — points to default persona on first boot

data/                                    — H2 file lives here (gitignored)
```

---

## 4. AI Wiring Details

### Spring AI configuration (default: local Qwen)

`application.properties`:

```properties
# Server
server.port=${PORT:8080}

# Vaadin
vaadin.launch-browser=true
vaadin.allowed-packages=com.vaadin,org.vaadin,com.example.mise

# H2 (file mode, survives restart)
spring.datasource.url=jdbc:h2:file:./data/mise;AUTO_SERVER=TRUE
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=update

# Spring AI — OpenAI-compatible endpoint (default: local Qwen)
spring.ai.openai.base-url=${MISE_MODEL_BASE_URL:http://localhost:8080}
spring.ai.openai.api-key=${MISE_MODEL_API_KEY:not-needed}
spring.ai.openai.chat.options.model=${MISE_MODEL_NAME:Qwen3.6-35B-A3B-UD-Q5_K_XL}
spring.ai.openai.chat.options.temperature=0.2

# Mise demo
mise.seed.directory=demo/data
mise.seed.active-persona-file=demo/data/active_persona.txt
```

Switching to a hosted model is a profile flip (`--spring.profiles.active=hosted`) or three env vars — no code change.

### Orchestrator wiring sketch

```java
@Configuration
class AIConfig {
    @Bean
    LLMProvider llmProvider(ChatClient.Builder builder) {
        return new SpringAILLMProvider(builder.build());
    }

}

// Per-UI: built inside MainLayout's constructor, given the MessageList/MessageInput
// that Vaadin's AIOrchestrator claims. Not a Spring bean — short-lived companion
// to the UI. History persists in H2, so multiple UIs see the same conversation.
public class HouseholdOrchestrator {
    private final AIOrchestrator orchestrator;
    private final ConversationService conversations;

    public HouseholdOrchestrator(LLMProvider provider,
                                 ConversationService conversations,
                                 MessageList messageList,
                                 MessageInput messageInput,
                                 List<AIController> controllers,   // UC-012: Reports chart/grid controllers
                                 Object... tools) {
        this.conversations = conversations;
        var history = conversations.loadHistory();
        var builder = AIOrchestrator.builder(provider, SYSTEM_PROMPT)
            .withMessageList(messageList)
            .withInput(messageInput)
            .withTools(tools)
            .withResponseListener(this::onResponseComplete);
        controllers.forEach(builder::withController);
        if (!history.isEmpty()) builder.withHistory(history, Map.of());
        this.orchestrator = builder.build();
    }

    private void onResponseComplete(ResponseListener.ResponseEvent e) {
        // Index-based sync (assistant ChatMessage.messageId is null — see above).
        conversations.syncFromOrchestrator(orchestrator.getHistory());
    }

    public AIOrchestrator orchestrator() { return orchestrator; }
}
```

Tools are plain Spring beans whose methods carry `@org.springframework.ai.tool.annotation.Tool("...")`. They take and return DTOs; views re-read their data when `ViewRefreshBroadcaster` fires after each AI turn rather than parsing tool responses.

### Reports controllers (UC-012)

`AIOrchestrator` accepts controllers only at build time (`reconnect()` exists solely for the post-deserialization path), so the Reports `Chart`/`Grid` components and their `ChartAIController`/`GridAIController` live in a per-UI `ReportsWidgets` holder created by `MainLayout` before the orchestrator is built. `ReportsView` adopts the components into its layout on attach and applies persisted-or-default states (`restoreState`); the controllers' tools are scoped to `/reports` by the system prompt — the same UC-008 convention as every other view's tools. `MiseDatabaseProvider` is the controllers' SQL surface: it describes only the curated reporting snapshot (rebuilt on demand by `ReportSnapshotService` when a source-table fingerprint changes) and rejects everything but single SELECTs.

---

## 5. Build & Run

```bash
./mvnw                            # dev mode (default goal: spring-boot:run)
./mvnw clean package              # production build (JAR in target/)
./mvnw test                       # unit + browserless-component tests
./mvnw test -Dtest=ClassName      # single test class
./mvnw -Pit verify                # *IT.java browser tests (headless, opt-in)
./mvnw -Pit verify -Dit.test=X    # single IT class
```

Port defaults to **8080** (override with `PORT` env var). Application CSS lives at `src/main/resources/META-INF/resources/styles.css`.

---

## 6. Open Questions / Decisions to Revisit

- **Conversation pruning.** Persisting every turn means history grows unbounded. A simple rolling window (e.g. last 50 turns, or last 7 days) likely suffices for the demo; revisit if context-window pressure shows up.
- **Seed re-import flag.** The "edit a YAML, restart, watch AI reasoning change" demo moment requires that capability adapters reload from disk on every restart — confirmed for `RecipeCatalog`/`PriceCatalog` since they cache from YAML, not H2. A dev-mode `mise.seed.reload-on-startup=true` may still be useful if we ever start writing those into H2.
- **Tool granularity.** First pass exposes one tool per high-level user intent (swap meal, evaluate detour, add column). If the model under-uses or over-uses tools, we'll revisit granularity.
