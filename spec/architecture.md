# Architecture

> Technology stack and application structure. `pom.xml` is the source of truth for versions. Do not modify `pom.xml` without asking.

---

## 1. Technology Stack

### Runtime

- **Java 21**
- **Spring Boot 4.x** — auto-configuration, embedded Tomcat
- **Vaadin Flow 25.2 (alpha)** with the **Aura theme** — server-side Java UI. Currently `25.2.0-alpha5` or newer is required for the AI component preview.
- **Maven** (wrapper included). Default goal: `spring-boot:run`.

### AI

- **Vaadin Flow AI components (preview)** — `com.vaadin.flow.component.ai.*`
  - `orchestrator.AIOrchestrator` — non-visual coordination engine; one per household, wired to a `MessageList`, `MessageInput`, optional `UploadManager`, and a set of tools.
  - `provider.LLMProvider` — interface for LLM communication.
  - `provider.SpringAILLMProvider` — concrete provider backed by Spring AI's `ChatClient`. (This project uses Spring AI, **not** the LangChain4J variant from the reference example.)
  - `provider.DatabaseProvider` — exposes a DB connection to the orchestrator so it can run SQL against H2 directly (used by Grid/Chart controllers; we will also use it for ad-hoc reasoning over plan history in Reports).
  - `grid.GridAIController` + `chart.ChartAIController` — AI-driven Grid and Chart wrappers used by Reports.
  - `common.ChatMessage` — used to serialize and restore conversation history.
- **Spring AI 1.x** (`spring-ai-openai-spring-boot-starter`) — provides a `ChatClient`/`ChatModel` configured against any OpenAI-compatible endpoint. Wraps the Qwen local model by default.
- **Feature flag:** `com.vaadin.experimental.aiComponents=true` in `src/main/resources/vaadin-featureflags.properties`.
- **Push:** `@Push` on `Application` is required for streaming chat responses.

### Persistence

