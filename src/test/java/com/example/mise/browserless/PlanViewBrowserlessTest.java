package com.example.mise.browserless;

import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.it.support.TestAiConfig;
import com.example.mise.ui.plan.PlanView;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.button.Button;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;

/**
 * Browserless example for /plan — same component tree as production, no Chromium,
 * no failsafe profile. Demonstrates the {@code SpringBrowserlessTest} integration
 * for UC follow-up work where Playwright is overkill (no CSS-rendering / layout
 * concerns, just component-state assertions).
 *
 * <p>Runs as a regular unit test under {@code ./mvnw test}. Reuses the existing
 * {@code "it"} Spring profile so the in-memory H2 + stubbed
 * {@link com.example.mise.it.support.TestChatModel} are wired up identically to
 * the Playwright ITs.
 *
 * <p>Companion AI integration tests live under {@code com.example.mise.aiit.*}
 * and run via {@code ./mvnw -Pai-it verify}; this Browserless layer covers the
 * UI affordances they intentionally skip (component-tree, click handlers).
 */
@SpringBootTest
@ActiveProfiles("it")
@Import(TestAiConfig.class)
class PlanViewBrowserlessTest extends SpringBrowserlessTest {

    @Autowired private HouseholdService householdService;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private PlanService planService;
    @Autowired private PlanRepository planRepository;
    @Autowired private MealRepository mealRepository;
    @Autowired private MealEditRepository mealEditRepository;
    @Autowired private ConversationMessageRepository conversationMessageRepository;
    @Autowired private RecipeCatalog recipeCatalog;

    @BeforeEach
    void seedHouseholdAndPlan() {
        var h = new Household();
        h.setName("Browserless Household");
        h.setSize(2);
        h.setWeeklyBudget(new BigDecimal("100.00"));
        h.setAllergies(List.of());
        h.setHatedFoods(List.of());
        var saved = householdService.save(h);
        planService.generateActivePlan(saved, recipeCatalog);
    }

    @AfterEach
    void cleanUp() {
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
     * UC-004 row-undo click → MealEdit revert + new audit row. Same assertion as
     * {@code PlanViewIT.clickingRowUndoRestoresPreviousRecipeAndWritesUndoAuditRow}
     * but in-process, no browser. Acts as a smoke check that the Browserless
     * harness is wired and the affordance is findable by stable id.
     */
    @Test
    void rowUndoButtonRestoresPreviousRecipeAndWritesAuditRow() {
        // Pre-seed an AI swap on Friday so the row carries edit history.
        var household = householdService.findHousehold().orElseThrow();
        var plan = planService.findActivePlan(household.getId()).orElseThrow();
        var friday = planService.findMeals(plan.getId()).stream()
                .filter(m -> m.getDate().getDayOfWeek() == DayOfWeek.FRIDAY)
                .findFirst().orElseThrow();
        var originalRecipe = friday.getRecipeRef();
        var swapTarget = recipeCatalog.findAll().stream()
                .map(r -> r.getId())
                .filter(id -> !id.equals(originalRecipe))
                .findFirst().orElseThrow();
        planService.swapMeal(friday.getId(), swapTarget, "Browserless setup");

        navigate(PlanView.class);

        var undoBtn = $(Button.class).id("mise-meal-undo-friday");
        test(undoBtn).click();

        var fridayAfter = mealRepository.findById(friday.getId()).orElseThrow();
        var fridayEdits = mealEditRepository.findByMealIdOrderByChangedAtDesc(friday.getId());

        Assertions.assertThat(fridayAfter.getRecipeRef())
                .as("Friday should revert to the pre-swap recipe after the undo click")
                .isEqualTo(originalRecipe);
        Assertions.assertThat(fridayEdits)
                .as("Undo must add its own audit row")
                .hasSizeGreaterThanOrEqualTo(2);
        Assertions.assertThat(fridayEdits.get(0).getReason())
                .as("Undo's audit row reason must start with 'Undo of edit'")
                .startsWith("Undo of edit");
    }
}
