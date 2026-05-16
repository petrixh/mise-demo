package com.example.mise.ui.reports;

import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceService;
import com.example.mise.domain.reports.*;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.charts.Chart;
import com.vaadin.flow.component.charts.model.*;
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
import java.util.stream.IntStream;

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

    private final HouseholdService householdService;
    private final ReportService reportService;
    private final ViewPreferenceService viewPreferenceService;
    private final ReportsRefreshBroadcaster refreshBroadcaster;

    /** Main content area — cleared and rebuilt on each refresh. */
    private final Div contentArea = new Div();

    /** Hook registered with the broadcaster so we can deregister the exact lambda on leave. */
    private Runnable refreshHook;

    public ReportsView(HouseholdService householdService,
                       ReportService reportService,
                       ViewPreferenceService viewPreferenceService,
                       ReportsRefreshBroadcaster refreshBroadcaster) {
        this.householdService = householdService;
        this.reportService = reportService;
        this.viewPreferenceService = viewPreferenceService;
        this.refreshBroadcaster = refreshBroadcaster;

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
        contentArea.addClassName("mise-reports-content-grid");

        // ── 0. KPI strip (M-5) ────────────────────────────────────────────
        WeeklyCostTrend trend = reportService.computeCostTrend(hh.getId());
        contentArea.add(buildKpiStrip(hh, trend));

        // ── 1. Weekly cost trend ──────────────────────────────────────────
        contentArea.add(buildCostTrendWidget(trend, hh.getId(), highlight));

        // ── 2. Category breakdown ─────────────────────────────────────────
        CategoryBreakdown breakdown = reportService.computeCategoryBreakdown(hh.getId(), null);
        Map<String, Object> chartPrefs = viewPreferenceService
                .getSettings(hh.getId(), ViewPreference.View.REPORTS, "categoryBreakdown")
                .orElse(Map.of());
        contentArea.add(buildCategoryWidget(breakdown, chartPrefs, hh.getId(), highlight));

        // ── 3. Leaderboard ────────────────────────────────────────────────
        Map<String, Object> leaderboardPrefs = viewPreferenceService
                .getSettings(hh.getId(), ViewPreference.View.REPORTS, "leaderboard")
                .orElse(Map.of());
        List<String> extraColumns = extractExtraColumns(leaderboardPrefs);
        boolean includeKcalPerEuro = extraColumns.contains("kcalPerEuro");
        List<LeaderboardEntry> leaderboard = reportService.computeLeaderboard(hh.getId(), includeKcalPerEuro);
        contentArea.add(buildLeaderboardWidget(leaderboard, extraColumns, hh.getId(), highlight));
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
                // m-2: set data label color to a light value so callouts are readable on dark theme
                DataLabels dataLabels = new DataLabels();
                dataLabels.setColor(new com.vaadin.flow.component.charts.model.style.SolidColor("#E4E4E7"));
                plotOpts.setDataLabels(dataLabels);
                conf.setPlotOptions(plotOpts);
                // R-V-02: show category legend with percentage alongside each color swatch
                Legend pieLegend = new Legend(true);
                pieLegend.setLabelFormat("{name} <span style=\"opacity:0.6\">{percentage:.0f}%</span>");
                pieLegend.setUseHTML(true);
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

        grid.addColumn(LeaderboardEntry::rank).setHeader("#").setWidth("3em").setFlexGrow(0);
        grid.addColumn(LeaderboardEntry::recipeName).setHeader("Meal").setFlexGrow(3);
        grid.addColumn(LeaderboardEntry::frequency).setHeader("Times").setFlexGrow(1);
        grid.addColumn(e -> "€" + e.averageCost().toPlainString()).setHeader("Avg cost").setFlexGrow(1);
        grid.addColumn(e -> Math.round(e.averageKcal()) + " kcal").setHeader("Kcal avg").setFlexGrow(2);

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

            // Top row: rank + meal name + times cooked
            var topRow = new Div();
            topRow.addClassName("mise-reports-leaderboard-card-top");

            var rankSpan = new Span("#" + entry.rank());
            rankSpan.addClassName("mise-reports-leaderboard-card-rank");

            var nameSpan = new Span(entry.recipeName());
            nameSpan.addClassName("mise-reports-leaderboard-card-name");

            var timesSpan = new Span("×" + entry.frequency());
            timesSpan.addClassName("mise-reports-leaderboard-card-times");

            topRow.add(rankSpan, nameSpan, timesSpan);

            // Bottom row: avg cost + avg kcal
            var bottomRow = new Div();
            bottomRow.addClassName("mise-reports-leaderboard-card-bottom");

            var costSpan = new Span("€" + entry.averageCost().toPlainString());
            costSpan.addClassName("mise-reports-leaderboard-card-cost");

            var kcalSpan = new Span(Math.round(entry.averageKcal()) + " kcal");
            kcalSpan.addClassName("mise-reports-leaderboard-card-kcal");

            bottomRow.add(costSpan, kcalSpan);
            card.add(topRow, bottomRow);
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
            var resetBtn = new Button(VaadinIcon.CLOSE_CIRCLE_O.create());
            resetBtn.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY_INLINE);
            resetBtn.getElement().setAttribute("data-testid", "report-reset-" + widgetKey);
            resetBtn.getElement().setAttribute("aria-label", "Reset " + title);
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

    @SuppressWarnings("unchecked")
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
