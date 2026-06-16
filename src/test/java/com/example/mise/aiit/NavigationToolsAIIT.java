package com.example.mise.aiit;

import com.example.mise.ai.tools.NavigationTools;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UC-008 AI integration: verifies that the live LLM correctly invokes
 * {@code NavigationTools.goToView} when asked to navigate, and that it can chain a
 * navigation call with a follow-up tool call (cross-view command pattern).
 *
 * <p>In {@code webEnvironment=MOCK} there is no real Vaadin UI. We work around this by
 * calling {@link NavigationTools#setNavigationExecutorForTesting} with a simple
 * recording executor before each test. The recording captures every route string passed
 * to {@code goToView}; tests assert on the captured list. The executor is cleared after
 * each test so that the Spring-managed bean is left clean for subsequent tests.
 *
 * <p>BR-04: valid views are "plan", "shopping", "reports" only.
 * BR-05: the goToView tool is always available regardless of active view.
 */
class NavigationToolsAIIT extends MiseAIIT {

    /** Captures routes passed to goToView during each test. Cleared in setUp/tearDown. */
    private final List<String> capturedRoutes = new ArrayList<>();

    @BeforeEach
    void setUpNavigationRecorder() {
        capturedRoutes.clear();
        // Replace the real UI navigation with a recording lambda. The bean is a Spring
        // singleton so this sets a test-only override on the shared instance.
        navigationTools.setNavigationExecutorForTesting(capturedRoutes::add);
    }

    @AfterEach
    void clearNavigationRecorder() {
        // Reset to null so the production path is restored after the test run.
        // The constructor's executorOverride is an AtomicReference; setting null
        // causes the production path (UI supplier) to be used again.
        navigationTools.setNavigationExecutorForTesting(null);
    }

    /**
     * UC-008 AC #1 — "Go to the reports view" must invoke goToView("reports").
     * The recording executor must capture exactly "reports".
     */
    @Test
    void goToReportsCommandInvokesTool() {
        seedHouseholdAndActivePlan();

        var reply = navigationChat()
                .prompt()
                .user("Go to the reports view please.")
                .call()
                .content();

        Assertions.assertThat(capturedRoutes)
                .as("goToView must have been called with 'reports'. "
                        + "Captured routes: %s. Reply: \"%s\"", capturedRoutes, reply)
                .containsAnyOf("reports");
    }

    /**
     * UC-008 AC #2 (navigation half) — a compound command that asks to navigate AND
     * reshape a widget ("go to reports and add a kcal-per-euro column") must still
     * navigate to reports.
     *
     * <p>The reshape half is no longer a tool call: UC-012 replaced the old
     * {@code addLeaderboardColumn} tool with the UI-scoped {@code GridAIController}
     * ({@code update_grid_data}), which is registered at build time on the
     * {@code AIOrchestrator} and is therefore absent from this headless
     * {@code ChatClient}. The derived-column / reshape behaviour is covered by
     * {@code ReportsViewIT#leaderboardKcalPerEuroColumnRestoresOnLoad} at the
     * Playwright layer instead. Here we assert only that the compound phrasing does
     * not suppress the navigation tool call.
     */
    @Test
    void compoundReportsCommandStillNavigates() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);

        var reply = navigationChat()
                .prompt()
                .user("Go to reports and add a kcal-per-euro column to the leaderboard.")
                .call()
                .content();

        Assertions.assertThat(capturedRoutes)
                .as("goToView must have been called with 'reports' even for a compound "
                        + "navigate-and-reshape command. Captured routes: %s. Reply: \"%s\"",
                        capturedRoutes, reply)
                .containsAnyOf("reports");
    }

    /**
     * UC-008 BR-04 — "Go to the dashboard view" asks for a non-existent route.
     * The tool returns REFUSED; no navigation must be captured. The model must relay
     * the refusal or name the valid routes.
     */
    @Test
    void invalidViewNameIsRefused() {
        seedHouseholdAndActivePlan();

        var reply = navigationChat()
                .prompt()
                .user("Go to the dashboard view.")
                .call()
                .content();

        String lower = reply.toLowerCase(Locale.ROOT);

        // No navigation must have been captured.
        Assertions.assertThat(capturedRoutes)
                .as("goToView must NOT have been called for an invalid view 'dashboard'. "
                        + "Captured routes: %s. Reply: \"%s\"", capturedRoutes, reply)
                .doesNotContain("dashboard");

        // The model must either refuse or name valid routes.
        Assertions.assertThat(lower)
                .as("Reply must indicate 'dashboard' is not a valid view, or name valid views. "
                        + "Full reply: \"%s\"", reply)
                .satisfiesAnyOf(
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "not a valid", "invalid", "don't have", "doesn't exist",
                                "no view", "not available", "refused", "can't navigate",
                                "cannot navigate"),
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "plan", "shopping", "reports"));
    }
}
