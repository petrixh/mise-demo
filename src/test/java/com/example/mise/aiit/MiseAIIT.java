package com.example.mise.aiit;

import com.example.mise.ai.HouseholdOrchestrator;
import com.example.mise.ai.tools.PlanTools;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;
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

    @Autowired protected HouseholdService householdService;
    @Autowired protected HouseholdRepository householdRepository;
    @Autowired protected PlanService planService;
    @Autowired protected PlanRepository planRepository;
    @Autowired protected MealRepository mealRepository;
    @Autowired protected MealEditRepository mealEditRepository;
    @Autowired protected ConversationMessageRepository conversationMessageRepository;
    @Autowired protected RecipeCatalog recipeCatalog;

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
}
