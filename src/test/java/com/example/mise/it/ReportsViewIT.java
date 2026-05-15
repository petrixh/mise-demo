package com.example.mise.it;

import com.example.mise.ai.tools.ReportsTools;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceRepository;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UC-007 Playwright IT — Reports dashboard view at /reports.
 *
 * <p>Lifecycle: {@code setupTest()} seeds a Household, 4 HISTORICAL plans (to satisfy
 * the "≥4 weeks of data" AC #1), and one ACTIVE plan for the current week, then navigates
 * to /reports. AC-level assertions cover the deterministic UI surface only; LLM-driven
 * paths (natural-language chat triggers) are deferred to Phase 5a.
 *
 * <p>Tool-call seam tests ({@link ReportsTools}) call the Spring bean directly rather
 * than going through the AI, then reload /reports and assert visible state changes.
 *
 * <p>Cleanup in {@code @AfterEach} deletes rows in FK-safe order:
 * meal_edit → view_preference → conversation_message → meal → plan → household.
 */
class ReportsViewIT extends MisePlaywrightIT {

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
    private RecipeCatalog recipeCatalog;

    @Autowired
    private ConversationMessageRepository conversationMessageRepository;

    @Autowired
    private ViewPreferenceRepository viewPreferenceRepository;

    @Autowired
    private ReportsTools reportsTools;

    @Override
    public String getView() {
        return "/reports";
    }

    /**
     * Override: seed Household + 4 HISTORICAL plans + 1 ACTIVE plan BEFORE the base class
     * navigates to /reports. Seeding history satisfies AC #1 ("≥4 weeks of data").
     */
    @Override
    @BeforeEach
    public void setupTest() throws Exception {
        Household h = new Household();
        h.setName("IT Reports Household");
        h.setSize(2);
        h.setWeeklyBudget(new BigDecimal("100.00"));
        h.setAllergies(List.of());
        h.setHatedFoods(List.of());
        householdService.save(h);

        var household = householdService.findHousehold().orElseThrow();

        // Seed 4 HISTORICAL plans so the cost-trend and leaderboard have data
        planService.seedHistory(household, 4, recipeCatalog);

        // Seed the current week's ACTIVE plan
        planService.generateActivePlan(household, recipeCatalog);

        // Navigate AFTER seeding so ReportsView.beforeEnter() finds the household and plans
        super.setupTest();
    }

    @AfterEach
    void cleanUp() {
        // FK-safe order: meal_edit → view_preference → conversation_message → meal → plan → household
        mealEditRepository.deleteAll();
        viewPreferenceRepository.deleteAll();
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

    // ── AC #1: route and page title ───────────────────────────────────────────

    /**
     * AC #1: /reports loads with the correct page title.
     */
    @Test
    void hasPageTitle() {
        assertThat(page).hasTitle("Mise — Reports");
    }

    /**
     * AC #1: the root reports container is visible.
     */
    @Test
    void reportsViewContainerIsVisible() {
        assertThat(page.getByTestId("reports-view")).isVisible();
    }

    // ── AC #1: all three widgets render ───────────────────────────────────────

    /**
     * AC #1: the weekly cost-trend chart container is visible.
     */
    @Test
    void costTrendWidgetIsVisible() {
        assertThat(page.getByTestId("report-cost-trend")).isVisible();
    }

    /**
     * AC #1: the cost-by-category chart container is visible.
     */
    @Test
    void categoryBreakdownWidgetIsVisible() {
        assertThat(page.getByTestId("report-category-breakdown")).isVisible();
    }

    /**
     * AC #1: the per-meal leaderboard grid is visible.
     */
    @Test
    void leaderboardWidgetIsVisible() {
        assertThat(page.getByTestId("leaderboard-grid")).isVisible();
    }

    /**
     * AC #1 composite: all three widget containers are visible in a single check.
     * This mirrors the "allThreeWidgetsRender" specification item.
     */
    @Test
    void allThreeWidgetsRender() {
        assertThat(page.getByTestId("report-cost-trend")).isVisible();
        assertThat(page.getByTestId("report-category-breakdown")).isVisible();
        assertThat(page.getByTestId("leaderboard-grid")).isVisible();
    }

    // ── AC #1: leaderboard has rows ───────────────────────────────────────────

    /**
     * AC #1: given 4 historical + 1 active plan (35 meals total), the leaderboard Grid
     * must have at least 1 rendered row.
     *
     * <p>Vaadin Grid renders data rows inside {@code vaadin-grid-cell-content} elements.
     * We assert the grid is non-empty by checking {@code vaadin-grid-cell-content} count
     * is ≥ 1 scoped to the leaderboard container.
     */
    @Test
    void leaderboardGridHasRows() {
        var leaderboardGrid = page.getByTestId("leaderboard-grid");
        assertThat(leaderboardGrid).isVisible();
        // Vaadin Grid renders row cells as vaadin-grid-cell-content inside the grid element
        int cellCount = leaderboardGrid.locator("vaadin-grid-cell-content").count();
        Assertions.assertThat(cellCount)
                .as("Leaderboard must have at least 1 rendered cell (AC #1: ≥1 row)")
                .isGreaterThanOrEqualTo(1);
    }

    // ── AC #2 + AC #3: add leaderboard column (tool-call seam) ───────────────

    /**
     * AC #2 (tool-call seam): calling {@link ReportsTools#addLeaderboardColumn} directly
     * then reloading /reports causes the "kcal/€" column header to appear in the leaderboard.
     */
    @Test
    void addLeaderboardColumnAddsColumnToGrid() {
        reportsTools.addLeaderboardColumn("kcalPerEuro");

        page.navigate(getUrl() + "/reports");

        // The column header "kcal/€" must be present in the leaderboard widget
        var leaderboardGrid = page.getByTestId("leaderboard-grid");
        assertThat(leaderboardGrid.getByText("kcal/€")).isVisible();
    }

    /**
     * AC #3 (persistence): the "kcal/€" column added via the tool survives a page reload.
     */
    @Test
    void columnSurvivesReload() {
        reportsTools.addLeaderboardColumn("kcalPerEuro");

        // First reload — column is added
        page.navigate(getUrl() + "/reports");
        assertThat(page.getByTestId("leaderboard-grid").getByText("kcal/€")).isVisible();

        // Second reload — column must still be present (BR-04 persistence)
        page.navigate(getUrl() + "/reports");
        assertThat(page.getByTestId("leaderboard-grid").getByText("kcal/€")).isVisible();
    }

    // ── AC #6: reset widget removes added column ───────────────────────────────

    /**
     * AC #6: after adding the "kcal/€" column via the tool, calling
     * {@link ReportsTools#resetWidget} then reloading /reports removes the column.
     */
    @Test
    void resetWidgetRemovesColumn() {
        reportsTools.addLeaderboardColumn("kcalPerEuro");

        // Confirm the column was added
        page.navigate(getUrl() + "/reports");
        assertThat(page.getByTestId("leaderboard-grid").getByText("kcal/€")).isVisible();

        // Reset the leaderboard widget — deletes the ViewPreference row
        reportsTools.resetWidget("leaderboard");

        // Reload — column must be gone
        page.navigate(getUrl() + "/reports");
        assertThat(page.getByTestId("leaderboard-grid").getByText("kcal/€")).not().isVisible();
    }

    // ── AC #4: transform category chart (persistence-level assertion) ──────────

    /**
     * AC #4 (tool-call seam): calling {@link ReportsTools#transformCategoryChart} with
     * chartType="bar" and orientation="horizontal" persists a {@link ViewPreference} row
     * with the correct settings JSON.
     *
     * <p>Visual chart-shape changes are not reliably assertable at the IT layer (Highcharts
     * renders asynchronously into a {@code <canvas>}). The assertion is kept at the
     * persistence layer per the coverage rules.
     */
    @Test
    void transformCategoryChartUpdatesViewPreference() {
        reportsTools.transformCategoryChart("bar", "horizontal");

        var household = householdService.findHousehold().orElseThrow();
        var prefOpt = viewPreferenceRepository.findByHouseholdIdAndViewAndWidgetKey(
                household.getId(), ViewPreference.View.REPORTS, "categoryBreakdown");

        Assertions.assertThat(prefOpt)
                .as("AC #4: ViewPreference row for categoryBreakdown must exist after transform")
                .isPresent();
        Assertions.assertThat(prefOpt.get().getSettings())
                .as("AC #4: settings must record chartType=bar")
                .contains("\"chartType\":\"bar\"");
        Assertions.assertThat(prefOpt.get().getSettings())
                .as("AC #4: settings must record orientation=horizontal")
                .contains("\"orientation\":\"horizontal\"");
    }

    // ── Reset buttons are rendered (UI surface) ───────────────────────────────

    /**
     * AC: the per-widget reset buttons for categoryBreakdown and leaderboard are present
     * in the DOM (even when no customisation has been applied yet, the buttons are always
     * rendered — see {@code buildWidgetShell(hasReset=true)} in ReportsView).
     */
    @Test
    void resetButtonsArePresentForCustomisableWidgets() {
        assertThat(page.getByTestId("report-reset-categoryBreakdown")).isAttached();
        assertThat(page.getByTestId("report-reset-leaderboard")).isAttached();
    }
}
