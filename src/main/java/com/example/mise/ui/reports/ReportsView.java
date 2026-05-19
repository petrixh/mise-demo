package com.example.mise.ui.reports;

import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.insights.Insight;
import com.example.mise.domain.insights.InsightService;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceService;
import com.example.mise.domain.reports.*;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.charts.Chart;
import com.vaadin.flow.component.charts.model.*;
import com.vaadin.flow.component.charts.model.style.SolidColor;
import com.vaadin.flow.component.charts.model.style.Style;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.math.BigDecimal;
import java.util.*;

/**
 * UC-007 Reports view at /reports.
 *
 * <p>Three widgets:
 * <ol>
 *   <li>Weekly cost trend — line chart spanning plan history</li>
 *   <li>Cost by category — donut (default) or bar, persisted as ViewPreference</li>
 *   <li>Per-meal leaderboard — Grid with optional derived columns (e.g. kcalPerEuro)</li>
 * </ol>
 *
 * <p>AI transforms (chart shape, extra columns) are applied by {@link com.example.mise.ai.tools.ReportsTools}
 * which mutates {@link ViewPreference} rows. The view re-reads those preferences on every refresh
 * triggered by the {@link ReportsRefreshBroadcaster}.
 *
 * <p>BR-07: View registers the broadcaster hook on attach and deregisters on leave.
 */
@Route(value = "reports", layout = com.example.mise.ui.MainLayout.class)
@PageTitle("Mise — Reports")
public class ReportsView extends VerticalLayout implements BeforeEnterObserver, BeforeLeaveObserver {

    /**
     * Design-system category colors keyed by canonical aisle label.
     * Hex values match --mise-category-* CSS custom properties in styles.css.
     */
    private static final Map<String, String> CATEGORY_COLORS = Map.of(
            "Protein", "#7F77DD",
            "Produce", "#1D9E75",
            "Pantry",  "#D85A30",
            "Dairy",   "#D4537E",
            "Other",   "#B4B2A9"
    );

    /**
     * Theme tokens applied to every chart so the chart canvas merges with the
     * surrounding Reports panel and uses the Mise palette. The chart contents
     * (the data) drive the series colors via per-item color overrides above;
     * these tokens cover the chrome: background, axis lines, grid, labels.
     *
     * Important: SolidColor's String constructor stores the string verbatim,
     * and the JSON serializer strips parentheses + commas — so passing
     * "rgba(0,0,0,0)" ends up as "rgba0,0,0,0" in the SVG fill, which is
     * invalid and Highcharts paints the canvas black. Always build rgba
     * colors via SolidColor(int, int, int, double) instead.
     */
    private static SolidColor chartColor(int r, int g, int b, double a) {
        return new SolidColor(r, g, b, a);
    }
    private static final SolidColor CHART_BG       = chartColor(0, 0, 0, 0.0);
    private static final SolidColor CHART_HAIRLINE = chartColor(255, 255, 255, 0.08);
    private static final SolidColor CHART_TEXT     = chartColor(228, 228, 231, 0.78);
    private static final SolidColor CHART_LABEL    = chartColor(228, 228, 231, 0.62);

    private final HouseholdService householdService;
    private final ReportService reportService;
    private final ViewPreferenceService viewPreferenceService;
    private final ReportsRefreshBroadcaster refreshBroadcaster;
    private final InsightService insightService;

    /** Main content area — cleared and rebuilt on each refresh. */
    private final Div contentArea = new Div();

    /** Hook registered with the broadcaster so we can deregister the exact lambda on leave. */
    private Runnable refreshHook;

