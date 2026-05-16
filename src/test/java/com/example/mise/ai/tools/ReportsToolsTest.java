package com.example.mise.ai.tools;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceRepository;
import com.example.mise.ui.reports.ReportsRefreshBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Tests for ReportsTools @Tool methods (UC-007).
 */
@SpringBootTest
@Transactional
class ReportsToolsTest {

    @Autowired
    private ReportsTools reportsTools;

    @Autowired
    private ReportsRefreshBroadcaster refreshBroadcaster;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private ViewPreferenceRepository viewPreferenceRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private MealRepository mealRepository;

    @Autowired
    private MealEditRepository mealEditRepository;

    @MockitoBean
    private RecipeCatalog recipeCatalog;

    @MockitoBean
    private PriceCatalog priceCatalog;

    private Household household;

    @BeforeEach
    void setUp() {
        mealEditRepository.deleteAll();
        mealRepository.deleteAll();
        planRepository.deleteAll();
        viewPreferenceRepository.deleteAll();
        householdRepository.deleteAll();

        household = new Household();
        household.setSize(2);
        household.setWeeklyBudget(BigDecimal.valueOf(80));
        household = householdRepository.save(household);

        when(priceCatalog.findDefaultStore()).thenReturn(Optional.empty());
        when(priceCatalog.findAllStores()).thenReturn(List.of());
        when(priceCatalog.findPrice(anyString())).thenReturn(Optional.empty());
        when(recipeCatalog.findAll()).thenReturn(List.of());
        when(recipeCatalog.findById(anyString())).thenReturn(Optional.empty());
    }

    // ── addLeaderboardColumn ───────────────────────────────────────────────────

    @Test
    void addLeaderboardColumn_kcalPerEuro_upsertsViewPreferenceWithExtraColumns() {
        String result = reportsTools.addLeaderboardColumn("kcalPerEuro");

        assertThat(result).containsIgnoringCase("Added column kcalPerEuro");

        var pref = viewPreferenceRepository.findByHouseholdIdAndViewAndWidgetKey(
                household.getId(), ViewPreference.View.REPORTS, "leaderboard");
        assertThat(pref).isPresent();
        // Settings JSON should contain "kcalPerEuro" in extraColumns
        assertThat(pref.get().getSettings()).contains("kcalPerEuro");
        assertThat(pref.get().getSettings()).contains("extraColumns");
    }

    @Test
    void addLeaderboardColumn_unsupportedKey_returnsRefused() {
        String result = reportsTools.addLeaderboardColumn("carbonFootprint");

        assertThat(result).startsWith("REFUSED:");
        assertThat(result).containsIgnoringCase("carbonFootprint");
        assertThat(result).containsIgnoringCase("not derivable");

        // No preference row should be created
        var pref = viewPreferenceRepository.findByHouseholdIdAndViewAndWidgetKey(
                household.getId(), ViewPreference.View.REPORTS, "leaderboard");
        assertThat(pref).isEmpty();
    }

    @Test
    void addLeaderboardColumn_calledTwice_doesNotDuplicate() {
        reportsTools.addLeaderboardColumn("kcalPerEuro");
        reportsTools.addLeaderboardColumn("kcalPerEuro");

        var pref = viewPreferenceRepository.findByHouseholdIdAndViewAndWidgetKey(
                household.getId(), ViewPreference.View.REPORTS, "leaderboard");
        assertThat(pref).isPresent();
        // Settings should have exactly one occurrence of kcalPerEuro in the list
        String settings = pref.get().getSettings();
        int count = settings.split("kcalPerEuro", -1).length - 1;
        assertThat(count).isEqualTo(1);
    }

    // ── transformCategoryChart ────────────────────────────────────────────────

    @Test
    void transformCategoryChart_barHorizontal_upsertsViewPreferenceWithCorrectSettings() {
        String result = reportsTools.transformCategoryChart("bar", "horizontal");

        assertThat(result).containsIgnoringCase("horizontal bar");

        var pref = viewPreferenceRepository.findByHouseholdIdAndViewAndWidgetKey(
                household.getId(), ViewPreference.View.REPORTS, "categoryBreakdown");
        assertThat(pref).isPresent();
        assertThat(pref.get().getSettings()).contains("\"chartType\":\"bar\"");
        assertThat(pref.get().getSettings()).contains("\"orientation\":\"horizontal\"");
    }