- **H2 in file mode** at `./data/mise.mv.db` (configurable via `spring.datasource.url`).
- **Spring Data JPA** — repositories and JPA entities for application state (household, plans, conversation messages, pantry, view preferences, insights).
- **Schema management:** `spring.jpa.hibernate.ddl-auto=update` for the demo. (No Flyway/Liquibase — overkill for the demo's scope.)
- Recipes and store catalogs are **read-only seed data** loaded from YAML at startup into in-memory caches behind their service interfaces; they are not stored in H2 (editing a YAML file and restarting must visibly change AI reasoning — see `concept_demo_notes.md`).

### Testing

- **JUnit 5** (via `spring-boot-starter-test`)
- **Vaadin Browserless Test framework** (`browserless-test-junit6`) for view tests
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
- **One conversation, many views.** A **single `AIOrchestrator` per household** drives one shared `MessageList`/`MessageInput` placed in `MainLayout`. The conversation is persisted to H2 via `ConversationService` and rehydrated on app restart through `AIOrchestrator.builder(...).withHistory(...)`. Each view registers view-specific tools/controllers (e.g. `GridAIController` for Reports) with the same orchestrator when entered, and unregisters on leave.
- **AI output is structured data.** Tools return DTOs. The UI reacts to service-level state changes via Vaadin Signals or repository-event listeners, not by parsing chat text.
- **Capabilities are bounded.** Tools are declared explicitly with `@org.springframework.ai.tool.annotation.Tool`. The AI cannot reach beyond them.
- **Pluggable adapters.** `RecipeCatalog`, `PriceCatalog`, `NutritionEstimator` have file-backed stub implementations. Production replacements (real APIs) drop in behind the same interface without changes to services, orchestrator, or UI.
- **Mobile-first.** `MainLayout` uses `AppLayout` with a bottom navbar on narrow widths and a side drawer at desktop widths. The chat panel docks into a `Popover` on mobile and into a side drawer on desktop. Grid and Chart components opt into responsive variants. Behavior is identical at every form factor.

---

## 3. Application Structure

```
com.example.mise/
  Application.java                       — Spring Boot entry + @Push + @StyleSheet(Aura)
  config/
    AIConfig.java                        — ChatClient + SpringAILLMProvider bean wiring
    ModelProperties.java                 — @ConfigurationProperties("mise.model")
    SeedDataLoader.java                  — CommandLineRunner: load YAML seeds on first run

  ui/
    MainLayout.java                      — AppLayout with nav + shared chat panel
    plan/
      PlanView.java                      — @Route("plan") + @Route("")
      MealGrid.java                      — reusable component (one row per day)
      WeeklyStatsBar.java                — KPIs (cost / prep time / kcal / variety)
    shopping/
      ShoppingView.java                  — @Route("shopping")
      ShoppingListPanel.java             — grouped by aisle, pantry toggle, check-off
      StoreRecommendation.java           — selected store + "worth detour?" hint
    reports/
      ReportsView.java                   — @Route("reports") — Vaadin Dashboard
      CostByCategoryWidget.java          — uses ChartAIController
      LeaderboardWidget.java             — uses GridAIController
    onboarding/
      OnboardingView.java                — @Route("welcome") + chat-only first run
    chat/
      ChatPanel.java                     — shared MessageList + MessageInput (in MainLayout)
      ChatLayouts.java                   — layout factory (mirrors the reference example)

  ai/
    HouseholdOrchestrator.java           — @SessionScope bean wrapping AIOrchestrator
    OrchestratorFactory.java             — builds orchestrator from history + current view tools
    tools/
      PlanTools.java                     — @Tool methods: swapMeal, pinMeal, regenerateWeek, ...
      ShoppingTools.java                 — @Tool methods: markPantry, switchMode, evaluateDetour, ...
      ReportsTools.java                  — @Tool methods: addColumn, transformChart, ...
      PreferencesTools.java              — @Tool methods: setAllergy, setBudget, muteInsights, ...
      ViewNavigationTools.java           — @Tool: goToView (cross-view chat)

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
      ReportService.java + impl
      ViewPreference.java                — JPA entity (chart shape, added columns)
      ViewPreferenceService.java + impl
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
  stores/*.yaml                          — Prima, Lidl, Local Market
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
spring.ai.openai.base-url=${MISE_MODEL_BASE_URL:http://192.168.1.196:8080}
spring.ai.openai.api-key=${MISE_MODEL_API_KEY:not-needed}
spring.ai.openai.chat.options.model=${MISE_MODEL_NAME:Qwen3.6-A3B-UD-Q5_K_XL}
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

    @Bean
    DatabaseProvider databaseProvider(DataSource ds) {
        return new DatabaseProvider(ds);
    }
}

@SpringComponent
@SessionScope
class HouseholdOrchestrator {
    private final AIOrchestrator orchestrator;

    HouseholdOrchestrator(LLMProvider llmProvider,
                          ConversationService conversations,
                          List<Object> toolBeans, // PlanTools, ShoppingTools, ...
                          MessageList messageList,
                          MessageInput messageInput) {
        var history = conversations.loadHistory(currentHouseholdId());
        var builder = AIOrchestrator.builder(llmProvider, SYSTEM_PROMPT)
            .withMessageList(messageList)
            .withInput(messageInput);
        toolBeans.forEach(builder::withTools);
        if (!history.isEmpty()) builder.withHistory(history, Map.of());
        this.orchestrator = builder.build();
        // Persist every assistant/user turn:
        orchestrator.addMessageListener(msg ->
            conversations.append(currentHouseholdId(), msg));
    }

    AIOrchestrator orchestrator() { return orchestrator; }
}
```

Tools are plain Spring beans whose methods carry `@org.springframework.ai.tool.annotation.Tool("...")`. They take and return DTOs; the UI listens to service-level state changes (via Vaadin Signals or `ApplicationEventPublisher`) rather than parsing tool responses.

### View-scoped controllers

Reports widgets attach `GridAIController` / `ChartAIController` to the shared orchestrator when entered (`AfterNavigationObserver`) and detach on leave. The system prompt is augmented for the view ("you are currently in Reports; the user can ask for column additions or chart transforms").

---

## 5. Build & Run

```bash
./mvnw                            # dev mode (default goal: spring-boot:run)
./mvnw clean package              # production build (JAR in target/)
./mvnw test                       # all tests
./mvnw test -Dtest=ClassName      # single test class
```

Port defaults to **8080** (override with `PORT` env var). Application CSS lives at `src/main/resources/META-INF/resources/styles.css`.

---

## 6. Open Questions / Decisions to Revisit

- **Conversation pruning.** Persisting every turn means history grows unbounded. A simple rolling window (e.g. last 50 turns, or last 7 days) likely suffices for the demo; revisit if context-window pressure shows up.
- **Seed re-import flag.** The "edit a YAML, restart, watch AI reasoning change" demo moment requires that capability adapters reload from disk on every restart — confirmed for `RecipeCatalog`/`PriceCatalog` since they cache from YAML, not H2. A dev-mode `mise.seed.reload-on-startup=true` may still be useful if we ever start writing those into H2.
- **Tool granularity.** First pass exposes one tool per high-level user intent (swap meal, evaluate detour, add column). If the model under-uses or over-uses tools, we'll revisit granularity.
