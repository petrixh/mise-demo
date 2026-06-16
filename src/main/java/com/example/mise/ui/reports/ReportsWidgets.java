package com.example.mise.ui.reports;

import com.example.mise.ai.MiseDatabaseProvider;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceService;
import com.example.mise.ui.shared.CategoryColors;
import com.example.mise.ui.shared.MiseChart;
import com.vaadin.flow.component.ai.chart.ChartAIController;
import com.vaadin.flow.component.ai.chart.ChartState;
import com.vaadin.flow.component.ai.grid.AIDataRow;
import com.vaadin.flow.component.ai.grid.GridAIController;
import com.vaadin.flow.component.ai.grid.GridState;
import com.vaadin.flow.component.ai.orchestrator.AIController;
import com.vaadin.flow.component.charts.model.AxisTitle;
import com.vaadin.flow.component.charts.model.ChartType;
import com.vaadin.flow.component.charts.model.Configuration;
import com.vaadin.flow.component.charts.model.DataLabels;
import com.vaadin.flow.component.charts.model.HorizontalAlign;
import com.vaadin.flow.component.charts.model.LayoutDirection;
import com.vaadin.flow.component.charts.model.Legend;
import com.vaadin.flow.component.charts.model.PlotOptionsLine;
import com.vaadin.flow.component.charts.model.PlotOptionsPie;
import com.vaadin.flow.component.charts.model.VerticalAlign;
import com.vaadin.flow.component.charts.model.YAxis;
import com.vaadin.flow.component.charts.model.style.SolidColor;
import com.vaadin.flow.component.grid.Grid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * UC-012: the three AI-drivable Reports widgets and their Vaadin AI
 * controllers, owned per UI by {@link com.example.mise.ui.MainLayout} so the
 * controllers can be registered on the shared orchestrator at build time
 * ({@code AIOrchestrator.reconnect} is deserialization-only — controllers
 * cannot be attached or detached at runtime). {@link ReportsView} adopts the
 * components into its layout on attach; component state is server-side, so it
 * survives the view being detached and recreated.
 *
 * <p>Per widget: a meaningful <b>default query</b> (the UC-007 defaults), state
 * persistence to {@link ViewPreference} ({@code query} +
 * {@code controllerStateB64}, BR-05/BR-10), and an "edited" notification for
 * the view's highlight (BR-09).
 */
public class ReportsWidgets {

    private static final Logger log = LoggerFactory.getLogger(ReportsWidgets.class);

    public static final String KEY_TREND = "trendChart";
    public static final String KEY_CATEGORY = "categoryChart";
    public static final String KEY_LEADERBOARD = "leaderboard";

    private final MiseChart trendChart = new MiseChart(ChartType.LINE);
    private final MiseChart categoryChart = new MiseChart(ChartType.PIE);
    private final Grid<AIDataRow> leaderboardGrid = new Grid<>();

    private final ChartAIController trendController;
    private final ChartAIController categoryController;
    private final GridAIController leaderboardController;

    private final ViewPreferenceService viewPreferenceService;
    private final HouseholdService householdService;

    /** Set by ReportsView while attached; notified with the widgetKey after an AI reshape. */
    private Consumer<String> editedListener;

    /** The viewed Monday from the last applyStates call — reused by per-turn refreshes. */
    private LocalDate lastViewedMonday;

    public ReportsWidgets(MiseDatabaseProvider databaseProvider,
                          ViewPreferenceService viewPreferenceService,
                          HouseholdService householdService) {
        this.viewPreferenceService = viewPreferenceService;
        this.householdService = householdService;

        trendChart.setWidthFull();
        trendChart.setHeight("220px");
        trendChart.getElement().setAttribute("data-testid", "report-cost-trend");

        categoryChart.setWidthFull();
        categoryChart.setHeight("220px");
        categoryChart.getElement().setAttribute("data-testid", "report-category-breakdown");

        leaderboardGrid.setWidthFull();
        leaderboardGrid.setAllRowsVisible(true);
        leaderboardGrid.addClassName("mise-reports-leaderboard");
        leaderboardGrid.getElement().setAttribute("data-testid", "leaderboard-grid");

        trendController = new ChartAIController(trendChart, databaseProvider);
        categoryController = new ChartAIController(categoryChart, databaseProvider);
        leaderboardController = new GridAIController(leaderboardGrid, databaseProvider);

        // Persist on every successful AI reshape (not fired by restoreState).
        trendController.addStateChangeListener(state -> onChartEdited(KEY_TREND, trendChart, state));
        categoryController.addStateChangeListener(state -> onChartEdited(KEY_CATEGORY, categoryChart, state));
        leaderboardController.addStateChangeListener(state -> {
            saveSettings(KEY_LEADERBOARD, Map.of("query", state.query()));
            notifyEdited(KEY_LEADERBOARD);
        });
    }

