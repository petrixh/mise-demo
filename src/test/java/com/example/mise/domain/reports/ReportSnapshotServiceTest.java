package com.example.mise.domain.reports;

import com.example.mise.ai.MiseDatabaseProvider;
import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeMacros;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * UC-012: the reporting snapshot (BR-04 freshness) and the SQL surface guard
 * (BR-03 SELECT-only) in {@link MiseDatabaseProvider}.
 *
 * <p>{@code @Transactional} is load-bearing: plain {@code ./mvnw test} runs
 * against the FILE H2 from application.properties, and rollback is what keeps
 * the {@code deleteAll()} fixtures from wiping the developer database. The
 * snapshot's JdbcTemplate joins the same transaction, so the in-test queries
 * still see the seeded rows.
 */
@SpringBootTest
@org.springframework.transaction.annotation.Transactional
class ReportSnapshotServiceTest {

    @Autowired private ReportSnapshotService snapshotService;
    @Autowired private MiseDatabaseProvider databaseProvider;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private MealRepository mealRepository;
    @Autowired private MealEditRepository mealEditRepository;
    @Autowired private PlanService planService;

    @MockitoBean private RecipeCatalog recipeCatalog;
    @MockitoBean private com.example.mise.capabilities.pricing.PriceCatalog priceCatalog;

    private Household household;
    private static final LocalDate MONDAY = LocalDate.of(2026, 5, 18);

    @BeforeEach
    void setUp() {
        mealEditRepository.deleteAll();
        mealRepository.deleteAll();
        planRepository.deleteAll();
        householdRepository.deleteAll();

        household = householdRepository.save(new Household());

        var pasta = new Recipe();
        pasta.setId("pasta");
        pasta.setName("Spaghetti Bolognese");
        pasta.setCategoryTags(List.of("italian"));
        pasta.setDefaultServings(2);
        pasta.setPrepMinutes(30);
        var macros = new RecipeMacros();
        macros.setKcal(700);
        pasta.setMacros(macros);
        when(recipeCatalog.findById(anyString())).thenReturn(Optional.of(pasta));
        when(priceCatalog.findPrice(anyString())).thenReturn(Optional.empty());
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.empty());
    }

    @Test
    void rebuildPopulatesMealHistoryAndWeeklyKpi() {
        seedPlanWithMeal();
        snapshotService.rebuild();

        var meals = databaseProvider.executeQuery(
                "SELECT recipe_name, day_of_week, prep_minutes FROM meal_history");
        assertThat(meals).hasSize(1);
        assertThat(meals.get(0))
                .containsEntry("RECIPE_NAME", "Spaghetti Bolognese")
                .containsEntry("DAY_OF_WEEK", "Friday")
                .containsEntry("PREP_MINUTES", 30);

        var kpis = databaseProvider.executeQuery("SELECT plan_status FROM weekly_kpi");
        assertThat(kpis).hasSize(1);
        assertThat(kpis.get(0)).containsEntry("PLAN_STATUS", "ACTIVE");
    }

    /** BR-04: a meal mutation between two queries must be visible in the second. */
    @Test
    void snapshotRefreshesAfterMealMutation() {
        var meal = seedPlanWithMeal();
        assertThat(databaseProvider.executeQuery("SELECT recipe_name FROM meal_history"))
                .hasSize(1);

        planService.swapMeal(meal.getId(), "pasta-2", "test mutation");

        var rows = databaseProvider.executeQuery(
                "SELECT status FROM meal_history WHERE meal_id = " + meal.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("STATUS", "EDITED");
        assertThat(databaseProvider.executeQuery("SELECT edit_id FROM meal_edit_history"))
                .hasSize(1);
    }

    /** BR-03: anything but a single SELECT is rejected with a model-actionable error. */
    @Test
    void executeQueryRejectsNonSelect() {
        seedPlanWithMeal();
        for (String bad : List.of(
                "DELETE FROM meal_history",
                "DROP TABLE meal_history",
                "UPDATE meal_history SET est_cost_eur = 0",
                "SELECT 1; DELETE FROM meal_history")) {
            assertThatThrownBy(() -> databaseProvider.executeQuery(bad))
                    .as("must reject: %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SELECT");
        }
    }

    @Test
    void schemaDescribesOnlyReportingTables() {
        String schema = databaseProvider.getSchema();
        assertThat(schema)
                .contains("meal_history", "weekly_kpi", "meal_edit_history", "meal_category_cost")
                .doesNotContain("conversation_message", "household", "pantry");
    }

    private Meal seedPlanWithMeal() {
        var plan = new Plan();
        plan.setHouseholdId(household.getId());
        plan.setWeekStartDate(MONDAY);
        plan.setStatus(Plan.Status.ACTIVE);
        plan = planRepository.save(plan);

        var meal = new Meal();
        meal.setPlanId(plan.getId());
        meal.setDate(MONDAY.plusDays(4)); // Friday
        meal.setSlot(Meal.Slot.DINNER);
        meal.setServings(2);
        meal.setStatus(Meal.Status.PLANNED);
        meal.setRecipeRef("pasta");
        meal.setLastEditedBy(Meal.Editor.USER);
        return mealRepository.save(meal);
    }
}