    public ReportsView(HouseholdService householdService,
                       ReportService reportService,
                       ViewPreferenceService viewPreferenceService,
                       ReportsRefreshBroadcaster refreshBroadcaster,
                       InsightService insightService) {
        this.householdService = householdService;
        this.reportService = reportService;
        this.viewPreferenceService = viewPreferenceService;
        this.refreshBroadcaster = refreshBroadcaster;
        this.insightService = insightService;

        setId("mise-reports-view");
        getElement().setAttribute("data-testid", "reports-view");
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        addClassName("mise-reports-view");

        contentArea.addClassName("mise-reports-content");
        contentArea.setWidthFull();
        add(contentArea);

        // BR-07: register broadcaster hook on attach; deregister on detach
        addAttachListener(e -> {
            UI ui = e.getUI();
            refreshHook = () -> ui.access(this::aiRefresh);
            refreshBroadcaster.register(refreshHook);
        });
        addDetachListener(e -> {
            if (refreshHook != null) {
                refreshBroadcaster.deregister(refreshHook);
                refreshHook = null;
            }
        });
    }

    // ── RouterLayout lifecycle ─────────────────────────────────────────────────

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!householdService.exists()) {
            event.forwardTo("welcome");
            return;
        }
        loadAndRender(false);
    }

    /** BR-07: broadcaster hook is deregistered here, not on detach, to cover navigation away. */
    @Override
    public void beforeLeave(BeforeLeaveEvent event) {
        if (refreshHook != null) {
            refreshBroadcaster.deregister(refreshHook);
            refreshHook = null;
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void aiRefresh() {
        loadAndRender(true);
    }

    private void loadAndRender(boolean highlight) {
        var hhOpt = householdService.findHousehold();
        if (hhOpt.isEmpty()) return;
        var hh = hhOpt.get();

        contentArea.removeAll();

        // Single-panel container matching the mockup at
        //   ai-meal-planner/mise/html-mockups-initial/mise_meal_planner_reports_view.html
        // KPI strip → chart row (cost trend + category breakdown side by side) →
        // leaderboard → AI insights block, all inside one filled panel with
        // 0.5px hairline separators.
        var panel = new Div();
        panel.addClassName("mise-reports-panel");
        panel.getElement().setAttribute("data-testid", "reports-panel");

        // ── 0. KPI strip (M-5) ────────────────────────────────────────────
        WeeklyCostTrend trend = reportService.computeCostTrend(hh.getId());
        panel.add(buildKpiStrip(hh, trend));

        // ── 1+2. Chart row: cost trend (left) + category breakdown (right) ──
        var chartRow = new Div();
        chartRow.addClassName("mise-reports-chart-row");
        chartRow.add(buildCostTrendWidget(trend, hh.getId(), highlight));

        CategoryBreakdown breakdown = reportService.computeCategoryBreakdown(hh.getId(), null);
        Map<String, Object> chartPrefs = viewPreferenceService
                .getSettings(hh.getId(), ViewPreference.View.REPORTS, "categoryBreakdown")
                .orElse(Map.of());
        chartRow.add(buildCategoryWidget(breakdown, chartPrefs, hh.getId(), highlight));
        panel.add(chartRow);

        // ── 3. Leaderboard ────────────────────────────────────────────────
        Map<String, Object> leaderboardPrefs = viewPreferenceService
                .getSettings(hh.getId(), ViewPreference.View.REPORTS, "leaderboard")
                .orElse(Map.of());
        List<String> extraColumns = extractExtraColumns(leaderboardPrefs);
        boolean includeKcalPerEuro = extraColumns.contains("kcalPerEuro");
        List<LeaderboardEntry> leaderboard = reportService.computeLeaderboard(hh.getId(), includeKcalPerEuro);
        panel.add(buildLeaderboardWidget(leaderboard, extraColumns, hh.getId(), highlight));

        // ── R-F-01: AI insights block (non-dismissable), at the bottom of the
        // panel so it sits beneath the leaderboard like the mockup. Above the
        // chat dock conceptually; the chat dock itself is in MainLayout.
        panel.add(buildInsightsBlock(hh.getId()));

        contentArea.add(panel);
    }

    // ── R-F-01: AI insights block ──────────────────────────────────────────────

    /**
     * R-F-01: Builds a non-dismissable AI insights paragraph at the top of Reports.
     * Shows the most recent undismissed insight; if none, shows the most recent
     * insight regardless of dismissed state (for historical context). Falls back to
     * a quiet placeholder when no insights have been generated yet.
     *
     * <p>Unlike the Plan insight banner (which is dismissable per P-F-01), this block
     * has no dismiss button. Reports is the natural surface for summaries.
     */
    private Div buildInsightsBlock(Long householdId) {
        var block = new Div();
        block.setId("mise-reports-insights-block");
        block.addClassName("mise-reports-insights-block");
        block.getElement().setAttribute("data-testid", "reports-insights-block");

        // Prefer the current undismissed insight; fall back to most recent overall
        Insight insight = insightService.currentInsight(householdId)
                .orElseGet(() -> {
                    var all = insightService.allInsights(householdId);
                    return all.isEmpty() ? null : all.get(0);
                });

        var bulbIcon = VaadinIcon.LIGHTBULB.create();
        bulbIcon.addClassName("mise-reports-insights-icon");

        var labelSpan = new Span("AI insight");
        labelSpan.addClassName("mise-reports-insights-label");

        var headerRow = new Div(bulbIcon, labelSpan);
        headerRow.addClassName("mise-reports-insights-header");

        var bodySpan = new Span();
        bodySpan.addClassName("mise-reports-insights-body");
        bodySpan.getElement().setAttribute("data-testid", "reports-insights-body");

        if (insight != null) {
            bodySpan.setText(insight.getBody());
        } else {
            bodySpan.setText("No insights yet — complete a few weeks of planning to see AI-generated summaries here.");
            block.addClassName("mise-reports-insights-empty");
        }

        block.add(headerRow, bodySpan);
        return block;
    }

    // ── KPI strip (M-5) ───────────────────────────────────────────────────────

    /**
     * Builds the 4-card KPI strip for the Reports view top:
     * "4-week avg", "this week", "avg/meal", "vs target".
     */
    private Div buildKpiStrip(Household hh, WeeklyCostTrend trend) {
        var strip = new Div();
        strip.setId("mise-reports-kpi-strip");
        strip.addClassName("mise-kpi-strip");
        strip.getElement().setAttribute("data-testid", "reports-kpi-strip");

        // 4-week avg — average of the past 4 weeks (excludes current week)
        BigDecimal fourWeekAvg = reportService.weeklyAverage(hh.getId(), 4);

        // This week — most recent point in the trend
        BigDecimal thisWeek = trend.points().isEmpty() ? BigDecimal.ZERO
                : trend.points().get(trend.points().size() - 1).totalCost();

        // Avg/meal — this week total / 7 (7 meals in a plan week)
        BigDecimal avgMeal = thisWeek.divide(BigDecimal.valueOf(7), 2, java.math.RoundingMode.HALF_UP);

        // vs target — this week vs household budget (positive = over, negative = under)
        BigDecimal vsTarget = BigDecimal.ZERO;
        boolean budgetSet = hh.getWeeklyBudget() != null
                && hh.getWeeklyBudget().compareTo(BigDecimal.ZERO) > 0;
        if (budgetSet) {
            vsTarget = thisWeek.subtract(hh.getWeeklyBudget());
        }

        strip.add(buildKpiCard("4-week avg", "€" + String.format("%.2f", fourWeekAvg), false));
        strip.add(buildKpiCard("this week", "€" + String.format("%.2f", thisWeek), false));
        strip.add(buildKpiCard("avg/meal", "€" + String.format("%.2f", avgMeal), false));

        if (budgetSet) {
            String vsLabel = (vsTarget.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") +
                    String.format("%.2f", vsTarget);
            boolean overBudget = vsTarget.compareTo(BigDecimal.ZERO) > 0;
            var vsCard = buildKpiCard("vs target", "€" + vsLabel, overBudget);
            strip.add(vsCard);
        } else {
            strip.add(buildKpiCard("vs target", "—", false));
        }

        return strip;
    }

    private Div buildKpiCard(String label, String value, boolean warn) {
        var card = new Div();
        card.addClassName("mise-kpi-card");

        var labelP = new Paragraph(label);
        labelP.addClassName("mise-kpi-label");

        var valueP = new Paragraph(value);
        valueP.addClassName("mise-kpi-value");
        if (warn) valueP.addClassName("mise-kpi-value-warn");

        card.add(labelP, valueP);
        return card;
    }

    // ── Chart theme helper ────────────────────────────────────────────────────

    /**
     * Applies the Mise chart theme to a chart so it merges with the Reports
     * panel: transparent canvas + plot area, hairline axis/grid lines, and
     * label text in the panel's secondary/body text shades. Per-series colors
     * are still set explicitly by callers from CATEGORY_COLORS — those win
     * because they're per-item rather than the chart-level palette.
     *
     * Call once per chart, after the Configuration's axes have been added.
     * It's safe to call when an axis hasn't been registered yet (the helper
     * defensively skips missing ones).
     */
    private void applyMiseChartTheme(Chart chart) {
        Configuration conf = chart.getConfiguration();

        // Transparent canvas + plot area so the panel background shows through.
        conf.getChart().setBackgroundColor(CHART_BG);
        conf.getChart().setPlotBackgroundColor(CHART_BG);

        // Per-series colors are already set explicitly by callers via
        // CATEGORY_COLORS — chart-level palette isn't needed here. Vaadin
        // Charts 25 has no Configuration.setColors; the per-item Color wins.

        // Axis chrome — hairline lines + low-contrast labels.
        styleXAxis(conf.getxAxis());
        styleYAxis(conf.getyAxis());

        // Legend text reads against the panel.
        Legend legend = conf.getLegend();
        if (legend != null) {
            Style legendStyle = new Style();
            legendStyle.setColor(CHART_TEXT);
            legend.setItemStyle(legendStyle);
            Style legendHover = new Style();
            legendHover.setColor(SolidColor.WHITE);
            legend.setItemHoverStyle(legendHover);
        }
    }

    private void styleXAxis(XAxis axis) {
        if (axis == null) return;
        axis.setLineColor(CHART_HAIRLINE);
        axis.setTickColor(CHART_HAIRLINE);
        axis.setGridLineColor(CHART_HAIRLINE);
        applyAxisLabelStyle(axis);
    }

    private void styleYAxis(YAxis axis) {
        if (axis == null) return;
        axis.setLineColor(CHART_HAIRLINE);
        axis.setTickColor(CHART_HAIRLINE);
        axis.setGridLineColor(CHART_HAIRLINE);
        applyAxisLabelStyle(axis);
    }

    /** Applies the Mise label color + minimum font-size to an axis's tick labels and title. */
    private void applyAxisLabelStyle(Axis axis) {
        Labels labels = axis.getLabels() != null ? axis.getLabels() : new Labels();
        Style labelStyle = labels.getStyle() != null ? labels.getStyle() : new Style();
        labelStyle.setColor(CHART_LABEL);
        if (labelStyle.getFontSize() == null) labelStyle.setFontSize("10px");
        labels.setStyle(labelStyle);
        axis.setLabels(labels);

        AxisTitle title = axis.getTitle();
        if (title != null) {
            Style titleStyle = title.getStyle() != null ? title.getStyle() : new Style();
            titleStyle.setColor(CHART_TEXT);
            title.setStyle(titleStyle);
            axis.setTitle(title);
        }
    }

    // ── Widget builders ────────────────────────────────────────────────────────

    private Div buildCostTrendWidget(WeeklyCostTrend trend, Long householdId, boolean highlight) {
        var widget = buildWidgetShell("Weekly cost trend", "costTrend", householdId, false, highlight);
        var body = (Div) widget.getChildren()
                .filter(c -> c instanceof Div && ((Div) c).hasClassName("mise-reports-widget-body"))
                .findFirst().orElseGet(Div::new);

        var chartDiv = new Div();
        chartDiv.setId("mise-reports-cost-trend");
        chartDiv.getElement().setAttribute("data-testid", "report-cost-trend");
        chartDiv.addClassName("mise-reports-chart-container");

        if (!trend.points().isEmpty()) {
            Chart chart = new Chart(ChartType.LINE);
            Configuration conf = chart.getConfiguration();
            conf.setTitle("");

            XAxis x = new XAxis();
            String[] categories = trend.points().stream()
                    .map(p -> p.weekStartDate().toString())
                    .toArray(String[]::new);
            x.setCategories(categories);
            // R-V-03: keep date labels horizontal and compact so they don't eat chart area
            Labels xLabels = new Labels();
            xLabels.setRotation(0);
            com.vaadin.flow.component.charts.model.style.Style xLabelStyle =
                    new com.vaadin.flow.component.charts.model.style.Style();
            xLabelStyle.setFontSize("10px");
            xLabels.setStyle(xLabelStyle);
            x.setLabels(xLabels);
            conf.addxAxis(x);

            YAxis y = new YAxis();
            y.setTitle(new AxisTitle("Cost (€)"));
            conf.addyAxis(y);

            // Apply a single brand-aligned hue to the trend line
            PlotOptionsLine lineOpts = new PlotOptionsLine();
            lineOpts.setColor(new com.vaadin.flow.component.charts.model.style.SolidColor(
                    CATEGORY_COLORS.get("Protein")));
            conf.setPlotOptions(lineOpts);

            // R-V-04: hide legend for single-series chart (redundant label)
            Legend trendLegend = new Legend(false);
            conf.setLegend(trendLegend);

            DataSeries series = new DataSeries("Weekly cost");
            for (var point : trend.points()) {
                series.add(new DataSeriesItem(
                        point.weekStartDate().toString(),
                        point.totalCost().doubleValue()));
            }
            conf.addSeries(series);
            applyMiseChartTheme(chart);
            chart.setWidthFull();
            chart.setHeight("220px");
            chartDiv.add(chart);
        } else {
            chartDiv.add(new Span("No data yet — complete at least one week to see the trend."));
        }

        body.add(chartDiv);
        return widget;
    }

    private Div buildCategoryWidget(CategoryBreakdown breakdown, Map<String, Object> prefs,
                                     Long householdId, boolean highlight) {
        var widget = buildWidgetShell("Cost by category", "categoryBreakdown", householdId, true, highlight);
        var body = (Div) widget.getChildren()
                .filter(c -> c instanceof Div && ((Div) c).hasClassName("mise-reports-widget-body"))
                .findFirst().orElseGet(Div::new);

        var chartDiv = new Div();
        chartDiv.setId("mise-reports-category-breakdown");
        chartDiv.getElement().setAttribute("data-testid", "report-category-breakdown");
        chartDiv.addClassName("mise-reports-chart-container");

        if (!breakdown.entries().isEmpty()) {
            String chartTypePref = (String) prefs.getOrDefault("chartType", "donut");
            String orientationPref = (String) prefs.getOrDefault("orientation", "vertical");

            ChartType type;
            if ("bar".equals(chartTypePref)) {
                // BAR in Highcharts/Vaadin Charts = horizontal bar; COLUMN = vertical bar
                type = "horizontal".equals(orientationPref) ? ChartType.BAR : ChartType.COLUMN;
            } else {
                type = ChartType.PIE; // pie with innerSize = donut
            }

            Chart chart = new Chart(type);
            Configuration conf = chart.getConfiguration();
            conf.setTitle("");

            if (ChartType.PIE.equals(type)) {
                // Donut — apply design-system category colors per slice
                DataSeries series = new DataSeries("Cost");
                PlotOptionsPie plotOpts = new PlotOptionsPie();
                plotOpts.setInnerSize("50%");
                // Mockup uses a side legend with color dots, not the
                // connector-line callouts Highcharts draws by default. Disable
                // data labels on the slices so the legend can do the work.
                DataLabels dataLabels = new DataLabels();
                dataLabels.setEnabled(false);
                plotOpts.setDataLabels(dataLabels);
                // Highcharts pie series default to showInLegend = false; flip it
                // so the side legend (configured below) actually renders rows.
                plotOpts.setShowInLegend(true);
                conf.setPlotOptions(plotOpts);
                // R-V-02: vertical legend on the right side (mockup: dot + label + %).
                // Highcharts otherwise places the legend below the chart; at 220px it
                // gets pushed out. Plain-text labelFormat — HTML labels with a span
                // were silently dropping all legend items in 25.2-alpha5.
                Legend pieLegend = new Legend(true);
                pieLegend.setLayout(LayoutDirection.VERTICAL);
                pieLegend.setAlign(HorizontalAlign.RIGHT);
                pieLegend.setVerticalAlign(VerticalAlign.MIDDLE);
                pieLegend.setLabelFormat("{name}  {percentage:.0f}%");
                conf.setLegend(pieLegend);
                for (var entry : breakdown.entries()) {
                    DataSeriesItem item = new DataSeriesItem(
                            entry.category(), entry.totalCost().doubleValue());
                    String color = CATEGORY_COLORS.get(entry.category());
                    if (color != null) {
                        item.setColor(new com.vaadin.flow.component.charts.model.style.SolidColor(color));
                    }
                    series.add(item);
                }
                conf.addSeries(series);
            } else {
                // Bar / Column
                XAxis x = new XAxis();
                String[] cats = breakdown.entries().stream()
                        .map(CategoryCostEntry::category)
                        .toArray(String[]::new);
                x.setCategories(cats);
                conf.addxAxis(x);

                YAxis y = new YAxis();
                y.setTitle(new AxisTitle("Cost (€)"));
                conf.addyAxis(y);

                DataSeries series = new DataSeries("Cost (€)");
                for (var entry : breakdown.entries()) {
                    DataSeriesItem item = new DataSeriesItem(
                            entry.category(), entry.totalCost().doubleValue());
                    String barColor = CATEGORY_COLORS.get(entry.category());
                    if (barColor != null) {
                        item.setColor(new com.vaadin.flow.component.charts.model.style.SolidColor(barColor));
                    }
                    series.add(item);
                }
                conf.addSeries(series);
            }

            applyMiseChartTheme(chart);
            chart.setWidthFull();
            chart.setHeight("220px");
            chartDiv.add(chart);
        } else {
            chartDiv.add(new Span("No data for the current week."));
        }

        body.add(chartDiv);
        return widget;
    }

    private Div buildLeaderboardWidget(List<LeaderboardEntry> entries, List<String> extraColumns,
                                        Long householdId, boolean highlight) {
        var widget = buildWidgetShell("Per-meal leaderboard", "leaderboard", householdId, true, highlight);
        var body = (Div) widget.getChildren()
                .filter(c -> c instanceof Div && ((Div) c).hasClassName("mise-reports-widget-body"))
                .findFirst().orElseGet(Div::new);

        // ── Desktop: standard Vaadin Grid (hidden on mobile via CSS) ─────────
        Grid<LeaderboardEntry> grid = new Grid<>();
        grid.setId("mise-reports-leaderboard");
        grid.getElement().setAttribute("data-testid", "leaderboard-grid");
        grid.addClassName("mise-reports-leaderboard");
        grid.addClassName("mise-reports-leaderboard-desktop");
        grid.setWidthFull();
        grid.setAllRowsVisible(true);

        grid.addColumn(e -> "#" + e.rank()).setHeader("#").setWidth("3.5em").setFlexGrow(0);
        grid.addColumn(LeaderboardEntry::recipeName).setHeader("Meal").setFlexGrow(3);
        grid.addColumn(e -> "×" + e.frequency()).setHeader("Times").setWidth("5em").setFlexGrow(0);
        grid.addColumn(e -> "€" + e.averageCost().toPlainString()).setHeader("Avg cost").setFlexGrow(1);

        if (extraColumns.contains("kcalPerEuro")) {
            grid.addColumn(e -> {
                Object val = e.extras().get("kcalPerEuro");
                return val != null ? val.toString() : "—";
            }).setHeader("kcal/€").setFlexGrow(1);
        }

        grid.setItems(entries);

        // ── Mobile: card list (shown on mobile via CSS, hidden on desktop) ───
        var cardList = new Div();
        cardList.addClassName("mise-reports-leaderboard-cards");
        cardList.getElement().setAttribute("data-testid", "leaderboard-cards");

        for (var entry : entries) {
            var card = new Div();
            card.addClassName("mise-reports-leaderboard-card");

            // Left side: rank + meal name
            var leftGroup = new Div();
            leftGroup.addClassName("mise-reports-leaderboard-card-left");

            var rankSpan = new Span("#" + entry.rank());
            rankSpan.addClassName("mise-reports-leaderboard-card-rank");

            var nameSpan = new Span(entry.recipeName());
            nameSpan.addClassName("mise-reports-leaderboard-card-name");

            leftGroup.add(rankSpan, nameSpan);

            // Right side: times-cooked badge + avg cost
            var rightGroup = new Div();
            rightGroup.addClassName("mise-reports-leaderboard-card-right");

            var timesSpan = new Span("×" + entry.frequency());
            timesSpan.addClassName("mise-reports-leaderboard-card-times");

            var costSpan = new Span("€" + entry.averageCost().toPlainString());
            costSpan.addClassName("mise-reports-leaderboard-card-cost");

            rightGroup.add(timesSpan, costSpan);
            card.add(leftGroup, rightGroup);
            cardList.add(card);
        }

        body.add(grid, cardList);
        return widget;
    }

    // ── Widget shell builder ───────────────────────────────────────────────────

    /**
     * Builds a titled widget card with an optional reset button in the header.
     * The widget body (a Div with class "mise-reports-widget-body") is added as the
     * second child — widget builders append their content to it.
     */
    private Div buildWidgetShell(String title, String widgetKey, Long householdId,
                                  boolean hasReset, boolean highlight) {
        var widget = new Div();
        widget.setId("mise-reports-widget-" + widgetKey);
        widget.addClassName("mise-reports-widget");
        if (highlight) {
            // Briefly highlight after an AI-triggered refresh — CSS fades this out
            widget.addClassName("mise-reports-widget-edited");
        }

        // Header row
        var heading = new H3(title);
        heading.addClassName("mise-reports-widget-title");

        var header = new HorizontalLayout(heading);
        header.addClassName("mise-reports-widget-header");
        header.setWidthFull();
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.setAlignItems(Alignment.CENTER);

        if (hasReset) {
            // Revert icon (rotate-left) — same affordance MealGrid uses for
            // "undo last AI change". The previous × (close-circle-o) read as
            // "remove this widget" rather than "undo my customizations".
            var resetBtn = new Button(VaadinIcon.ROTATE_LEFT.create());
            resetBtn.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY_INLINE);
            resetBtn.getElement().setAttribute("data-testid", "report-reset-" + widgetKey);
            resetBtn.getElement().setAttribute("aria-label", "Revert " + title + " to defaults");
            resetBtn.getElement().setAttribute("title", "Revert to defaults");
            resetBtn.addClassName("mise-reports-reset-btn");
            resetBtn.addClickListener(e -> {
                viewPreferenceService.deleteSettings(householdId, ViewPreference.View.REPORTS, widgetKey);
                loadAndRender(false);
            });
            header.add(resetBtn);
        }

        var body = new Div();
        body.addClassName("mise-reports-widget-body");

        widget.add(header, body);
        return widget;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private List<String> extractExtraColumns(Map<String, Object> prefs) {
        Object raw = prefs.get("extraColumns");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        return List.of();
    }
}
