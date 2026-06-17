package com.example.mise.ai.tools;

import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeMacros;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.ui.ViewedWeekState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression test for issue #79, at the {@link PlanTools} integration level.
 *
 * <p>Unlike {@link PlanToolsTest}, this test wires the <b>real</b>
 * {@link ViewedWeekState} into {@link PlanTools} (it is NOT a {@code @MockitoBean}).
 * That matters: the bug was that the viewed week, written on the UI thread, was
 * invisible to the AI tool thread because {@code ViewedWeekState} read it from
 * {@code VaadinSession}. Mocking {@code ViewedWeekState} (as the older tests do)
 * bypasses that seam and hides the regression. This JVM has no
 * {@code VaadinSession} active, exactly like the production tool thread, so the
 * real holder is exercised honestly.
 */
@SpringBootTest
@Transactional
class PlanToolsViewedWeekGroundingTest {

    @Autowired
    private PlanTools planTools;

    @Autowired
    private ViewedWeekState viewedWeekState; // REAL bean, not mocked — that's the point

    @Autowired
    private HouseholdRepository householdRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private MealRepository mealRepository;

    @MockitoBean
    private RecipeCatalog recipeCatalog;
    @MockitoBean
    private MealCostCalculator mealCostCalculator;

    private static final LocalDate ACTIVE_MONDAY =
            LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    private static final LocalDate PAST_MONDAY = ACTIVE_MONDAY.minusWeeks(3);

    @BeforeEach
    void setUp() {
        mealRepository.deleteAll();
        planRepository.deleteAll();
        householdRepository.deleteAll();
        viewedWeekState.clear();

        var hh = new Household();
        hh.setSize(2);
        hh.setWeeklyBudget(BigDecimal.valueOf(80));
        householdRepository.save(hh);

        // ACTIVE week: Friday meal is "active-fri"
        var active = new Plan();
        active.setHouseholdId(hh.getId());
        active.setWeekStartDate(ACTIVE_MONDAY);
        active.setStatus(Plan.Status.ACTIVE);
        active = planRepository.save(active);
        saveFridayMeal(active.getId(), ACTIVE_MONDAY.plusDays(4), "active-fri");

        // HISTORICAL week (3 weeks ago): Friday meal is "past-fri"
        var past = new Plan();
        past.setHouseholdId(hh.getId());
        past.setWeekStartDate(PAST_MONDAY);
        past.setStatus(Plan.Status.HISTORICAL);
        past = planRepository.save(past);
        saveFridayMeal(past.getId(), PAST_MONDAY.plusDays(4), "past-fri");

        when(recipeCatalog.findById(anyString())).thenAnswer(inv -> Optional.of(buildRecipe(inv.getArgument(0))));
        when(mealCostCalculator.costFor(org.mockito.ArgumentMatchers.any())).thenReturn(BigDecimal.valueOf(10.00));
    }

    @Test
    void noViewedWeek_anchorsToActiveWeek() {
        // Baseline: with nothing selected, "friday" is the active week's Friday.
        assertThat(planTools.resolveDate("friday")).isEqualTo(ACTIVE_MONDAY.plusDays(4));
        assertThat(planTools.findMealOnDay("friday")).contains("active-fri");
    }

    @Test
    void viewingPastWeek_friday_resolvesToThatWeeksFriday() {
        // Simulate MainLayout writing the viewed week on navigation (UI thread).
        viewedWeekState.setViewedMonday(PAST_MONDAY);

        // The tool (as if on the AI thread) must anchor "friday" to the viewed week.
        assertThat(planTools.resolveDate("friday")).isEqualTo(PAST_MONDAY.plusDays(4));
        assertThat(planTools.findMealOnDay("friday"))
                .as("chat must report the VIEWED week's Friday meal, not the active week's")
                .contains("past-fri")
                .doesNotContain("active-fri");
    }

    @Test
    void clearingViewedWeek_returnsToActiveWeek() {
        viewedWeekState.setViewedMonday(PAST_MONDAY);
        viewedWeekState.clear();

        assertThat(planTools.resolveDate("friday")).isEqualTo(ACTIVE_MONDAY.plusDays(4));
        assertThat(planTools.findMealOnDay("friday")).contains("active-fri");
    }

    private void saveFridayMeal(Long planId, LocalDate date, String recipeRef) {
        var meal = new Meal();
        meal.setPlanId(planId);
        meal.setDate(date);
        meal.setSlot(Meal.Slot.DINNER);
        meal.setServings(2);
        meal.setStatus(Meal.Status.PLANNED);
        meal.setRecipeRef(recipeRef);
        meal.setLastEditedBy(Meal.Editor.AI);
        mealRepository.save(meal);
    }

    private Recipe buildRecipe(String id) {
        var recipe = new Recipe();
        recipe.setId(id);
        recipe.setName(id);
        recipe.setPrepMinutes(30);
        recipe.setDefaultServings(2);
        var macros = new RecipeMacros();
        macros.setKcal(500);
        recipe.setMacros(macros);
        return recipe;
    }
}
