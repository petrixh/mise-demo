package com.example.mise.ai.tools;

import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceService;
import com.example.mise.domain.reports.ReportService;
import com.example.mise.ui.reports.ReportsRefreshBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * Spring AI tools for the Reports view (UC-007).
 * Registered globally on the {@link com.example.mise.ai.HouseholdOrchestrator} alongside
 * PlanTools and ShoppingTools (UC-008 will introduce proper view-scoped registration).
 */
@Component
public class ReportsTools {

    private static final Logger log = LoggerFactory.getLogger(ReportsTools.class);

    /** Only these column keys are derivable from existing data (BR-03). */
    private static final Set<String> SUPPORTED_EXTRA_COLUMNS = Set.of("kcalPerEuro");

    private final HouseholdService householdService;
    private final ViewPreferenceService viewPreferenceService;
    private final ReportService reportService;
    private final ReportsRefreshBroadcaster refreshBroadcaster;

    public ReportsTools(HouseholdService householdService,
                        ViewPreferenceService viewPreferenceService,
                        ReportService reportService,
                        ReportsRefreshBroadcaster refreshBroadcaster) {
        this.householdService = householdService;
        this.viewPreferenceService = viewPreferenceService;
        this.reportService = reportService;
        this.refreshBroadcaster = refreshBroadcaster;
    }

    // ── Leaderboard column tools ───────────────────────────────────────────────

