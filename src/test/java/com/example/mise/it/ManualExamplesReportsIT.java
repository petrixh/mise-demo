package com.example.mise.it;

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
import com.example.mise.domain.preferences.ViewPreferenceService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.vaadin.addons.dramafinder.AbstractBasePlaywrightIT;
import org.vaadin.addons.dramafinder.element.MessageInputElement;

import java.math.BigDecimal;
import java.util.List;

/**
 * Release-gate verification of the manual's two Reports <b>widget-reshape</b> example queries
 * (#12 chart repoint, #13 leaderboard rank-by-kcal). Those reshapes are driven by Vaadin
 * {@code ChartAIController}/{@code GridAIController} tools that do not exist in the headless AIIT
 * harness — so unlike the other 13 examples (see {@code ManualExamplesAIIT}) they can only be verified
 * end-to-end through the <b>real browser chat dock against the live model</b>.
 *
 * <p>Unlike {@link MisePlaywrightIT} (which forces {@code @ActiveProfiles("it")} + a stub ChatModel),
 * this extends {@link AbstractBasePlaywrightIT} directly under {@code @ActiveProfiles("manual")} so the
 * real {@code OpenAiChatModel} + production Reports controllers are wired (see
 * {@code application-manual.properties}). Tagged {@code manual-example} → runs only under the opt-in
 * {@code -Pmanual-verify} profile.
 *
 * <p><b>Assertion = the state seam, not the canvas.</b> A successful controller-driven reshape persists
 * a SQL query into a {@code ViewPreference} row (the same seam {@code ReportsViewIT} restores from and
 * {@code resetReportsWidget} deletes). We seed with no preferences, send the manual prompt into the dock,
 * then poll the DB until the widget's {@code ViewPreference} appears — robust to the model's exact
 * column alias / chart wording and to streaming timing, and it proves the live model actually reshaped
 * the widget rather than just replying in prose.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("manual")
@Tag("manual-example")
class ManualExamplesReportsIT extends AbstractBasePlaywrightIT {

    /** How long to wait for the live model's turn (tool calls + reshape persist) to land. */
    private static final long REPLY_TIMEOUT_MS = 180_000L;

    @LocalServerPort
    protected int port;

    @Autowired private HouseholdService householdService;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private PlanService planService;
    @Autowired private PlanRepository planRepository;
    @Autowired private MealRepository mealRepository;
    @Autowired private MealEditRepository mealEditRepository;
    @Autowired private RecipeCatalog recipeCatalog;
    @Autowired private ConversationMessageRepository conversationMessageRepository;
    @Autowired private ViewPreferenceRepository viewPreferenceRepository;
    @Autowired private ViewPreferenceService viewPreferenceService;

    private Household household;

    @Override
    public String getUrl() {
        return "http://localhost:" + port;
    }

    @Override
    public String getView() {
        return "/reports";
    }

    @Override
    @BeforeEach
    public void setupTest() throws Exception {
        Household h = new Household();
        h.setName("Manual Reports Household");
        h.setSize(2);
        h.setWeeklyBudget(new BigDecimal("100.00"));
        h.setAllergies(List.of());
        h.setHatedFoods(List.of());
        householdService.save(h);
        household = householdService.findHousehold().orElseThrow();

        // History so the trend/leaderboard have real data to reshape; active week for "current".
        planService.seedHistory(household, 4, recipeCatalog);
        planService.generateActivePlan(household, recipeCatalog);

        super.setupTest(); // navigates to getUrl() + getView() == /reports
    }

    @AfterEach
    void cleanUp() {
        mealEditRepository.deleteAll();
        viewPreferenceRepository.deleteAll();
        conversationMessageRepository.deleteAll();
        householdRepository.findAll().forEach(hh ->
                planRepository.findByHouseholdIdOrderByWeekStartDateDesc(hh.getId())
                        .forEach(plan -> {
                            mealRepository.deleteAll(mealRepository.findByPlanId(plan.getId()));
                            planRepository.delete(plan);
                        }));
        householdRepository.deleteAll();
    }

    // ── #13 — "In the leaderboard, rank by kcal per euro." ─────────────────────
    @Test
    void leaderboardRankByKcalPerEuro_reshapesViaChat() throws InterruptedException {
        Assertions.assertThat(pref("leaderboard"))
                .as("Precondition: the leaderboard must start with no saved ViewPreference").isFalse();

        sendToDock("In the leaderboard, rank by kcal per euro.");

        Assertions.assertThat(pollForPref("leaderboard"))
                .as("Manual example #13: the live model must reshape the leaderboard (update_grid_data), "
                        + "persisting a REPORTS/leaderboard ViewPreference within %d ms.", REPLY_TIMEOUT_MS)
                .isTrue();
    }

    // ── #12 — "Show me a chart of how often I cook vegetarian dinners by month." ─
    // Was the #85 regression: the model emitted MySQL `DATE_FORMAT(...)` against H2 (Function not
    // found), so every chart data-source update failed and the reshape never persisted. Fixed by
    // steering the reporting schema notes to H2's FORMATDATETIME(...) plus a self-correcting error
    // hint in MiseDatabaseProvider (see ReportSnapshotService.SCHEMA_NOTES).
    @Test
    void chartVegetarianByMonth_reshapesViaChat() throws InterruptedException {
        Assertions.assertThat(pref("trendChart") || pref("categoryChart"))
                .as("Precondition: charts must start with no saved ViewPreference").isFalse();

        sendToDock("Show me a chart of how often I cook vegetarian dinners by month.");

        // Accept either chart widget being repointed — the manual documents the trend chart, but the
        // model legitimately may target the category chart; either proves the NL-driven repoint worked.
        Assertions.assertThat(pollForAnyPref("trendChart", "categoryChart"))
                .as("Manual example #12: the live model must repoint a chart (chart controller tool), "
                        + "persisting a REPORTS/{trendChart|categoryChart} ViewPreference within %d ms.",
                        REPLY_TIMEOUT_MS)
                .isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void sendToDock(String prompt) {
        var chatDock = page.getByTestId("chat-dock");
        MessageInputElement.get(chatDock).typeAndSubmit(prompt);
    }

    private boolean pref(String widgetKey) {
        return viewPreferenceService
                .getSettings(household.getId(), ViewPreference.View.REPORTS, widgetKey)
                .isPresent();
    }

    private boolean pollForPref(String widgetKey) throws InterruptedException {
        return pollForAnyPref(widgetKey);
    }

    /** Polls the DB (the reshape persists during the turn) until any of the keys has a preference. */
    private boolean pollForAnyPref(String... widgetKeys) throws InterruptedException {
        long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (String key : widgetKeys) {
                if (pref(key)) {
                    return true;
                }
            }
            Thread.sleep(1000L);
        }
        return false;
    }
}
