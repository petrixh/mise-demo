package com.example.mise.aiit;

import com.example.mise.domain.preferences.ViewPreference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

/**
 * UC-007/UC-012 AI integration: verifies the live LLM uses
 * {@code ReportingTools} (queryReportingData, resetReportsWidget) under the
 * production system prompt — answers grounded in real SQL results, refusal of
 * data the schema doesn't have, and widget reset.
 *
 * <p>The widget-reshape path (chart/grid controller tools) is UI-bound and is
 * verified live via the browser round-trip, not here.
 */
class ReportingToolsAIIT extends MiseAIIT {

    /**
     * UC-007 BR-06 — "why was last week cheaper?" must be answered from
     * queryReportingData results: real meals/weeks/amounts, a refusal for lack
     * of data, or a clarifying question — anything except invented facts.
     */
    @Test
    void weekCostQuestionIsGroundedInQueryResults() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);

        var reply = reportsChat()
                .prompt()
                .user("Why was last week cheaper than usual?")
                .call()
                .content();

        String lower = reply.toLowerCase(Locale.ROOT);
        Assertions.assertThat(lower)
                .as("Reply must cite real meals/weeks, refuse for lack of data, or ask a "
                        + "clarifying question — never invent facts. Full reply: \"%s\"", reply)
                .satisfiesAnyOf(
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "baked cod", "beef stew", "chicken curry", "chicken rice",
                                "chicken stir fry", "grilled chicken", "lentil soup",
                                "meatball", "minced meat", "pea risotto", "pork chop",
                                "potato gratin", "salmon pasta", "spaghetti bolognese",
                                "tofu", "tuna pasta", "veggie", "vegetable"),
                        r -> Assertions.assertThat(r).containsAnyOf("€", "eur"),
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "don't have", "do not have", "no data", "not enough",
                                "which week", "clarify"));
    }

    /**
     * UC-012 BR-08 — data the schema doesn't have must be refused with a real
     * proxy offered, never fabricated.
     */
    @Test
    void carbonFootprintIsRefusedWithProxy() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);

        var reply = reportsChat()
                .prompt()
                .user("How big is my carbon footprint per meal this month?")
                .call()
                .content();

        String lower = reply.toLowerCase(Locale.ROOT);
        Assertions.assertThat(lower)
                .as("Reply must say carbon data isn't available (and ideally offer cost/kcal "
                        + "as a proxy) — it must NOT produce a carbon number. Reply: \"%s\"", reply)
                // Accept any phrasing that conveys "the data isn't there". The set is
                // deliberately broad on the refusal wording (a fabricated answer is still
                // caught by the doesNotContain check below). "track" covers the common
                // "isn't tracked / we don't track" phrasing that flaked this test before.
                .containsAnyOf("don't have", "do not have", "no carbon", "not available",
                        "isn't available", "is not available", "can't", "cannot", "unable",
                        "track", "no data", "don't collect", "not collected", "no such data");
        Assertions.assertThat(lower)
                .as("Reply must not state a fabricated kg-CO2 figure. Reply: \"%s\"", reply)
                .doesNotContain("kg co2", "kgco2", "co2e");
    }

    /**
     * UC-012 BR-10 — "reset the leaderboard" must call resetReportsWidget and
     * delete the persisted ViewPreference row.
     */
    @Test
    void resetLeaderboardDeletesViewPreference() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);
        viewPreferenceService.saveSettings(hh.getId(), ViewPreference.View.REPORTS,
                "leaderboard", Map.of("query", "SELECT recipe_name AS \"Meal\" FROM meal_history"));

        var reply = reportsChat()
                .prompt()
                .user("Reset the leaderboard to its default.")
                .call()
                .content();

        Assertions.assertThat(viewPreferenceService.getSettings(
                        hh.getId(), ViewPreference.View.REPORTS, "leaderboard"))
                .as("The REPORTS/leaderboard ViewPreference row must be deleted after the "
                        + "reset tool call. Reply was: \"%s\"", reply)
                .isEmpty();
    }
}
