package com.example.mise.ui.reports;

import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.insights.Insight;
import com.example.mise.domain.insights.InsightService;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceService;
import com.example.mise.domain.reports.*;
import com.example.mise.ui.shared.CategoryColors;
import com.example.mise.ui.shared.KpiCard;
import com.example.mise.ui.shared.MiseChart;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.charts.model.*;
import com.vaadin.flow.component.charts.model.style.SolidColor;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.example.mise.ui.ViewedWeekService;
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

    private final HouseholdService householdService;
    private final ReportService reportService;
    private final ViewPreferenceService viewPreferenceService;
    private final ReportsRefreshBroadcaster refreshBroadcaster;
    private final InsightService insightService;
    private final ViewedWeekService viewedWeekService;

    /** Main content area — cleared and rebuilt on each refresh. */
    private final Div contentArea = new Div();

    /** Hook registered with the broadcaster so we can deregister the exact lambda on leave. */
    private Runnable refreshHook;

    /** UC-010: the week start date to display (null = most recent plan). */
    private java.time.LocalDate viewedWeekStart;

    public ReportsView(HouseholdService householdService,
                       ReportService reportService,
                       ViewPreferenceService viewPreferenceService,
                       ReportsRefreshBroadcaster refreshBroadcaster,
                       InsightService insightService,
                       ViewedWeekService viewedWeekService) {
        this.householdService = householdService;
        this.reportService = reportService;
        this.viewPreferenceService = viewPreferenceService;
        this.refreshBroadcaster = refreshBroadcaster;
        this.insightService = insightService;
        this.viewedWeekService = viewedWeekService;

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
        // UC-010: resolve the viewed week from the ?week= query param
        String weekParam = event.getLocation().getQueryParameters()
                .getParameters().getOrDefault("week", java.util.List.of()).stream()
                .findFirst().orElse(null);
        if (weekParam != null && !weekParam.isBlank()) {
            try {
                var any = java.time.LocalDate.parse(weekParam.trim());
                viewedWeekStart = viewedWeekService.snapToMonday(any);
            } catch (Exception ignored) {
                viewedWeekStart = null;
            }
        } else {
            viewedWeekStart = null;
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

        CategoryBreakdown breakdown = reportService.computeCategoryBreakdown(hh.getId(), viewedWeekStart);
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

        strip.add(new KpiCard("4-week avg", "€" + String.format("%.2f", fourWeekAvg)));
        strip.add(new KpiCard("this week",  "€" + String.format("%.2f", thisWeek)));
        strip.add(new KpiCard("avg/meal",   "€" + String.format("%.2f", avgMeal)));

        if (budgetSet) {
            String vsLabel = (vsTarget.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") +
                    String.format("%.2f", vsTarget);
            boolean overBudget = vsTarget.compareTo(BigDecimal.ZERO) > 0;
            strip.add(new KpiCard("vs target", "€" + vsLabel, overBudget));
        } else {
            strip.add(new KpiCard("vs target", "—"));
        }

        return strip;
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
            MiseChart chart = new MiseChart(ChartType.LINE);
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
            lineOpts.setColor(new SolidColor(CategoryColors.HEX.get("Protein")));
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
            chart.applyTheme();
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

            MiseChart chart = new MiseChart(type);
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
                    String color = CategoryColors.HEX.get(entry.category());
                    if (color != null) {
                        item.setColor(new SolidColor(color));
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
                    String barColor = CategoryColors.HEX.get(entry.category());
                    if (barColor != null) {
                        item.setColor(new SolidColor(barColor));
                    }
                    series.add(item);
                }
                conf.addSeries(series);
            }

            chart.applyTheme();
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
