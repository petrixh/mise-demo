package com.example.mise.domain.plan;

import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeIngredient;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * UC-011 unit tests for {@link PlanService#generatePlannedWeeks} and
 * {@link PlanService#promoteIfRolledOver}.
 *
 * <p>Uses {@code @Transactional} so each test rolls back, avoiding touching
 * the developer file H2 at {@code ./data/mise}.
 */
@SpringBootTest
@Transactional
class PlanServiceUC011Test {

    @Autowired private PlanService planService;
    @Autowired private PlanRepository planRepository;
    @Autowired private MealRepository mealRepository;
    @Autowired private HouseholdRepository householdRepository;

    @MockitoBean private RecipeCatalog recipeCatalog;

    private Household household;

    /** Monday for the "current" active plan used in most tests. */
    private static final LocalDate ACTIVE_MONDAY = LocalDate.of(2026, 6, 15); // Mon

    @BeforeEach
    void setUp() {
        // Stub the recipe catalog with 10 allergen-free recipes (7 needed per week)
        List<Recipe> recipes = buildRecipes(10);
        when(recipeCatalog.findAll()).thenReturn(recipes);
        when(recipeCatalog.findById(anyString()))
                .thenAnswer(inv -> recipes.stream()
                        .filter(r -> r.getId().equals(inv.getArgument(0)))
                        .findFirst());

        // Persist a household
        household = new Household();
        household.setSize(2);
        household.setAllergies(new ArrayList<>());
        household.setHatedFoods(new ArrayList<>());
        household = householdRepository.save(household);

        // Persist an ACTIVE plan for ACTIVE_MONDAY
        var plan = new Plan();
        plan.setHouseholdId(household.getId());
        plan.setWeekStartDate(ACTIVE_MONDAY);
        plan.setStatus(Plan.Status.ACTIVE);
        planRepository.save(plan);
    }

    // ── generatePlannedWeeks tests ────────────────────────────────────────────

    @Test
    void planNextWeek_createsExactlyOnePlan() {
        LocalDate nextMonday = ACTIVE_MONDAY.plusDays(7);
        List<Plan> created = planService.generatePlannedWeeks(
                household, nextMonday, nextMonday, recipeCatalog).created();

        assertThat(created).hasSize(1);
        Plan p = created.get(0);
        assertThat(p.getStatus()).isEqualTo(Plan.Status.PLANNED);
        assertThat(p.getWeekStartDate()).isEqualTo(nextMonday);
        assertThat(p.getHouseholdId()).isEqualTo(household.getId());
    }

    @Test
    void planNextWeek_creates7Meals() {
        LocalDate nextMonday = ACTIVE_MONDAY.plusDays(7);
        List<Plan> created = planService.generatePlannedWeeks(
                household, nextMonday, nextMonday, recipeCatalog).created();

        assertThat(created).hasSize(1);
        List<Meal> meals = mealRepository.findByPlanId(created.get(0).getId());
        assertThat(meals).hasSize(7);
    }

    @Test
    void planNextWeek_mealsHaveAiEditor_BR04() {
        LocalDate nextMonday = ACTIVE_MONDAY.plusDays(7);
        List<Plan> created = planService.generatePlannedWeeks(
                household, nextMonday, nextMonday, recipeCatalog).created();

        List<Meal> meals = mealRepository.findByPlanId(created.get(0).getId());
        assertThat(meals).allMatch(m -> m.getLastEditedBy() == Meal.Editor.AI);
    }

    @Test
    void planFiveWeeks_createsAllFive() {
        LocalDate from = ACTIVE_MONDAY.plusDays(7);
        LocalDate through = ACTIVE_MONDAY.plusDays(7 * 5);
        List<Plan> created = planService.generatePlannedWeeks(
                household, from, through, recipeCatalog).created();

        assertThat(created).hasSize(5);
        // All have PLANNED status
        assertThat(created).allMatch(p -> p.getStatus() == Plan.Status.PLANNED);
    }

    @Test
    void idempotent_repeatedPlanDoesNotCreate_BR03() {
        LocalDate nextMonday = ACTIVE_MONDAY.plusDays(7);
        // First call: creates 1 plan
        planService.generatePlannedWeeks(household, nextMonday, nextMonday, recipeCatalog).created();
        // Second call: should create 0 (already exists)
        List<Plan> second = planService.generatePlannedWeeks(
                household, nextMonday, nextMonday, recipeCatalog).created();

        assertThat(second).isEmpty();
        // Still only 2 plans total (ACTIVE + 1 PLANNED)
        assertThat(planRepository.findByHouseholdIdOrderByWeekStartDateDesc(household.getId()))
                .hasSize(2);
    }

    @Test
    void cap8Weeks_generatesAtMost8_BR05() {
        LocalDate from = ACTIVE_MONDAY.plusDays(7);
        LocalDate through = ACTIVE_MONDAY.plusDays(7 * 12); // 12 weeks requested
        List<Plan> created = planService.generatePlannedWeeks(
                household, from, through, recipeCatalog).created();

        assertThat(created).hasSize(8);
    }

    @Test
    void skippedExistingWeekDoesNotCountTowardCap_BR05() {
        // Pre-create weeks 1 and 2 (they'll be skipped)
        LocalDate week1 = ACTIVE_MONDAY.plusDays(7);
        LocalDate week2 = ACTIVE_MONDAY.plusDays(14);
        planService.generatePlannedWeeks(household, week1, week2, recipeCatalog).created();

        // Now request 10 weeks starting from week 1: weeks 1+2 are skipped,
        // cap of 8 applies only to NEW ones → should create 8 more (weeks 3–10)
        LocalDate through = ACTIVE_MONDAY.plusDays(7 * 10);
        List<Plan> created = planService.generatePlannedWeeks(
                household, week1, through, recipeCatalog).created();

        assertThat(created).hasSize(8);
        // All newly created plans start from week 3 or later
        assertThat(created).allMatch(p -> !p.getWeekStartDate().isBefore(ACTIVE_MONDAY.plusDays(21)));
    }

    @Test
    void pastWeekRequest_returnsEmpty_BR02() {
        // Request to plan the ACTIVE week (should be refused — below earliest allowed)
        List<Plan> created = planService.generatePlannedWeeks(
                household, ACTIVE_MONDAY, ACTIVE_MONDAY, recipeCatalog).created();

        assertThat(created).isEmpty();
    }

    @Test
    void allergyFilter_shellfishIngredient_neverInPlannedMeals_BR04() {
        // Create 9 allergy-free recipes + 1 that contains "shellfish" as an ingredient name
        List<Recipe> recipes = buildRecipes(9);
        Recipe shellfishRecipe = buildRecipe("shellfish-pasta", "Shellfish Pasta");
        RecipeIngredient shellfish = new RecipeIngredient();
        shellfish.setName("shellfish");  // ingredient name MUST match allergen term exactly
        shellfish.setQuantity(200.0);
        shellfish.setOptional(false);
        shellfishRecipe.setIngredients(List.of(shellfish));
        List<Recipe> allRecipes = new ArrayList<>(recipes);
        allRecipes.add(shellfishRecipe);

        when(recipeCatalog.findAll()).thenReturn(allRecipes);

        // Household with shellfish allergy
        household.setAllergies(List.of("shellfish"));
        householdRepository.save(household);

        LocalDate nextMonday = ACTIVE_MONDAY.plusDays(7);
        List<Plan> created = planService.generatePlannedWeeks(
                household, nextMonday, nextMonday, recipeCatalog).created();

        assertThat(created).hasSize(1);
        List<Meal> meals = mealRepository.findByPlanId(created.get(0).getId());
        assertThat(meals).hasSize(7);
        // None of the meals should reference the shellfish-containing recipe (hard allergy filter)
        assertThat(meals).noneMatch(m -> m.getRecipeRef().equals("shellfish-pasta"));
    }

    // ── promoteIfRolledOver / findActivePlan tests ────────────────────────────

    @Test
    void findActivePlan_returnsCurrentActive_whenNoRollover() {
        var active = planService.findActivePlan(household.getId());
        assertThat(active).isPresent();
        assertThat(active.get().getWeekStartDate()).isEqualTo(ACTIVE_MONDAY);
        assertThat(active.get().getStatus()).isEqualTo(Plan.Status.ACTIVE);
    }

    @Test
    void promoteIfRolledOver_promotesPlannedPlan_whenActiveWeekHasEnded() {
        // Simulate the active week ending: move ACTIVE_MONDAY to 2 weeks in the past
        LocalDate oldMonday = LocalDate.now().minusWeeks(2).with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate plannedMonday = LocalDate.now().with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

        // Remove the existing ACTIVE plan and create a new one in the past
        planRepository.deleteAll(
                planRepository.findByHouseholdIdOrderByWeekStartDateDesc(household.getId()));

        var staleActive = new Plan();
        staleActive.setHouseholdId(household.getId());
        staleActive.setWeekStartDate(oldMonday);
        staleActive.setStatus(Plan.Status.ACTIVE);
        planRepository.save(staleActive);

        var plannedPlan = new Plan();
        plannedPlan.setHouseholdId(household.getId());
        plannedPlan.setWeekStartDate(plannedMonday);
        plannedPlan.setStatus(Plan.Status.PLANNED);
        planRepository.save(plannedPlan);

        // Trigger the promotion
        planService.promoteIfRolledOver(household.getId());

        // Stale ACTIVE → HISTORICAL
        var refreshedOld = planRepository.findById(staleActive.getId()).orElseThrow();
        assertThat(refreshedOld.getStatus()).isEqualTo(Plan.Status.HISTORICAL);

        // PLANNED → ACTIVE
        var refreshedPlanned = planRepository.findById(plannedPlan.getId()).orElseThrow();
        assertThat(refreshedPlanned.getStatus()).isEqualTo(Plan.Status.ACTIVE);

        // Invariant: exactly one ACTIVE
        var activePlans = planRepository.findByHouseholdIdAndStatusIn(
                household.getId(), List.of(Plan.Status.ACTIVE));
        assertThat(activePlans).hasSize(1);
    }

    @Test
    void promoteIfRolledOver_demotesElapsedPlannedWeeks() {
        // Set up: ACTIVE 3 weeks ago, PLANNED 2 weeks ago (also elapsed), PLANNED this week
        LocalDate activeMonday = LocalDate.now().minusWeeks(3).with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate elapsedPlannedMonday = LocalDate.now().minusWeeks(2).with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate currentMonday = LocalDate.now().with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

        // Skip if calculated weeks happen to be the same (edge case on Monday)
        if (activeMonday.equals(elapsedPlannedMonday) || elapsedPlannedMonday.equals(currentMonday)) {
            return; // skip this test in edge timing window
        }

        planRepository.deleteAll(
                planRepository.findByHouseholdIdOrderByWeekStartDateDesc(household.getId()));

        var oldActive = new Plan();
        oldActive.setHouseholdId(household.getId());
        oldActive.setWeekStartDate(activeMonday);
        oldActive.setStatus(Plan.Status.ACTIVE);
        planRepository.save(oldActive);

        var elapsedPlanned = new Plan();
        elapsedPlanned.setHouseholdId(household.getId());
        elapsedPlanned.setWeekStartDate(elapsedPlannedMonday);
        elapsedPlanned.setStatus(Plan.Status.PLANNED);
        planRepository.save(elapsedPlanned);

        var currentPlanned = new Plan();
        currentPlanned.setHouseholdId(household.getId());
        currentPlanned.setWeekStartDate(currentMonday);
        currentPlanned.setStatus(Plan.Status.PLANNED);
        planRepository.save(currentPlanned);

        planService.promoteIfRolledOver(household.getId());

        // Old ACTIVE → HISTORICAL
        assertThat(planRepository.findById(oldActive.getId()).orElseThrow().getStatus())
                .isEqualTo(Plan.Status.HISTORICAL);
        // Elapsed PLANNED (entire week in past) → HISTORICAL
        assertThat(planRepository.findById(elapsedPlanned.getId()).orElseThrow().getStatus())
                .isEqualTo(Plan.Status.HISTORICAL);
        // Current PLANNED → ACTIVE
        assertThat(planRepository.findById(currentPlanned.getId()).orElseThrow().getStatus())
                .isEqualTo(Plan.Status.ACTIVE);

        // Exactly one ACTIVE remains
        assertThat(planRepository.findByHouseholdIdAndStatusIn(
                household.getId(), List.of(Plan.Status.ACTIVE))).hasSize(1);
    }

    @Test
    void promoteIfRolledOver_noPlannedPlan_keepsStaleActive_BR06_invariant() {
        // Gap case: the active week has ended but no PLANNED plan covers the current week.
        // UC-002 BR-01 / UC-011 BR-06 require "exactly one ACTIVE plan at all times", so the
        // sweep must NOT drop to zero ACTIVE plans — it leaves the (now stale) ACTIVE in place
        // rather than demoting it with nothing to promote.
        LocalDate oldMonday = LocalDate.now().minusWeeks(2).with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

        planRepository.deleteAll(
                planRepository.findByHouseholdIdOrderByWeekStartDateDesc(household.getId()));

        var staleActive = new Plan();
        staleActive.setHouseholdId(household.getId());
        staleActive.setWeekStartDate(oldMonday);
        staleActive.setStatus(Plan.Status.ACTIVE);
        planRepository.save(staleActive);

        planService.promoteIfRolledOver(household.getId());

        // Stale ACTIVE stays ACTIVE (no PLANNED plan to promote) — invariant preserved.
        assertThat(planRepository.findById(staleActive.getId()).orElseThrow().getStatus())
                .isEqualTo(Plan.Status.ACTIVE);
        // Exactly one ACTIVE plan still present.
        assertThat(planRepository.findByHouseholdIdAndStatusIn(
                household.getId(), List.of(Plan.Status.ACTIVE))).hasSize(1);
    }

    @Test
    void singleActiveInvariant_preservedAfterGeneration_BR01() {
        // Generate 3 future planned weeks
        LocalDate from = ACTIVE_MONDAY.plusDays(7);
        LocalDate through = ACTIVE_MONDAY.plusDays(21);
        planService.generatePlannedWeeks(household, from, through, recipeCatalog).created();

        // Still exactly one ACTIVE plan
        var activePlans = planRepository.findByHouseholdIdAndStatusIn(
                household.getId(), List.of(Plan.Status.ACTIVE));
        assertThat(activePlans).hasSize(1);
        assertThat(activePlans.get(0).getWeekStartDate()).isEqualTo(ACTIVE_MONDAY);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<Recipe> buildRecipes(int count) {
        List<Recipe> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(buildRecipe("recipe-" + i, "Recipe " + i));
        }
        return list;
    }

    private Recipe buildRecipe(String id, String name) {
        Recipe r = new Recipe();
        r.setId(id);
        r.setName(name);
        r.setPrepMinutes(30);
        r.setDefaultServings(2);
        r.setIngredients(List.of()); // no allergens
        return r;
    }
}