    public MiseChart trendChart() { return trendChart; }
    public MiseChart categoryChart() { return categoryChart; }
    public Grid<AIDataRow> leaderboardGrid() { return leaderboardGrid; }

    /**
     * The single composite controller to register on the orchestrator at build
     * time. {@code AIOrchestrator} accepts exactly one controller, so the three
     * widget controllers are wrapped by {@link ReportsAIController}. The
     * after-response hook re-runs the widget queries once the deferred renders
     * have been applied — that is the per-turn data refresh (a meal swap in the
     * same turn becomes visible) and it must NOT happen any earlier, or it
     * would clear the controllers' pending state.
     */
    public AIController controller() {
        return new ReportsAIController(trendController, categoryController, leaderboardController,
                this::refreshAfterTurn);
    }

    /** Re-applies persisted-or-default states when the widgets are on screen. */
    private void refreshAfterTurn() {
        if (leaderboardGrid.isAttached()) {
            applyStates(lastViewedMonday);
        }
    }

    public void setEditedListener(Consumer<String> listener) {
        this.editedListener = listener;
    }

    /**
     * Restores every widget: persisted state when present (BR-10), otherwise
     * the default query. Re-executes the queries, so calling this on each
     * Reports attach doubles as a data refresh.
     *
     * @param viewedMonday UC-010: the viewed week for the category default; null = latest week
     */
    public void applyStates(LocalDate viewedMonday) {
        lastViewedMonday = viewedMonday;
        restoreChart(trendController, trendChart, KEY_TREND, defaultTrendState());
        restoreChart(categoryController, categoryChart, KEY_CATEGORY, defaultCategoryState(viewedMonday));

        GridState savedGrid = settings(KEY_LEADERBOARD)
                .map(s -> s.get("query"))
                .map(q -> new GridState(q.toString()))
                .orElseGet(this::defaultLeaderboardState);
        try {
            leaderboardController.restoreState(savedGrid);
        } catch (Exception e) {
            log.warn("Leaderboard restore failed, falling back to default: {}", e.getMessage());
            leaderboardController.restoreState(defaultLeaderboardState());
        }
    }

    /** Reset one widget: drop the persisted state, re-apply its default (BR-10). */
    public void resetWidget(String widgetKey, LocalDate viewedMonday) {
        householdService.findHousehold().ifPresent(hh ->
                viewPreferenceService.deleteSettings(hh.getId(), ViewPreference.View.REPORTS, widgetKey));
        switch (widgetKey) {
            case KEY_TREND -> { trendController.restoreState(defaultTrendState()); trendChart.applyTheme(); }
            case KEY_CATEGORY -> { categoryController.restoreState(defaultCategoryState(viewedMonday)); categoryChart.applyTheme(); }
            case KEY_LEADERBOARD -> leaderboardController.restoreState(defaultLeaderboardState());
            default -> log.warn("Unknown widgetKey '{}'", widgetKey);
        }
    }

    // ── default states (the UC-007 defaults, expressed as queries) ────────────

    /** Weekly total cost over time — datetime line via the _x/_y alias convention. */
    private ChartState defaultTrendState() {
        String query = """
                SELECT week_start_date AS "_x", total_cost_eur AS "_y" \
                FROM weekly_kpi ORDER BY week_start_date""";
        Configuration conf = new Configuration();
        conf.getChart().setType(ChartType.LINE);
        conf.setTitle("");
        conf.setLegend(new Legend(false));
        YAxis y = new YAxis();
        y.setTitle(new AxisTitle("Cost (€)"));
        conf.addyAxis(y);
        var line = new PlotOptionsLine();
        line.setColor(new SolidColor(CategoryColors.HEX.get("Protein")));
        conf.setPlotOptions(line);
        return new ChartState(List.of(query), conf);
    }

