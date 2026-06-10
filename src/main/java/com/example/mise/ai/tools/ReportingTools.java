package com.example.mise.ai.tools;

import com.example.mise.ai.MiseDatabaseProvider;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceService;
import com.example.mise.ui.shared.ViewRefreshBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UC-007/UC-012 reporting tools. Widget <em>reshaping</em> is handled entirely
 * by the Vaadin chart/grid controller tools (no fixed transforms here); this
 * class covers the two things the controllers don't:
 *
 * <ul>
 *   <li>{@link #queryReportingData} — free-form, read-only SQL for answering
 *       data questions in chat ("why was last week more expensive?") grounded
 *       in the same reporting schema the widgets use.</li>
 *   <li>{@link #resetReportsWidget} — drops a widget's persisted state so it
 *       falls back to its built-in default query.</li>
 * </ul>
 */
@Component
public class ReportingTools {

    private static final Logger log = LoggerFactory.getLogger(ReportingTools.class);

    /** Keeps tool results bounded; the model can always narrow its query. */
    private static final int MAX_ROWS = 40;

    public static final Set<String> WIDGET_KEYS = Set.of("trendChart", "categoryChart", "leaderboard");

    private final MiseDatabaseProvider databaseProvider;
    private final HouseholdService householdService;
    private final ViewPreferenceService viewPreferenceService;
    private final ViewRefreshBroadcaster refreshBroadcaster;

    public ReportingTools(MiseDatabaseProvider databaseProvider,
                          HouseholdService householdService,
                          ViewPreferenceService viewPreferenceService,
                          ViewRefreshBroadcaster refreshBroadcaster) {
        this.databaseProvider = databaseProvider;
        this.householdService = householdService;
        this.viewPreferenceService = viewPreferenceService;
        this.refreshBroadcaster = refreshBroadcaster;
    }

    @Tool(description = """
            Run a read-only SQL SELECT against the meal-history reporting schema and return the rows.
            Use this to answer data questions in chat — weekly costs, comparisons, frequencies,
            'why was week X more expensive than usual'. Call get_database_schema first if you don't
            know the tables. SELECT only; never fabricate values not present in the result.""")
    public String queryReportingData(
            @ToolParam(description = "A single SQL SELECT statement against the reporting schema.") String sql) {
        try {
            List<Map<String, Object>> rows = databaseProvider.executeQuery(sql);
            if (rows.isEmpty()) return "Query returned no rows.";
            var sb = new StringBuilder(String.join(" | ", rows.get(0).keySet())).append('\n');
            rows.stream().limit(MAX_ROWS).forEach(row -> sb.append(
                    String.join(" | ", row.values().stream()
                            .map(v -> v == null ? "" : v.toString()).toList())).append('\n'));
            if (rows.size() > MAX_ROWS) {
                sb.append("… ").append(rows.size() - MAX_ROWS)
                  .append(" more rows omitted — narrow the query if you need them.");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("queryReportingData failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = """
            Reset a Reports widget to its built-in default query, discarding the user's
            customizations. widgetKey is one of: trendChart, categoryChart, leaderboard.""")
    public String resetReportsWidget(
            @ToolParam(description = "Widget to reset: 'trendChart', 'categoryChart' or 'leaderboard'.") String widgetKey) {
        if (!WIDGET_KEYS.contains(widgetKey)) {
            return "Refused: unknown widgetKey '" + widgetKey + "'. Supported: " + WIDGET_KEYS;
        }
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";
            viewPreferenceService.deleteSettings(hh.getId(), ViewPreference.View.REPORTS, widgetKey);
            refreshBroadcaster.fireRefresh();
            return "Reset '" + widgetKey + "' to its default.";
        } catch (Exception e) {
            log.warn("resetReportsWidget failed: {}", e.getMessage());
            return "Error: could not reset widget: " + e.getMessage();
        }
    }
}
