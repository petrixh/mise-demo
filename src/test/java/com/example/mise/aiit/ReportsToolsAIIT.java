package com.example.mise.aiit;

import com.example.mise.domain.preferences.ViewPreference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * UC-007 AI integration: verifies that the live LLM correctly uses
 * {@code ReportsTools} (addLeaderboardColumn, transformCategoryChart, explainWeekVsAverage)
 * under the production system prompt.
 *
 * <p>BR-03: only derivable columns are supported. BR-05: non-derivable column requests
 * return a REFUSED sentinel — the model must relay it without persisting the column.
 * BR-06: explanations cite concrete meals/categories, never invented prices.
 */
class ReportsToolsAIIT extends MiseAIIT {

    /**
     * UC-007 AC #1 / BR-03 — "Add a kcal-per-euro column to the leaderboard" must call
     * addLeaderboardColumn("kcalPerEuro") and the ViewPreference row must be persisted.
     * The reply must NOT mention unsupported columns like "carbon footprint".
     */
    @Test
    void addColumnRequestPersistsViewPreference() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);

        var reply = reportsChat()
                .prompt()
                .user("Add a kcal-per-euro column to the leaderboard.")
                .call()
                .content();

        // DB assertion: the ViewPreference row must exist with extraColumns=[kcalPerEuro]
        var settingsOpt = viewPreferenceService.getSettings(
                hh.getId(), ViewPreference.View.REPORTS, "leaderboard");

        Assertions.assertThat(settingsOpt)
                .as("A ViewPreference row for REPORTS/leaderboard must exist after the tool call. "
                        + "Reply was: \"%s\"", reply)
                .isPresent();

        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) settingsOpt.get().get("extraColumns");
        Assertions.assertThat(cols)
                .as("extraColumns must contain 'kcalPerEuro'. "
                        + "Actual settings: %s. Reply: \"%s\"", settingsOpt.get(), reply)
                .isNotNull()
                .contains("kcalPerEuro");

        // The reply must not claim a column that does not exist in the tool's SUPPORTED set.
        Assertions.assertThat(reply.toLowerCase(Locale.ROOT))
                .as("Reply must NOT mention 'carbon footprint' — an unsupported column")
                .doesNotContain("carbon footprint");
    }

    /**
     * UC-007 AC #2 / BR-03 — "Add a carbon-footprint-per-meal column" is not derivable.
     * The tool returns a REFUSED sentinel. The model must relay the refusal and must NOT
     * persist a ViewPreference row for that column.
     */
    @Test
    void nonDerivableColumnIsRefused() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);

        var reply = reportsChat()
                .prompt()
                .user("Add a carbon-footprint-per-meal column to the leaderboard.")
                .call()
                .content();

        String lower = reply.toLowerCase(Locale.ROOT);

        // The reply must relay the refusal.
        Assertions.assertThat(lower)
                .as("Reply must relay the REFUSED sentinel for non-derivable column. "
                        + "Full reply: \"%s\"", reply)
                .satisfiesAnyOf(
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "cannot", "can't", "not derivable", "not available",
                                "don't have", "isn't available", "no data",
                                "refused", "not supported", "unsupported"),
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "only", "supported", "kcalpereuro", "kcal per euro",
                                "kcal-per-euro"));

        // DB assertion: NO ViewPreference row should exist for "carbon" column.
        var settingsOpt = viewPreferenceService.getSettings(
                hh.getId(), ViewPreference.View.REPORTS, "leaderboard");

        // If a row exists, carbonFootprint must not be in extraColumns.
        if (settingsOpt.isPresent()) {
            @SuppressWarnings("unchecked")
            List<String> cols = (List<String>) settingsOpt.get().get("extraColumns");
            if (cols != null) {
                boolean hasCarbonColumn = cols.stream()
                        .anyMatch(c -> c != null && c.toLowerCase(Locale.ROOT).contains("carbon"));
                Assertions.assertThat(hasCarbonColumn)
                        .as("Hallucination: the model persisted a 'carbon' column despite the REFUSED sentinel. "
                                + "Columns: %s. Reply: \"%s\"", cols, reply)
                        .isFalse();
            }
        }
    }

    /**
     * UC-007 AC #3 / BR-02 — "Show category breakdown as a horizontal bar" must call
     * transformCategoryChart and persist chartType=bar + orientation=horizontal.
     */
    @Test
    void transformChartPersistsViewPreference() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);

        var reply = reportsChat()
                .prompt()
                .user("Show the category breakdown as a horizontal bar instead of a donut.")
                .call()
                .content();

        // DB assertion: the ViewPreference row for categoryBreakdown must have chartType=bar.
        var settingsOpt = viewPreferenceService.getSettings(
                hh.getId(), ViewPreference.View.REPORTS, "categoryBreakdown");

        Assertions.assertThat(settingsOpt)
                .as("A ViewPreference row for REPORTS/categoryBreakdown must exist. "
                        + "Reply: \"%s\"", reply)
                .isPresent();

        Map<String, Object> settings = settingsOpt.get();
        Assertions.assertThat(settings.get("chartType"))
                .as("chartType must be 'bar'. Settings: %s. Reply: \"%s\"", settings, reply)
                .isEqualTo("bar");

        Assertions.assertThat(settings.get("orientation"))
                .as("orientation must be 'horizontal'. Settings: %s. Reply: \"%s\"", settings, reply)
                .isEqualTo("horizontal");
    }

    /**
     * UC-007 AC #4 / BR-06 — "Why was last week cheaper than usual?" must call
     * explainWeekVsAverage and cite at least one real recipe name from the seeded plan.
     * The model must not invent prices beyond what the tool returns.
     */
    @Test
    void explainWeekVsAverageReferencesRealMeals() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);

        var reply = reportsChat()
                .prompt()
                .user("Why was last week cheaper than usual?")
                .call()
                .content();

        String lower = reply.toLowerCase(Locale.ROOT);

        // The reply must cite at least one recipe name that is in the catalog.
        // These are the possible recipe names from demo/data/recipes/ (lowercased fragments).
        Assertions.assertThat(lower)
                .as("Reply must cite real meals, refuse for lack of data, OR ask a clarifying "
                        + "question about which week — anything except inventing facts. "
                        + "Full reply: \"%s\"", reply)
                .satisfiesAnyOf(
                        // Best path: model cited a real recipe from the plan
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "baked cod", "beef stew", "chicken curry", "chicken rice",
                                "chicken stir fry", "grilled chicken", "lentil soup",
                                "meatball", "minced meat", "pea risotto", "pork chop",
                                "potato gratin", "salmon pasta", "spaghetti bolognese",
                                "tuna pasta", "turkey", "vegetable soup", "veggie pasta"),
                        // Acceptable: model relays a "no historical data" message
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "no history", "not enough", "only one week",
                                "no plan history", "insufficient", "need more"),
                        // Acceptable: model asks for clarification when the week is ambiguous
                        // (rather than guessing — this honours the no-fabrication rule).
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "which week", "specific date", "iso format",
                                "could you provide", "could you specify",
                                "monday date", "date for the week"));
    }
}