    /**
     * Cost by category for the viewed week — donut with design-system slice
     * colors supplied through the converter's {@code _color} column.
     */
    private ChartState defaultCategoryState(LocalDate viewedMonday) {
        String weekFilter = viewedMonday != null
                ? "DATE '" + viewedMonday + "'"
                : "(SELECT MAX(week_start_date) FROM weekly_kpi)";
        StringBuilder colorCase = new StringBuilder("CASE category");
        CategoryColors.HEX.forEach((cat, hex) ->
                colorCase.append(" WHEN '").append(cat).append("' THEN '").append(hex).append('\''));
        colorCase.append(" END");
        String query = "SELECT category AS \"Category\", SUM(cost_eur) AS \"Cost\", "
                + colorCase + " AS \"_color\" "
                + "FROM meal_category_cost WHERE week_start_date = " + weekFilter + " "
                + "GROUP BY category ORDER BY SUM(cost_eur) DESC";

        Configuration conf = new Configuration();
        conf.getChart().setType(ChartType.PIE);
        conf.setTitle("");
        var pie = new PlotOptionsPie();
        pie.setInnerSize("50%");
        var labels = new DataLabels();
        labels.setEnabled(false);
        pie.setDataLabels(labels);
        pie.setShowInLegend(true);
        conf.setPlotOptions(pie);
        Legend legend = new Legend(true);
        legend.setLayout(LayoutDirection.VERTICAL);
        legend.setAlign(HorizontalAlign.RIGHT);
        legend.setVerticalAlign(VerticalAlign.MIDDLE);
        legend.setLabelFormat("{name}  {percentage:.0f}%");
        conf.setLegend(legend);
        return new ChartState(List.of(query), conf);
    }

    /** Recipes by appearance count across all weeks. */
    private GridState defaultLeaderboardState() {
        return new GridState("""
                SELECT recipe_name AS "Meal", COUNT(*) AS "Times", \
                CAST(AVG(est_cost_eur) AS DECIMAL(8,2)) AS "Avg cost (EUR)" \
                FROM meal_history GROUP BY recipe_name \
                ORDER BY COUNT(*) DESC, recipe_name""");
    }

    // ── persistence plumbing ───────────────────────────────────────────────────

    private void restoreChart(ChartAIController controller, MiseChart chart,
                              String widgetKey, ChartState defaultState) {
        ChartState state = settings(widgetKey)
                .map(s -> s.get("controllerStateB64"))
                .map(b64 -> decodeChartState(b64.toString()))
                .orElse(null);
        try {
            controller.restoreState(state != null ? state : defaultState);
        } catch (Exception e) {
            log.warn("{} restore failed, falling back to default: {}", widgetKey, e.getMessage());
            controller.restoreState(defaultState);
        }
        chart.applyTheme();
        chart.drawChart();
    }

    private void onChartEdited(String widgetKey, MiseChart chart, ChartState state) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("query", state.queries().isEmpty() ? "" : state.queries().get(0));
        String b64 = encode(state);
        if (b64 != null) {
            settings.put("controllerStateB64", b64);
        }
        saveSettings(widgetKey, settings);
        chart.applyTheme();
        chart.drawChart();
        notifyEdited(widgetKey);
    }

    private java.util.Optional<Map<String, Object>> settings(String widgetKey) {
        return householdService.findHousehold().flatMap(hh ->
                viewPreferenceService.getSettings(hh.getId(), ViewPreference.View.REPORTS, widgetKey));
    }

    private void saveSettings(String widgetKey, Map<String, Object> settings) {
        try {
            householdService.findHousehold().ifPresent(hh -> viewPreferenceService
                    .saveSettings(hh.getId(), ViewPreference.View.REPORTS, widgetKey, settings));
        } catch (Exception e) {
            log.warn("Could not persist {} state: {}", widgetKey, e.getMessage());
        }
    }

    private void notifyEdited(String widgetKey) {
        if (editedListener != null) {
            editedListener.accept(widgetKey);
        }
    }

    /**
     * BR-05: ChartState is round-tripped as base64 Java-serialized bytes — its
     * internals (a Charts {@link Configuration}) are not a stable public JSON
     * shape, so we treat it as an opaque Serializable blob.
     */
    private static String encode(Serializable state) {
        try (var bytes = new ByteArrayOutputStream(); var out = new ObjectOutputStream(bytes)) {
            out.writeObject(state);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            log.warn("Could not serialize controller state: {}", e.getMessage());
            return null;
        }
    }

    private static ChartState decodeChartState(String b64) {
        try (var in = new ObjectInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(b64)))) {
            return (ChartState) in.readObject();
        } catch (Exception e) {
            log.warn("Could not deserialize saved chart state (falling back to default): {}",
                    e.getMessage());
            return null;
        }
    }
}
