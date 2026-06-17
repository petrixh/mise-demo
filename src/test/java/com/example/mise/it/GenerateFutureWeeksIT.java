package com.example.mise.it;

import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UC-011 Playwright IT — "generate future weeks" visible surface in the week-navigation header.
 *
 * <p>There is no new route or view in UC-011. All visible side effects are in the existing
 * {@code MainLayout} header week-navigator:
 * <ul>
 *   <li>A PLANNED plan makes the next-chevron ({@code #mise-week-next}) enabled.</li>
 *   <li>Navigating into a PLANNED week renders {@code #mise-week-badge} with the CSS class
 *       {@code mise-week-badge--future}.</li>
 *   <li>The PLANNED week renders 7 {@code meal-row} entries.</li>
 * </ul>
 *
 * <p>Seeding strategy:
 * <ul>
 *   <li>ACTIVE plan — this week's Monday (seeded via {@link PlanService#generateActivePlan}).</li>
 *   <li>PLANNED plan — active Monday + 7 days (seeded via
 *       {@link PlanService#generatePlannedWeeks}).</li>
 * </ul>
 *
 * <p>Cleanup in {@code @AfterEach} deletes rows in FK-safe order:
 * meal_edit → conversation_message → meal → plan → household.
 */
class GenerateFutureWeeksIT extends MisePlaywrightIT {

    private static final DateTimeFormatter WEEK_FMT = DateTimeFormatter.ofPattern("MMM d");

    // ── Spring beans ──────────────────────────────────────────────────────────

    @Autowired
    private HouseholdService householdService;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private PlanService planService;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private MealRepository mealRepository;

    @Autowired
    private MealEditRepository mealEditRepository;

    @Autowired
    private ConversationMessageRepository conversationMessageRepository;

    @Autowired
    private RecipeCatalog recipeCatalog;

    // ── Seeded plan references ────────────────────────────────────────────────

    /** The ACTIVE plan for this week. */
    private Plan activePlan;

    /** The PLANNED plan for the next week. */
    private Plan plannedPlan;

    /** Household seeded per test. */
    private Household household;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public String getView() {
        return "/plan";
    }

    /**
     * Seeds one ACTIVE plan (this week) and one PLANNED plan (next week), then navigates
     * to /plan (the active week) so the header renders with both plans present.
     */
    @Override
    @BeforeEach
    public void setupTest() throws Exception {
        seedHousehold();
        super.setupTest();   // navigates to /plan
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
        activePlan = null;
        plannedPlan = null;
        household = null;
    }

    // ── Seeding helpers ───────────────────────────────────────────────────────

    private void seedHousehold() {
        Household h = new Household();
        h.setName("IT Future Weeks Household");
        h.setSize(2);
        h.setWeeklyBudget(new BigDecimal("100.00"));
        h.setAllergies(List.of());
        h.setHatedFoods(List.of());
        householdService.save(h);

        household = householdService.findHousehold().orElseThrow();

        // Seed ACTIVE plan via PlanService (produces 7 meals, status=ACTIVE)
        activePlan = planService.generateActivePlan(household, recipeCatalog);

        // Seed one PLANNED week via the same service method the chat tool calls
        LocalDate nextMonday = PlanService.currentWeekMonday().plusWeeks(1);
        var result = planService.generatePlannedWeeks(
                household, nextMonday, nextMonday, recipeCatalog);
        Assertions.assertThat(result.created()).hasSize(1);
        plannedPlan = result.created().get(0);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * AC: With an ACTIVE plan + one PLANNED plan, loading /plan (active week) shows the
     * next chevron ENABLED (UC-010 only disables it when there is no newer plan).
     */
    @Test
    void activeWeekNextChevronIsEnabledWhenPlannedPlanExists() {
        // Default navigation lands on the active week (/plan without ?week=)
        assertThat(page.locator("#mise-week-next")).isVisible();

        // Next must be enabled — there is one planned plan ahead
        assertThat(page.locator("#mise-week-next")).not().hasAttribute("disabled", "");
        assertThat(page.locator("#mise-week-next")).not().hasAttribute("aria-disabled", "true");
    }

    /**
     * AC: Clicking #mise-week-next from the active week navigates to the planned week.
     * The badge must show the planned Monday and carry the mise-week-badge--future class.
     */
    @Test
    void clickNextNavigatesToPlannedWeekWithFutureBadge() {
        page.locator("#mise-week-next").click();

        // Wait for navigation: assert the badge text first
        String expectedBadge = "Week of " + plannedPlan.getWeekStartDate().format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expectedBadge);

        // The badge must carry the future modifier class (the spec'd visual contract)
        assertThat(page.locator("#mise-week-badge"))
                .hasClass(Pattern.compile(".*mise-week-badge--future.*"));
    }

    /**
     * AC: Navigating directly to /plan?week={plannedMonday} renders the planned week with
     * the future badge modifier applied.
     */
    @Test
    void directNavigateToPlannedWeekShowsFutureBadge() {
        String plannedMonday = plannedPlan.getWeekStartDate().toString();
        page.navigate(getUrl() + "/plan?week=" + plannedMonday);

        // Badge must show the planned Monday
        String expectedBadge = "Week of " + plannedPlan.getWeekStartDate().format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expectedBadge);

        // Future class must be present
        assertThat(page.locator("#mise-week-badge"))
                .hasClass(Pattern.compile(".*mise-week-badge--future.*"));
    }

    /**
     * AC: The planned week renders exactly 7 meal-row entries (one per day of the week).
     */
    @Test
    void directNavigateToPlannedWeekRendersSevenMealRows() {
        String plannedMonday = plannedPlan.getWeekStartDate().toString();
        page.navigate(getUrl() + "/plan?week=" + plannedMonday);

        // Wait for the badge to confirm the right week loaded
        assertThat(page.locator("#mise-week-badge"))
                .containsText("Week of " + plannedPlan.getWeekStartDate().format(WEEK_FMT));

        // Exactly 7 meal rows must be present
        assertThat(page.getByTestId("meal-row")).hasCount(7);
    }

    /**
     * AC: The seeding path used above (generatePlannedWeeks for the next Monday) creates
     * exactly one new PLANNED plan with 7 meals — proves the service contract the chat tool relies on.
     *
     * <p>This is a repository assertion, not a UI assertion. It validates that the seeding
     * done in @BeforeEach produced the correct data shape in the database.
     */
    @Test
    void generatePlannedWeeksCreatesExactlyOnePlanWithSevenMeals() {
        // The planned plan must have status=PLANNED
        var refreshed = planRepository.findById(plannedPlan.getId()).orElseThrow();
        Assertions.assertThat(refreshed.getStatus()).isEqualTo(Plan.Status.PLANNED);

        // It must be weekStartDate = active Monday + 7 days
        LocalDate expectedMonday = PlanService.currentWeekMonday().plusWeeks(1);
        Assertions.assertThat(refreshed.getWeekStartDate()).isEqualTo(expectedMonday);

        // It must have exactly 7 meals
        var meals = mealRepository.findByPlanId(plannedPlan.getId());
        Assertions.assertThat(meals).hasSize(7);

        // All meals must have status=PLANNED and editor=AI (BR-spec)
        meals.forEach(meal -> {
            Assertions.assertThat(meal.getStatus()).isEqualTo(Meal.Status.PLANNED);
            Assertions.assertThat(meal.getLastEditedBy()).isEqualTo(Meal.Editor.AI);
        });
    }
}
