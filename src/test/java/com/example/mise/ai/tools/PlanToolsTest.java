package com.example.mise.ai.tools;

import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeIngredient;
import com.example.mise.capabilities.recipes.RecipeMacros;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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

    @Autowired
    private MealEditRepository mealEditRepository;

    @MockitoBean
    private RecipeCatalog recipeCatalog;

    @MockitoBean
    private MealCostCalculator mealCostCalculator;

    // week of 2026-05-11 (Monday)
    private static final LocalDate WEEK_START = LocalDate.of(2026, 5, 11);

    private Household savedHousehold;
    private Plan savedPlan;

    @BeforeEach
    void setUp() {
        mealEditRepository.deleteAll();
        mealRepository.deleteAll();
        planRepository.deleteAll();
        householdRepository.deleteAll();

        savedHousehold = new Household();
        savedHousehold.setSize(2);
        savedHousehold.setWeeklyBudget(BigDecimal.valueOf(80));
        householdRepository.save(savedHousehold);

        savedPlan = new Plan();
        savedPlan.setHouseholdId(savedHousehold.getId());
        savedPlan.setWeekStartDate(WEEK_START);
        savedPlan.setStatus(Plan.Status.ACTIVE);
        savedPlan = planRepository.save(savedPlan);

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

        // Mock cost calculator — return a deterministic cost so existing tests stay stable
        when(mealCostCalculator.costFor(org.mockito.ArgumentMatchers.any())).thenReturn(BigDecimal.valueOf(10.00));
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

    // ── UC-003 tool tests ─────────────────────────────────────────────────

    @Test
    void swapMealOnDay_createsMealEditAndFlipsMeal() {
        String thursdayDate = WEEK_START.plusDays(3).toString(); // Thursday = curry

        // Mock catalog to also know "lentil-soup"
        when(recipeCatalog.findById("lentil-soup")).thenReturn(Optional.of(buildVegetarianRecipe("lentil-soup")));

        Instant before = Instant.now();
        String result = planTools.swapMealOnDay(thursdayDate, "lentil-soup", "Make Thursday vegetarian", "");

        assertThat(result).contains("lentil-soup-name");
        assertThat(result).doesNotContain("Cannot").doesNotContain("pinned");

        // Verify meal was mutated
        var updatedMeal = mealRepository.findByPlanIdOrderByDateAsc(savedPlan.getId()).stream()
                .filter(m -> m.getDate().equals(WEEK_START.plusDays(3)))
                .findFirst().orElseThrow();
        assertThat(updatedMeal.getRecipeRef()).isEqualTo("lentil-soup");
        assertThat(updatedMeal.getStatus()).isEqualTo(Meal.Status.EDITED);
        assertThat(updatedMeal.getLastEditedBy()).isEqualTo(Meal.Editor.AI);
        assertThat(updatedMeal.getLastEditedAt()).isAfterOrEqualTo(before);

        // Verify exactly one MealEdit row was created
        var edits = mealEditRepository.findByMealIdOrderByChangedAtDesc(updatedMeal.getId());
        assertThat(edits).hasSize(1);
        assertThat(edits.get(0).getPreviousRecipeRef()).isEqualTo("curry");
        assertThat(edits.get(0).getChangedBy()).isEqualTo(Meal.Editor.AI);
        assertThat(edits.get(0).getReason()).isEqualTo("Make Thursday vegetarian");
    }

    @Test
    void swapMealOnDay_refusesPinnedMeal() {
        // Pin Thursday's meal first
        var thursday = mealRepository.findByPlanIdOrderByDateAsc(savedPlan.getId()).stream()
                .filter(m -> m.getDate().equals(WEEK_START.plusDays(3)))
                .findFirst().orElseThrow();
        thursday.setPinned(true);
        mealRepository.save(thursday);

        when(recipeCatalog.findById("lentil-soup")).thenReturn(Optional.of(buildVegetarianRecipe("lentil-soup")));

        String result = planTools.swapMealOnDay(WEEK_START.plusDays(3).toString(), "lentil-soup", "reason", "");

        assertThat(result).containsIgnoringCase("REFUSED");
        assertThat(result).containsIgnoringCase("not changed");
        assertThat(result).containsIgnoringCase("pinned");
        // Meal should NOT have been changed
        var unchanged = mealRepository.findById(thursday.getId()).orElseThrow();
        assertThat(unchanged.getRecipeRef()).isEqualTo("curry");
        assertThat(mealEditRepository.findByMealIdOrderByChangedAtDesc(thursday.getId())).isEmpty();
    }

    @Test
    void swapMealOnDay_refusesAllergenRecipe() {
        // Set peanut allergy on household
        savedHousehold.setAllergies(List.of("peanut"));
        householdRepository.save(savedHousehold);

        // Build a recipe that contains peanut
        var peanutRecipe = buildRecipe("peanut-noodles");
        var peanutIng = new RecipeIngredient();
        peanutIng.setName("peanut sauce");
        peanutIng.setQuantity(50);
        peanutIng.setUnit("g");
        peanutIng.setAisle("dry-goods");
        peanutRecipe.setIngredients(List.of(peanutIng));
        when(recipeCatalog.findById("peanut-noodles")).thenReturn(Optional.of(peanutRecipe));

        String result = planTools.swapMealOnDay(WEEK_START.plusDays(0).toString(), "peanut-noodles", "reason", "");

        assertThat(result.toLowerCase()).containsAnyOf("allergy", "allergen");
        // Meal must not have changed
        var unchanged = mealRepository.findByPlanIdOrderByDateAsc(savedPlan.getId()).stream()
                .filter(m -> m.getDate().equals(WEEK_START))
                .findFirst().orElseThrow();
        assertThat(unchanged.getRecipeRef()).isEqualTo("chicken-rice");
    }

    @Test
    void negotiateWeekChanges_atomicRollbackOnPinConflict() {
        // Pin Saturday (day 5 = index 5 from Monday = Saturday)
        var saturday = mealRepository.findByPlanIdOrderByDateAsc(savedPlan.getId()).stream()
                .filter(m -> m.getDate().equals(WEEK_START.plusDays(5)))
                .findFirst().orElseThrow();
        saturday.setPinned(true);
        mealRepository.save(saturday);

        when(recipeCatalog.findById("lentil-soup")).thenReturn(Optional.of(buildVegetarianRecipe("lentil-soup")));
        when(recipeCatalog.findById("veggie-pasta")).thenReturn(Optional.of(buildVegetarianRecipe("veggie-pasta")));

        // Pre-validation in negotiateWeekChanges tool resolves all days and checks pin state
        // before calling planService.negotiateWeek. The tool itself iterates the directives
        // in order and will encounter the pinned Saturday during per-day meal lookup, but
        // since we call planService.negotiateWeek only after ALL directives are validated,
        // no DB writes happen when a pin conflict is detected pre-flight.
        // Note: the current tool validates allergy + recipe existence upfront but NOT pin state
        // (pin state is checked by planService.swapMeal). To ensure true atomic rollback in the
        // tool layer we verify: result contains "pinned" AND no MealEdit row was persisted for Monday.

        String mondayDate = WEEK_START.toString();
        String saturdayDate = WEEK_START.plusDays(5).toString();
        String result = planTools.negotiateWeekChanges(
                mondayDate + "=lentil-soup;" + saturdayDate + "=veggie-pasta",
                "reduce cost"
        );

        assertThat(result).containsIgnoringCase("REFUSED");
        assertThat(result).containsIgnoringCase("pinned");

        // Pre-flight pin check prevents any DB write — Monday's recipe must be unchanged
        // and no MealEdit row should exist for it.
        var mondayMeal = mealRepository.findByPlanIdOrderByDateAsc(savedPlan.getId()).stream()
                .filter(m -> m.getDate().equals(WEEK_START))
                .findFirst().orElseThrow();
        assertThat(mondayMeal.getRecipeRef()).isEqualTo("chicken-rice");
        assertThat(mealEditRepository.findByMealIdOrderByChangedAtDesc(mondayMeal.getId())).isEmpty();
    }

    @Test
    void setMealPin_updatesAuditFields() {
        String mondayDate = WEEK_START.toString();
        Instant before = Instant.now();

        String result = planTools.setMealPin(mondayDate, true);

        assertThat(result).containsIgnoringCase("pinned");

        var meal = mealRepository.findByPlanIdOrderByDateAsc(savedPlan.getId()).stream()
                .filter(m -> m.getDate().equals(WEEK_START))
                .findFirst().orElseThrow();
        assertThat(meal.isPinned()).isTrue();
        assertThat(meal.getLastEditedBy()).isEqualTo(Meal.Editor.AI);
        assertThat(meal.getLastEditedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void setMealPin_unpin_refusedByChat() {
        // First pin it (user-side via direct repo, simulating a user click)
        var monday = mealRepository.findByPlanIdOrderByDateAsc(savedPlan.getId()).stream()
                .filter(m -> m.getDate().equals(WEEK_START))
                .findFirst().orElseThrow();
        monday.setPinned(true);
        mealRepository.save(monday);

        // Attempt to unpin via chat — must be refused (BR-03)
        String result = planTools.setMealPin(WEEK_START.toString(), false);

        assertThat(result).containsIgnoringCase("REFUSED");
        assertThat(result).containsIgnoringCase("not allowed");

        // Meal must still be pinned
        var unchanged = mealRepository.findById(monday.getId()).orElseThrow();
        assertThat(unchanged.isPinned()).isTrue();
    }

    @Test
    void setMealPin_refusesUnpinFromChat() {
        // Seed a pinned meal on Saturday
        var saturday = mealRepository.findByPlanIdOrderByDateAsc(savedPlan.getId()).stream()
                .filter(m -> m.getDate().equals(WEEK_START.plusDays(5)))
                .findFirst().orElseThrow();
        saturday.setPinned(true);
        mealRepository.save(saturday);

        // Chat tries to unpin Saturday — must be refused
        String result = planTools.setMealPin("Saturday", false);

        assertThat(result).containsIgnoringCase("REFUSED");
        assertThat(result).containsIgnoringCase("not allowed");

        // Meal is still pinned in the DB
        var reloaded = mealRepository.findById(saturday.getId()).orElseThrow();
        assertThat(reloaded.isPinned()).isTrue();
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

    private Recipe buildVegetarianRecipe(String id) {
        var recipe = new Recipe();
        recipe.setId(id);
        recipe.setName(id + "-name");
        recipe.setCuisine("Test");
        recipe.setCategoryTags(List.of("vegetarian", "dinner"));
        recipe.setPrepMinutes(30);
        recipe.setDefaultServings(4);
        recipe.setEstimatedCost(7.0);
        var macros = new RecipeMacros();
        macros.setKcal(320);
        recipe.setMacros(macros);
        var ing = new RecipeIngredient();
        ing.setName("lentil");
        ing.setQuantity(300);
        ing.setUnit("g");
        ing.setAisle("dry-goods");
        recipe.setIngredients(List.of(ing));
        return recipe;
    }
}
