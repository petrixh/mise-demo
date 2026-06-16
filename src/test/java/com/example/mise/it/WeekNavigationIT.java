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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UC-010 Playwright IT — week navigation header controls (prev / next / badge / DatePicker).
 *
 * <p>Seeding strategy: 3 plans per test —
 * <ul>
 *   <li>HISTORICAL, weekStart = today − 14 days (oldest)</li>
 *   <li>HISTORICAL, weekStart = today − 7 days (middle)</li>
 *   <li>ACTIVE,     weekStart = today's Monday (newest)</li>
 * </ul>
 * Each plan gets 7 Meal rows so PlanView renders a full meal grid and so per-plan
 * recipe-ref assertions are deterministic.
 *
 * <p>Cleanup in {@code @AfterEach} deletes rows in FK-safe order:
 * meal_edit → conversation_message → meal → plan → household.
 *
 * <p><b>Pre-onboarding note:</b> The week-nav controls (prev/next/badge) live in
 * {@code MainLayout}, which is <em>not</em> the layout for {@code /welcome}. When there
 * is no household {@code PlanView.beforeEnter()} immediately redirects to {@code /welcome}
 * (a standalone layout), so the MainLayout header never renders. The pre-onboarding
 * disabled-button and placeholder-label behaviours cannot therefore be asserted at the
 * Playwright IT layer — they are verified at the unit / browserless layer instead.
 */
class WeekNavigationIT extends MisePlaywrightIT {

    private static final DateTimeFormatter WEEK_FMT = DateTimeFormatter.ofPattern("MMM d");

    // ── Spring beans ──────────────────────────────────────────────────────────

    @Autowired
    private HouseholdService householdService;

    @Autowired
    private HouseholdRepository householdRepository;

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

    /** The oldest historical plan (today − 14 days). */
    private Plan oldestPlan;
    /** Middle historical plan (today − 7 days). */
    private Plan middlePlan;
    /** The active plan (today's Monday). */
    private Plan activePlan;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public String getView() {
        return "/plan";
    }

    /**
     * Seeds 3 plans (oldest HISTORICAL, middle HISTORICAL, active ACTIVE) with 7 meals each,
     * then navigates to /plan so the header renders with all three plans present.
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
        oldestPlan = null;
        middlePlan = null;
        activePlan = null;
    }

    // ── Seeding helpers ───────────────────────────────────────────────────────

    private void seedHousehold() {
        Household h = new Household();
        h.setName("IT Week Nav Household");
        h.setSize(2);
        h.setWeeklyBudget(new BigDecimal("100.00"));
        h.setAllergies(List.of());
        h.setHatedFoods(List.of());
        householdService.save(h);

        var household = householdService.findHousehold().orElseThrow();
        LocalDate activeMonday = PlanService.currentWeekMonday();

        oldestPlan = seedPlan(household, activeMonday.minusWeeks(2), Plan.Status.HISTORICAL);
        middlePlan = seedPlan(household, activeMonday.minusWeeks(1), Plan.Status.HISTORICAL);
        activePlan = seedPlan(household, activeMonday,               Plan.Status.ACTIVE);
    }

    /** Creates a Plan + 7 Meal rows using the recipe catalog to populate recipeRef. */
    private Plan seedPlan(Household household, LocalDate weekStart, Plan.Status status) {
        var plan = new Plan();
        plan.setHouseholdId(household.getId());
        plan.setWeekStartDate(weekStart);
        plan.setStatus(status);
        plan = planRepository.save(plan);

        var recipes = recipeCatalog.findAll();
        for (int day = 0; day < 7; day++) {
            var meal = new Meal();
            meal.setPlanId(plan.getId());
            meal.setDate(weekStart.plusDays(day));
            meal.setSlot(Meal.Slot.DINNER);
            meal.setServings(household.getSize());
            meal.setStatus(status == Plan.Status.ACTIVE ? Meal.Status.PLANNED : Meal.Status.COOKED);
            meal.setLastEditedBy(Meal.Editor.USER);
            meal.setRecipeRef(recipes.get(day % recipes.size()).getId());
            mealRepository.save(meal);
        }
        return plan;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * AC: On the ACTIVE plan's week the header renders with prev enabled and next disabled.
     */
    @Test
    void activeWeekHasPrevEnabledAndNextDisabled() {
        // Default navigation lands on the active plan (/plan without ?week=)
        assertThat(page.locator("#mise-week-prev")).isVisible();
        assertThat(page.locator("#mise-week-next")).isVisible();

        // Prev must be enabled — there are 2 earlier plans
        assertThat(page.locator("#mise-week-prev")).not().hasAttribute("disabled", "");
        assertThat(page.locator("#mise-week-prev")).not().hasAttribute("aria-disabled", "true");

        // Next must be disabled — no plan after active
        assertThat(page.locator("#mise-week-next")).hasAttribute("disabled", "");
    }

    /**
     * AC: The week badge shows the active week's Monday formatted as "Week of {MMM d}".
     */
    @Test
    void activeWeekBadgeShowsActivePlanMonday() {
        String expected = "Week of " + activePlan.getWeekStartDate().format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expected);
    }