    /**
     * Adds a derived column to the leaderboard widget.
     * Only "kcalPerEuro" is currently supported (BR-03).
     */
    @Tool(description = "Add a derived column to the per-meal leaderboard in the Reports view. Supported column keys: kcalPerEuro (calories per euro spent). If the requested column is not derivable from current data (e.g. 'carbonFootprint'), returns a REFUSED sentinel — do NOT fabricate values.")
    public String addLeaderboardColumn(
            @ToolParam(description = "Column key to add. Currently supported: 'kcalPerEuro'. Any other value is refused.") String columnKey) {
        if (columnKey == null || columnKey.isBlank()) {
            return "REFUSED: column key must not be blank.";
        }
        if (!SUPPORTED_EXTRA_COLUMNS.contains(columnKey)) {
            return "REFUSED: column '" + columnKey + "' is not derivable from current data "
                    + "(supported: " + String.join(", ", SUPPORTED_EXTRA_COLUMNS) + "). "
                    + "Do NOT fabricate values. Tell the user this column cannot be added.";
        }
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            Map<String, Object> settings = viewPreferenceService
                    .getSettings(hh.getId(), ViewPreference.View.REPORTS, "leaderboard")
                    .map(s -> new LinkedHashMap<>(s))
                    .orElseGet(LinkedHashMap::new);

            @SuppressWarnings("unchecked")
            List<String> cols = (List<String>) settings.computeIfAbsent("extraColumns", k -> new ArrayList<>());
            if (!(cols instanceof ArrayList)) {
                cols = new ArrayList<>(cols);
                settings.put("extraColumns", cols);
            }
            if (!cols.contains(columnKey)) {
                cols.add(columnKey);
            }
            viewPreferenceService.saveSettings(hh.getId(), ViewPreference.View.REPORTS, "leaderboard", settings);
            // Issue #5: fire refresh on tool-call completion, not assistant-message stream end —
            // the @Transactional saveSettings has already committed at this point.
            refreshBroadcaster.fireRefresh();
            return "Added column " + columnKey + " to the leaderboard.";
        } catch (Exception e) {
            log.warn("addLeaderboardColumn error: {}", e.getMessage());
            return "Could not add column: " + e.getMessage();
        }
    }

    /**
     * Removes a derived column from the leaderboard widget.
     */
    @Tool(description = "Remove a derived column from the per-meal leaderboard in the Reports view. Use when the user asks to remove a column or reset a specific column.")
    public String removeLeaderboardColumn(
            @ToolParam(description = "Column key to remove (e.g. 'kcalPerEuro').") String columnKey) {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            Optional<Map<String, Object>> settingsOpt =
                    viewPreferenceService.getSettings(hh.getId(), ViewPreference.View.REPORTS, "leaderboard");

            if (settingsOpt.isEmpty()) return "Column '" + columnKey + "' not present (no preferences saved).";

            Map<String, Object> settings = new LinkedHashMap<>(settingsOpt.get());
            @SuppressWarnings("unchecked")
            List<String> cols = (List<String>) settings.get("extraColumns");
            if (cols == null || !cols.contains(columnKey)) {
                return "Column '" + columnKey + "' not present.";
            }
            List<String> updated = new ArrayList<>(cols);
            updated.remove(columnKey);
            settings.put("extraColumns", updated);
            viewPreferenceService.saveSettings(hh.getId(), ViewPreference.View.REPORTS, "leaderboard", settings);
            refreshBroadcaster.fireRefresh();
            return "Removed column " + columnKey + " from the leaderboard.";
        } catch (Exception e) {
            log.warn("removeLeaderboardColumn error: {}", e.getMessage());
            return "Could not remove column: " + e.getMessage();
        }
    }

    // ── Chart transform tool ───────────────────────────────────────────────────

    /**
     * Changes the shape of the category breakdown chart (BR-02).
     */
    @Tool(description = "Transform the cost-by-category chart in the Reports view. chartType: 'donut' (default pie/donut) or 'bar'. orientation: 'horizontal' or 'vertical' (only relevant for bar). The change is persisted so it survives page reloads.")
    public String transformCategoryChart(
            @ToolParam(description = "Chart type: 'donut' or 'bar'.") String chartType,
            @ToolParam(description = "Orientation: 'horizontal' or 'vertical'. Only used when chartType is 'bar'.") String orientation) {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            String resolvedType = (chartType == null || chartType.isBlank()) ? "donut" : chartType.trim().toLowerCase();
            String resolvedOrientation = (orientation == null || orientation.isBlank()) ? "vertical" : orientation.trim().toLowerCase();

            if (!Set.of("donut", "bar").contains(resolvedType)) {
                return "REFUSED: unknown chartType '" + resolvedType + "'. Supported: donut, bar.";
            }
            if (!Set.of("horizontal", "vertical").contains(resolvedOrientation)) {
                return "REFUSED: unknown orientation '" + resolvedOrientation + "'. Supported: horizontal, vertical.";
            }

            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("chartType", resolvedType);
            settings.put("orientation", resolvedOrientation);
            viewPreferenceService.saveSettings(hh.getId(), ViewPreference.View.REPORTS, "categoryBreakdown", settings);
            refreshBroadcaster.fireRefresh();

            String desc = "bar".equals(resolvedType)
                    ? ("horizontal".equals(resolvedOrientation) ? "horizontal bar" : "vertical bar")
                    : "donut";
            return "Category breakdown chart changed to " + desc + ".";
        } catch (Exception e) {
            log.warn("transformCategoryChart error: {}", e.getMessage());
            return "Could not transform chart: " + e.getMessage();
        }
    }

    // ── Reset tool ─────────────────────────────────────────────────────────────

    /**
     * Resets a widget's customizations to defaults by deleting its ViewPreference row (BR-05).
     */
    @Tool(description = "Reset a Reports widget to its default state by removing all saved customizations. widgetKey: 'leaderboard' (removes extra columns) or 'categoryBreakdown' (resets chart shape). Returns the prior settings or 'Already default'.")
    public String resetWidget(
            @ToolParam(description = "Widget key to reset: 'leaderboard' or 'categoryBreakdown'.") String widgetKey) {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            if (!Set.of("leaderboard", "categoryBreakdown").contains(widgetKey)) {
                return "Unknown widgetKey '" + widgetKey + "'. Supported: leaderboard, categoryBreakdown.";
            }

            Optional<Map<String, Object>> existing =
                    viewPreferenceService.getSettings(hh.getId(), ViewPreference.View.REPORTS, widgetKey);

            if (existing.isEmpty()) {
                return "Already default — no customizations saved for '" + widgetKey + "'.";
            }

            String priorDesc = existing.get().toString();
            viewPreferenceService.deleteSettings(hh.getId(), ViewPreference.View.REPORTS, widgetKey);
            refreshBroadcaster.fireRefresh();
            return "Reset '" + widgetKey + "' to defaults. Prior settings were: " + priorDesc;
        } catch (Exception e) {
            log.warn("resetWidget error: {}", e.getMessage());
            return "Could not reset widget: " + e.getMessage();
        }
    }

    // ── Explain tool ───────────────────────────────────────────────────────────

    /**
     * Returns a structured analysis for a week vs the overall average cost.
     * BR-06: uses current catalog only; never invents historical prices.
     */
    @Tool(description = "Return a structured cost analysis for a given week vs the average across all weeks. Use this to answer 'why was last week cheaper/more expensive than usual?'. Cite concrete meals and categories from the result. Never invent prices — this uses the current price catalog only.")
    public String explainWeekVsAverage(
            @ToolParam(description = "The Monday (week-start date) of the week to analyse, in ISO format (2026-05-11). Leave blank or null to analyse the most recent completed week.") String weekStartDate) {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            LocalDate date = null;
            if (weekStartDate != null && !weekStartDate.isBlank()) {
                try {
                    date = LocalDate.parse(weekStartDate.trim());
                } catch (Exception e) {
                    return "Could not parse weekStartDate '" + weekStartDate + "'. Use ISO format (2026-05-11).";
                }
            }

            return reportService.buildWeekVsAverageAnalysis(hh.getId(), date);
        } catch (Exception e) {
            log.warn("explainWeekVsAverage error: {}", e.getMessage());
            return "Could not build analysis: " + e.getMessage();
        }
    }
}