    @Test
    void transformCategoryChart_donut_upsertsDonutSettings() {
        String result = reportsTools.transformCategoryChart("donut", "vertical");

        assertThat(result).containsIgnoringCase("donut");

        var pref = viewPreferenceRepository.findByHouseholdIdAndViewAndWidgetKey(
                household.getId(), ViewPreference.View.REPORTS, "categoryBreakdown");
        assertThat(pref).isPresent();
        assertThat(pref.get().getSettings()).contains("\"chartType\":\"donut\"");
    }

    // ── resetWidget ───────────────────────────────────────────────────────────

    @Test
    void resetWidget_leaderboard_removesPreferenceRow() {
        // First add a column
        reportsTools.addLeaderboardColumn("kcalPerEuro");
        assertThat(viewPreferenceRepository.findByHouseholdIdAndViewAndWidgetKey(
                household.getId(), ViewPreference.View.REPORTS, "leaderboard")).isPresent();

        // Now reset
        String result = reportsTools.resetWidget("leaderboard");

        assertThat(result).containsIgnoringCase("Reset");
        assertThat(result).containsIgnoringCase("leaderboard");

        // Row should be gone
        assertThat(viewPreferenceRepository.findByHouseholdIdAndViewAndWidgetKey(
                household.getId(), ViewPreference.View.REPORTS, "leaderboard")).isEmpty();
    }

    @Test
    void resetWidget_noPreference_reportsAlreadyDefault() {
        String result = reportsTools.resetWidget("categoryBreakdown");

        assertThat(result).containsIgnoringCase("Already default");
    }

    @Test
    void resetWidget_categoryBreakdown_removesChartPreference() {
        reportsTools.transformCategoryChart("bar", "horizontal");

        String result = reportsTools.resetWidget("categoryBreakdown");

        assertThat(result).containsIgnoringCase("Reset");
        assertThat(viewPreferenceRepository.findByHouseholdIdAndViewAndWidgetKey(
                household.getId(), ViewPreference.View.REPORTS, "categoryBreakdown")).isEmpty();
    }

    // ── explainWeekVsAverage ──────────────────────────────────────────────────

    @Test
    void explainWeekVsAverage_noPlans_reportsNoHistory() {
        String result = reportsTools.explainWeekVsAverage(null);
        // No plans seeded → should report no history
        assertThat(result).containsIgnoringCase("No plan history");
    }

    @Test
    void explainWeekVsAverage_withPlans_includesCatalogNote() {
        // Seed a plan so the analysis runs
        var plan = new com.example.mise.domain.plan.Plan();
        plan.setHouseholdId(household.getId());
        plan.setWeekStartDate(java.time.LocalDate.of(2026, 4, 27));
        plan.setStatus(com.example.mise.domain.plan.Plan.Status.HISTORICAL);
        planRepository.save(plan);

        String result = reportsTools.explainWeekVsAverage("2026-04-27");

        // BR-06: must mention catalog note
        assertThat(result).containsIgnoringCase("current catalog");
    }

    // ── Issue #5: each mutating tool fires the refresh broadcaster ────────────

    @Test
    void mutatingTools_fireRefreshBroadcasterOnCompletion() {
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();
        Runnable hook = fired::incrementAndGet;
        refreshBroadcaster.register(hook);
        try {
            reportsTools.addLeaderboardColumn("kcalPerEuro");
            assertThat(fired.get()).as("addLeaderboardColumn fires refresh").isEqualTo(1);

            reportsTools.transformCategoryChart("bar", "horizontal");
            assertThat(fired.get()).as("transformCategoryChart fires refresh").isEqualTo(2);

            reportsTools.removeLeaderboardColumn("kcalPerEuro");
            assertThat(fired.get()).as("removeLeaderboardColumn fires refresh").isEqualTo(3);

            reportsTools.resetWidget("categoryBreakdown");
            assertThat(fired.get()).as("resetWidget fires refresh").isEqualTo(4);
        } finally {
            refreshBroadcaster.deregister(hook);
        }
    }

    @Test
    void refusedTool_doesNotFireRefresh() {
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();
        Runnable hook = fired::incrementAndGet;
        refreshBroadcaster.register(hook);
        try {
            String result = reportsTools.addLeaderboardColumn("carbonFootprint");
            assertThat(result).startsWith("REFUSED:");
            assertThat(fired.get()).as("refused tool must not fire refresh").isZero();
        } finally {
            refreshBroadcaster.deregister(hook);
        }
    }
}
