package com.example.mise.aiit;

import com.example.mise.ai.HouseholdOrchestrator;
import com.example.mise.ai.tools.InsightTools;
import com.example.mise.ai.tools.NavigationTools;
import com.example.mise.ai.tools.PlanTools;
import com.example.mise.ai.tools.ReportingTools;
import com.example.mise.ai.tools.ShoppingTools;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.insights.InsightRepository;
import com.example.mise.domain.insights.InsightService;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.domain.preferences.ViewPreferenceRepository;
import com.example.mise.domain.preferences.ViewPreferenceService;
import com.example.mise.domain.reports.ReportService;
import com.example.mise.domain.shopping.ExtraShoppingItemRepository;
import com.example.mise.domain.shopping.PantryRepository;
import com.example.mise.domain.shopping.ShoppingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

/**
 * Base class for the AI integration tests (`./mvnw -Pai-it verify`).
 *
 * <p>Boots a Spring context with no web environment (no Vaadin UI, no embedded
 * Tomcat), wires the production {@link ChatModel} pointed at the live LLM
 * endpoint, and exposes a {@link #planChat()} helper that returns a
 * {@link ChatClient} primed with the same {@code SYSTEM_PROMPT} and
 * {@link PlanTools} the production {@link HouseholdOrchestrator} uses.
 *
 * <p>The two failsafe forks (see {@code -Pai-it} in pom.xml) each get their own
 * in-memory H2, so subclasses can call {@link #seedHouseholdAndActivePlan()}
 * without worrying about cross-fork interference. Within a fork, tests run
 * sequentially and clean up rows in {@link #cleanUp()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("ai-it")
public abstract class MiseAIIT {

    @Autowired protected ChatModel chatModel;
    @Autowired protected PlanTools planTools;
    @Autowired protected ShoppingTools shoppingTools;
    @Autowired protected ReportingTools reportingTools;
    @Autowired protected NavigationTools navigationTools;
    @Autowired protected InsightTools insightTools;

    @Autowired protected HouseholdService householdService;
    @Autowired protected HouseholdRepository householdRepository;
    @Autowired protected PlanService planService;
    @Autowired protected PlanRepository planRepository;
    @Autowired protected MealRepository mealRepository;
    @Autowired protected MealEditRepository mealEditRepository;
    @Autowired protected ConversationMessageRepository conversationMessageRepository;
    @Autowired protected RecipeCatalog recipeCatalog;
    @Autowired protected PantryRepository pantryRepository;
    @Autowired protected ExtraShoppingItemRepository extraShoppingItemRepository;
    @Autowired protected ShoppingService shoppingService;
    @Autowired protected ReportService reportService;
    @Autowired protected InsightService insightService;
    @Autowired protected ViewPreferenceService viewPreferenceService;
    @Autowired protected ViewPreferenceRepository viewPreferenceRepository;
    @Autowired protected InsightRepository insightRepository;

    @BeforeEach
    protected void wipeBeforeEach() {
        cleanRows();
    }

    @AfterEach
    protected void cleanUp() {
        cleanRows();
    }

    private void cleanRows() {
        mealEditRepository.deleteAll();
        conversationMessageRepository.deleteAll();
        extraShoppingItemRepository.deleteAll();
        pantryRepository.deleteAll();
        insightRepository.deleteAll();
        viewPreferenceRepository.deleteAll();
        householdRepository.findAll().forEach(hh ->
                planRepository.findByHouseholdIdOrderByWeekStartDateDesc(hh.getId())
                        .forEach(plan -> {
                            mealRepository.deleteAll(mealRepository.findByPlanId(plan.getId()));
                            planRepository.delete(plan);
                        })
        );
        householdRepository.deleteAll();
    }

    /**
     * Seeds a household (size 2, €100 budget, no allergies / hated foods) and the
     * current week's active plan through the production codepath
     * ({@link PlanService#generateActivePlan}). Returns the persisted household.
     */
    protected Household seedHouseholdAndActivePlan() {
        var h = new Household();
        h.setName("AIIT Household " + System.nanoTime());
        h.setSize(2);
        h.setWeeklyBudget(new BigDecimal("100.00"));
        h.setAllergies(List.of());
        h.setHatedFoods(List.of());
        var saved = householdService.save(h);
        planService.generateActivePlan(saved, recipeCatalog);
        return saved;
    }

    /**
     * Returns a {@link ChatClient} configured with the production plan-view system
     * prompt and the {@link PlanTools} bean. Builds a fresh client per test — Spring
     * AI's tool-call loop is encapsulated in the client, so a per-test instance has
     * no shared mutable state.
     */
    protected ChatClient planChat() {
        return ChatClient.builder(chatModel)
                .defaultSystem(HouseholdOrchestrator.SYSTEM_PROMPT)
                .defaultTools(planTools)
                .build();
    }

    /**
     * Returns a {@link ChatClient} configured with the production system prompt and
     * BOTH {@link PlanTools} and {@link ShoppingTools} — mirroring the global tool
     * registration used in the production {@link com.example.mise.ai.HouseholdOrchestrator}.
     * Used by UC-005 (and future shopping-related) AI integration tests.
     */
    protected ChatClient shoppingChat() {
        return ChatClient.builder(chatModel)
                .defaultSystem(HouseholdOrchestrator.SYSTEM_PROMPT)
                .defaultTools(planTools, shoppingTools)
                .build();
    }

    /**
     * Returns a {@link ChatClient} with PlanTools + ShoppingTools + ReportingTools.
     * Used by UC-007 AI integration tests.
     */
    protected ChatClient reportsChat() {
        return ChatClient.builder(chatModel)
                .defaultSystem(HouseholdOrchestrator.SYSTEM_PROMPT)
                .defaultTools(planTools, shoppingTools, reportingTools)
                .build();
    }

    /**
     * Returns a {@link ChatClient} with PlanTools + ShoppingTools + ReportingTools + NavigationTools.
     * Used by UC-008 AI integration tests.
     */
    protected ChatClient navigationChat() {
        return ChatClient.builder(chatModel)
                .defaultSystem(HouseholdOrchestrator.SYSTEM_PROMPT)
                .defaultTools(planTools, shoppingTools, reportingTools, navigationTools)
                .build();
    }

    /**
     * Returns a {@link ChatClient} with all tools registered — mirrors the full production set.
     * Used by UC-009 AI integration tests.
     */
    protected ChatClient insightsChat() {
        return ChatClient.builder(chatModel)
                .defaultSystem(HouseholdOrchestrator.SYSTEM_PROMPT)
                .defaultTools(planTools, shoppingTools, reportingTools, navigationTools, insightTools)
                .build();
    }

    /**
     * Seeds {@code weeks} historical plans (HISTORICAL status, COOKED meals) going back
     * from the current Monday. Used by reports/insights tests that need plan history.
     */
    protected void seedFourWeeksHistory(Household h) {
        planService.seedHistory(h, 4, recipeCatalog);
    }
}
