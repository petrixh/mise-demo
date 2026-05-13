package com.example.mise.ai.tools;

import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.insights.Insight;
import com.example.mise.domain.insights.InsightService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * UC-009: AI tools for surfacing, muting, and managing insights.
 *
 * <p>All tools are registered globally on the {@link com.example.mise.ai.HouseholdOrchestrator}
 * from {@link com.example.mise.ui.MainLayout}.
 */
@Component
public class InsightTools {

    private static final Logger log = LoggerFactory.getLogger(InsightTools.class);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int MAX_LIST_SIZE = 10;

    private final InsightService insightService;
    private final HouseholdService householdService;

    public InsightTools(InsightService insightService, HouseholdService householdService) {
        this.insightService = insightService;
        this.householdService = householdService;
    }

    // ── tool methods ───────────────────────────────────────────────────────────

    /**
     * Dismisses the most-recent undismissed insight for the household.
     */
    @Tool(description = "Dismiss the current undismissed insight banner for this household. Use when the user says 'dismiss this', 'ignore that', or similar about the current insight.")
    public String dismissCurrentInsight() {
        Long householdId = getHouseholdId();
        if (householdId == null) return "No household found.";

        Optional<Insight> insightOpt = insightService.currentInsight(householdId);
        if (insightOpt.isEmpty()) {
            // Also check undismissed when muted
            insightOpt = findFirstUndismissed(householdId);
        }
        if (insightOpt.isEmpty()) {
            return "No insights to dismiss.";
        }

        Insight dismissed = insightService.dismiss(insightOpt.get().getId());
        return "Dismissed: " + dismissed.getBody();
    }

    /**
     * Mutes all future insight banners. Existing insights are still generated
     * and stored so they can be browsed via {@link #listInsightsIMissed()}.
     */
    @Tool(description = "Mute insight banners. The household will no longer see banners, but insights continue to be generated and can be listed on request. Use when the user says 'mute insights', 'stop insights', 'no more insights', etc.")
    public String muteInsights() {
        Long householdId = getHouseholdId();
        if (householdId == null) return "No household found.";
        insightService.mute(householdId, true);
        return "Insights muted. Banners will no longer appear, but you can still ask me for insights.";
    }

    /**
     * Re-enables insight banners.
     */
    @Tool(description = "Unmute (resume) insight banners. Use when the user says 'unmute insights', 'resume insights', etc.")
    public String unmuteInsights() {
        Long householdId = getHouseholdId();
        if (householdId == null) return "No household found.";
        insightService.mute(householdId, false);
        return "Insights resumed. Banners will appear again when there is a new insight.";
    }

    /**
     * Sets the insight frequency preference.
     */
    @Tool(description = "Set how often insight banners are generated. Accepted values: DAILY, WEEKLY, NEVER. Use when the user says 'insights only weekly', 'show me an insight every day', 'never show insights', etc.")
    public String setInsightFrequency(
            @ToolParam(description = "Frequency: one of DAILY, WEEKLY, NEVER") String frequency) {
        if (frequency == null || frequency.isBlank()) {
            return "REFUSED: frequency must be one of DAILY, WEEKLY, NEVER.";
        }
        Household.InsightFrequency freq;
        try {
            freq = Household.InsightFrequency.valueOf(frequency.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return "REFUSED: '" + frequency + "' is not a valid frequency. Use one of: DAILY, WEEKLY, NEVER.";
        }

        Long householdId = getHouseholdId();
        if (householdId == null) return "No household found.";
        insightService.setFrequency(householdId, freq);
        return "Insight frequency set to " + freq.name() + ".";
    }

    /**
     * Lists up to 10 of the most recent insights (newest first), regardless of muted state.
     */
    @Tool(description = "List insights (up to 10 most recent) including dismissed ones. Used when the user asks 'show me insights I missed', 'what insights did I miss?', etc. Returns the list regardless of whether insights are muted.")
    public String listInsightsIMissed() {
        Long householdId = getHouseholdId();
        if (householdId == null) return "No household found.";

        List<Insight> all = insightService.allInsights(householdId);
        if (all.isEmpty()) {
            return "No insights have been generated yet. Ask me for one or wait for the next scheduled insight.";
        }

        var sb = new StringBuilder("Recent insights (newest first):\n");
        all.stream().limit(MAX_LIST_SIZE).forEach(i -> {
            sb.append("- [")
              .append(DATE_FMT.format(i.getCreatedAt()))
              .append(i.isDismissed() ? ", dismissed" : ", undismissed")
              .append("] ")
              .append(i.getBody())
              .append("\n");
        });
        if (all.size() > MAX_LIST_SIZE) {
            sb.append("(").append(all.size() - MAX_LIST_SIZE).append(" older insights not shown)");
        }
        return sb.toString();
    }

    /**
     * Generates a new insight on user request (BR-04 c).
     * Returns the insight body or an "insufficient history" message.
     */
    @Tool(description = "Generate a new insight now, grounded in the household's plan history. Use when the user says 'give me an insight', 'any patterns?', etc. If the tool returns 'No insight available', relay that — never fabricate one.")
    public String requestInsight() {
        Long householdId = getHouseholdId();
        if (householdId == null) return "No household found.";

        try {
            Optional<Insight> insight = insightService.generate(householdId);
            return insight.map(Insight::getBody)
                    .orElse("No insight available — need more meal history.");
        } catch (Exception e) {
            log.warn("requestInsight error: {}", e.getMessage());
            return "Could not generate an insight: " + e.getMessage();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Long getHouseholdId() {
        return householdService.findHousehold()
                .map(h -> h.getId())
                .orElse(null);
    }

    /**
     * Finds the first undismissed insight regardless of muted state.
     * Used by dismissCurrentInsight when muted (you can still dismiss).
     */
    private Optional<Insight> findFirstUndismissed(Long householdId) {
        return insightService.allInsights(householdId).stream()
                .filter(i -> !i.isDismissed())
                .findFirst();
    }
}