    /**
     * AC: Clicking prev once changes the badge to show the previous (middle) plan's Monday.
     * Also asserts that the ?week= URL param appears after navigation.
     */
    @Test
    void clickPrevOnceShowsMiddleWeekInBadge() {
        page.locator("#mise-week-prev").click();

        // Wait for badge to update — this implicitly waits for Vaadin navigation to complete
        String expected = "Week of " + middlePlan.getWeekStartDate().format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expected);
    }

    /**
     * AC: After clicking prev once, the ?week= param appears in the URL.
     * Waits for badge update first (guarantees Vaadin navigation has completed).
     */
    @Test
    void weekParamAppearsInUrlAfterPrevClick() {
        page.locator("#mise-week-prev").click();

        // Wait for navigation to complete by asserting the badge first
        String expectedBadge = "Week of " + middlePlan.getWeekStartDate().format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expectedBadge);

        // Now the URL must carry ?week=<middle-monday>
        String url = page.url();
        Assertions.assertThat(url).contains("week=");
        Assertions.assertThat(url).contains(middlePlan.getWeekStartDate().toString());
    }

    /**
     * AC: Clicking prev twice (from active) lands on the oldest plan; prev is disabled, next is enabled.
     */
    @Test
    void clickPrevTwiceLandsOnOldestPlan() {
        page.locator("#mise-week-prev").click();
        // Wait for middle-week navigation before clicking again
        assertThat(page.locator("#mise-week-badge"))
                .containsText("Week of " + middlePlan.getWeekStartDate().format(WEEK_FMT));

        page.locator("#mise-week-prev").click();

        String expected = "Week of " + oldestPlan.getWeekStartDate().format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expected);

        // At the oldest plan: prev must be disabled
        assertThat(page.locator("#mise-week-prev")).hasAttribute("disabled", "");

        // And next must be enabled
        assertThat(page.locator("#mise-week-next")).not().hasAttribute("disabled", "");
        assertThat(page.locator("#mise-week-next")).not().hasAttribute("aria-disabled", "true");
    }

    /**
     * AC: After navigating to the oldest plan, clicking next returns to the middle plan.
     */
    @Test
    void nextButtonNavigatesForwardAfterReachingOldest() {
        // Navigate to oldest — click prev twice, waiting for each navigation
        page.locator("#mise-week-prev").click();
        assertThat(page.locator("#mise-week-badge"))
                .containsText("Week of " + middlePlan.getWeekStartDate().format(WEEK_FMT));
        page.locator("#mise-week-prev").click();
        assertThat(page.locator("#mise-week-badge"))
                .containsText("Week of " + oldestPlan.getWeekStartDate().format(WEEK_FMT));

        // Navigate forward once
        page.locator("#mise-week-next").click();

        String expected = "Week of " + middlePlan.getWeekStartDate().format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expected);
    }

    /**
     * AC: The ?week= param survives a page reload — the badge still shows the same week.
     */
    @Test
    void weekParamPersistsAcrossReload() {
        // Navigate to the middle plan
        page.locator("#mise-week-prev").click();
        String expectedBadge = "Week of " + middlePlan.getWeekStartDate().format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expectedBadge);

        // Verify the URL contains the week param before reloading
        Assertions.assertThat(page.url()).contains(middlePlan.getWeekStartDate().toString());

        // Reload — the URL still has ?week=<middle-monday>
        page.reload();

        assertThat(page.locator("#mise-week-badge")).containsText(expectedBadge);
    }

    /**
     * AC: Clicking the week badge opens the DatePicker overlay.
     */
    @Test
    void clickingBadgeOpensDatePickerOverlay() {
        page.locator("#mise-week-badge").click();

        // The DatePicker overlay element must appear in the DOM and be visible
        assertThat(page.locator("vaadin-date-picker-overlay")).isVisible();
    }

    /**
     * AC: Picking a Wednesday from the DatePicker snaps to that week's Monday (BR-07).
     * The badge must show the Monday after picking a Wednesday.
     *
     * <p>The DatePicker text input is hidden via CSS (only the overlay is shown). We set
     * its value programmatically via JavaScript — Vaadin's Polymer property setter triggers
     * the server-side ValueChangeEvent just as a user selection would.
     */
    @Test
    void pickingWednesdaySnapsToMonday() {
        // The Wednesday that belongs to the oldest plan's week
        LocalDate wednesday = oldestPlan.getWeekStartDate()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));

        // Open the picker via the badge
        page.locator("#mise-week-badge").click();
        assertThat(page.locator("vaadin-date-picker-overlay")).isVisible();

        // Set the picker value programmatically: arg is passed as the second parameter to the
        // arrow function — Playwright's evaluate(expression, arg) pattern.
        String isoWednesday = wednesday.toString();
        page.locator("#mise-week-datepicker").evaluate(
                "(picker, value) => { picker.value = value; }",
                isoWednesday);

        // After snap, the badge must show the Monday of that week (not the Wednesday)
        LocalDate expectedMonday = oldestPlan.getWeekStartDate();
        String expectedBadge = "Week of " + expectedMonday.format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expectedBadge);
    }

    /**
     * AC: Picking the same week the user is currently viewing is a no-op — URL doesn't change.
     */
    @Test
    void pickingSameWeekIsNoOp() {
        // Navigate to middle plan first — wait for navigation
        page.locator("#mise-week-prev").click();
        assertThat(page.locator("#mise-week-badge"))
                .containsText("Week of " + middlePlan.getWeekStartDate().format(WEEK_FMT));
        String urlBefore = page.url();

        // Open picker and set the same Monday that is already being viewed
        page.locator("#mise-week-badge").click();
        assertThat(page.locator("vaadin-date-picker-overlay")).isVisible();

        String sameMonday = middlePlan.getWeekStartDate().toString();
        page.locator("#mise-week-datepicker").evaluate(
                "(picker, value) => { picker.value = value; }",
                sameMonday);

        // Wait briefly for any potential navigation, then assert URL unchanged
        page.waitForTimeout(500);
        String urlAfter = page.url();
        Assertions.assertThat(urlAfter).isEqualTo(urlBefore);
    }

    /**
     * AC: Tab links preserve the ?week= param when switching between Plan and Shopping.
     * After clicking prev on /plan (landing on /plan?week=X), clicking the Shopping tab
     * must navigate to /shopping?week=X.
     */
    @Test
    void tabLinkPreservesWeekParam() {
        // Navigate to the middle (historical) plan — wait for navigation to complete
        page.locator("#mise-week-prev").click();
        String weekStr = middlePlan.getWeekStartDate().toString();
        assertThat(page.locator("#mise-week-badge"))
                .containsText("Week of " + middlePlan.getWeekStartDate().format(WEEK_FMT));
        // Confirm we have a ?week= in URL
        Assertions.assertThat(page.url()).contains("week=" + weekStr);

        // Click the Shopping tab — use the mise-tab class + "Shopping" text to scope
        // away from any accidental matches in page content
        page.locator(".mise-tab").filter(new com.microsoft.playwright.Locator.FilterOptions()
                .setHasText("Shopping")).click();

        // Wait for /shopping to load — title changes
        assertThat(page).hasTitle("Mise — Shopping");

        // The URL must be /shopping?week=<middle-monday>
        String url = page.url();
        Assertions.assertThat(url).contains("/shopping");
        Assertions.assertThat(url).contains("week=" + weekStr);
    }

    /**
     * AC: Navigating directly to /plan?week=<old-monday> renders that older plan's meals
     * in the PlanView (7 meal rows belonging to oldestPlan).
     */
    @Test
    void directWeekUrlRendersOlderPlanMeals() {
        String oldMonday = oldestPlan.getWeekStartDate().toString();
        page.navigate(getUrl() + "/plan?week=" + oldMonday);

        // Badge must show the oldest plan's Monday
        String expected = "Week of " + oldestPlan.getWeekStartDate().format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expected);

        // The meal grid must still render 7 rows (one per day)
        assertThat(page.getByTestId("meal-row")).hasCount(7);
    }

    /**
     * AC: Clicking the brand wordmark navigates to /plan (active week, no ?week= param).
     */
    @Test
    void brandWordmarkNavigatesToActiveWeek() {
        // Go to oldest plan first via direct URL
        page.navigate(getUrl() + "/plan?week=" + oldestPlan.getWeekStartDate());
        assertThat(page.locator("#mise-week-badge"))
                .containsText("Week of " + oldestPlan.getWeekStartDate().format(WEEK_FMT));

        // Click the brand/home wordmark
        page.locator("[data-testid='app-header-wordmark']").click();

        // Must navigate to active week — badge shows active plan's Monday
        String expected = "Week of " + activePlan.getWeekStartDate().format(WEEK_FMT);
        assertThat(page.locator("#mise-week-badge")).containsText(expected);

        // URL must no longer contain a ?week= param (active plan uses clean URL)
        Assertions.assertThat(page.url()).doesNotContain("week=");
    }
}
