package com.example.mise.ai.tools;

import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeIngredient;
import com.example.mise.capabilities.recipes.RecipeMacros;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for UC-002 PlanTools date resolution and meal lookup.
 */
@SpringBootTest
@Transactional
class PlanToolsTest {

    @Autowired
    private PlanTools planTools;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private MealRepository mealRepository;

    @MockitoBean
    private RecipeCatalog recipeCatalog;

    // week of 2026-05-11 (Monday)
    private static final LocalDate WEEK_START = LocalDate.of(2026, 5, 11);

    @BeforeEach
    void setUp() {
        mealRepository.deleteAll();
        planRepository.deleteAll();
        householdRepository.deleteAll();

        var household = new Household();
        household.setSize(2);
        household.setWeeklyBudget(BigDecimal.valueOf(80));
        householdRepository.save(household);

        var plan = new Plan();
        plan.setHouseholdId(household.getId());
        plan.setWeekStartDate(WEEK_START);
        plan.setStatus(Plan.Status.ACTIVE);
        var savedPlan = planRepository.save(plan);

        // Add Mon–Sun meals
        String[] recipeIds = {
            "chicken-rice", "beef-stew", "spaghetti", "curry", "salmon", "pork", "risotto"
        };
        for (int i = 0; i < 7; i++) {
            var meal = new Meal();
            meal.setPlanId(savedPlan.getId());
            meal.setDate(WEEK_START.plusDays(i));
            meal.setSlot(Meal.Slot.DINNER);
            meal.setServings(2);
            meal.setStatus(Meal.Status.PLANNED);
            meal.setRecipeRef(recipeIds[i]);
            meal.setLastEditedBy(Meal.Editor.USER);
            mealRepository.save(meal);
        }

        // Mock recipe catalog — return a basic recipe for any id
        when(recipeCatalog.findById(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            var recipe = buildRecipe(id);
            return Optional.of(recipe);
        });
        when(recipeCatalog.findAll()).thenReturn(List.of(buildRecipe("chicken-rice")));
    }

    // ── resolveDate tests ─────────────────────────────────────────────────

    @Test
    void resolveDate_today() {
        LocalDate result = planTools.resolveDate("today");
        assertThat(result).isEqualTo(LocalDate.now());
    }

    @Test
    void resolveDate_tomorrow() {
        LocalDate result = planTools.resolveDate("tomorrow");
        assertThat(result).isEqualTo(LocalDate.now().plusDays(1));
    }

    @Test
    void resolveDate_fullDayName_monday() {
        LocalDate result = planTools.resolveDate("Monday");
        assertThat(result).isNotNull();
        assertThat(result.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
    }

    @Test
    void resolveDate_abbreviation_thu() {
        LocalDate result = planTools.resolveDate("Thu");
        assertThat(result).isNotNull();
        assertThat(result.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.THURSDAY);
    }

    @Test
    void resolveDate_isoDate() {
        LocalDate result = planTools.resolveDate("2026-05-14");
        assertThat(result).isEqualTo(LocalDate.of(2026, 5, 14));
    }

    @Test
    void resolveDate_null_returnsNull() {
        assertThat(planTools.resolveDate(null)).isNull();
        assertThat(planTools.resolveDate("")).isNull();
        assertThat(planTools.resolveDate("  ")).isNull();
    }

    @Test
    void resolveDate_gibberish_returnsNull() {
        assertThat(planTools.resolveDate("blorp")).isNull();
    }

    // ── findMealOnDay tests ───────────────────────────────────────────────

    @Test
    void findMealOnDay_monday_returnsMealName() {
        // Monday of current plan week
        String result = planTools.findMealOnDay(WEEK_START.toString());
        assertThat(result).contains("chicken-rice-name").doesNotContain("No meal");
    }

    @Test
    void findMealOnDay_thursday_returnsCurry() {
        String thursdayDate = WEEK_START.plusDays(3).toString();
        String result = planTools.findMealOnDay(thursdayDate);
        assertThat(result).contains("curry-name");
    }

    @Test
    void findMealOnDay_emptySlot_returnsSentinel() {
        // A date outside the plan week should return sentinel
        String result = planTools.findMealOnDay("2020-01-01");
        assertThat(result).contains("No meal planned");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Recipe buildRecipe(String id) {
        var recipe = new Recipe();
        recipe.setId(id);
        recipe.setName(id + "-name");
        recipe.setCuisine("Test");
        recipe.setCategoryTags(List.of("dinner"));
        recipe.setPrepMinutes(30);
        recipe.setDefaultServings(2);
        recipe.setEstimatedCost(12.0);
        var macros = new RecipeMacros();
        macros.setKcal(500);
        recipe.setMacros(macros);
        var ing = new RecipeIngredient();
        ing.setName("chicken");
        ing.setQuantity(400);
        ing.setUnit("g");
        ing.setAisle("meat");
        recipe.setIngredients(List.of(ing));
        return recipe;
    }
}
