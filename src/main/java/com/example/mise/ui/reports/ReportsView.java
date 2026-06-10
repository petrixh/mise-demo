package com.example.mise.ui.reports;

import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.insights.Insight;
import com.example.mise.domain.insights.InsightService;
import com.example.mise.domain.reports.ReportService;
import com.example.mise.domain.reports.WeeklyCostTrend;
import com.example.mise.ui.MainLayout;
import com.example.mise.ui.ViewedWeekService;
import com.example.mise.ui.shared.KpiCard;
import com.example.mise.ui.shared.ViewRefreshBroadcaster;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * UC-007 / UC-012 Reports view at /reports. Single panel: KPI strip → chart row
 * (trend + category) → leaderboard → AI insights block.
 *
 * <p>The chart row and the leaderboard are <b>AI-controller-driven</b>: the
 * components and their {@code ChartAIController}/{@code GridAIController} live
 * in {@link ReportsWidgets} (owned by {@link MainLayout}, registered on the
 * shared orchestrator at build time). This view adopts the components on
 * attach, applies persisted-or-default states, shows the "edited" highlight
 * after AI reshapes (BR-09), and offers per-widget reset (BR-10).
 */
@Route(value = "reports", layout = MainLayout.class)
@PageTitle("Mise — Reports")
public class ReportsView extends VerticalLayout implements BeforeEnterObserver {

    private final HouseholdService householdService;
    private final ReportService reportService;
    private final InsightService insightService;
    private final ViewRefreshBroadcaster refreshBroadcaster;
    private final ViewedWeekService viewedWeekService;

    /** Main content area — cleared and rebuilt on each refresh. */
    private final Div contentArea = new Div();

    /** Widget shells by widgetKey, for the post-reshape "edited" highlight. */
    private final Map<String, Div> widgetShells = new HashMap<>();

    /** Last AI-edited widget + when — re-applied by render(), which rebuilds the
     *  shells and may run before or after the controller's highlight callback
     *  (their relative order depends on UI.access scheduling). */
    private String lastEditedKey;
    private java.time.Instant lastEditedAt = java.time.Instant.EPOCH;

    private ReportsWidgets widgets;
    private Runnable refreshHook;

    /** UC-010: the week start date to display (null = current/most recent plan). */
    private LocalDate viewedWeekStart;

    public ReportsView(HouseholdService householdService,
                       ReportService reportService,
                       InsightService insightService,
                       ViewRefreshBroadcaster refreshBroadcaster,
                       ViewedWeekService viewedWeekService) {
        this.householdService = householdService;
        this.reportService = reportService;
        this.insightService = insightService;
        this.refreshBroadcaster = refreshBroadcaster;
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

        addAttachListener(e -> {
            // Adopt the per-UI widgets from MainLayout (UC-012) and render.
            widgets = e.getUI().getChildren()
                    .filter(MainLayout.class::isInstance)
                    .map(MainLayout.class::cast)
                    .findFirst()
                    .map(MainLayout::reportsWidgets)
                    .orElse(null);
            if (widgets != null) {
                widgets.setEditedListener(this::highlightWidget);
            }
            render();
            widgetStates();

            UI ui = e.getUI();
            // The per-turn hook only rebuilds the scaffolding (KPI strip,
            // insights). The widget DATA refresh deliberately does NOT happen
            // here: a restoreState() in this path can run before the
            // controllers' deferred renders and clear their pending state
            // (observed live 2026-06-10). ReportsWidgets re-applies states from
            // the composite controller's onResponse instead, strictly after
            // the renders.
            refreshHook = () -> ui.access(this::render);
            refreshBroadcaster.register(refreshHook);
        });
        addDetachListener(e -> {
            if (widgets != null) {
                widgets.setEditedListener(null);
            }
            if (refreshHook != null) {
                refreshBroadcaster.deregister(refreshHook);
                refreshHook = null;
            }
        });
    }

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
        viewedWeekStart = null;
        if (weekParam != null && !weekParam.isBlank()) {
            try {
                viewedWeekStart = viewedWeekService.snapToMonday(LocalDate.parse(weekParam.trim()));
            } catch (Exception ignored) {
                // bad ?week= param → fall back to the current week
            }
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** (Re)builds the panel scaffolding and adopts the widget components into it. */
    private void render() {
        var hhOpt = householdService.findHousehold();
        if (hhOpt.isEmpty() || widgets == null) return;
        var hh = hhOpt.get();

        contentArea.removeAll();
        widgetShells.clear();

        var panel = new Div();
        panel.addClassName("mise-reports-panel");
        panel.getElement().setAttribute("data-testid", "reports-panel");

        panel.add(buildKpiStrip(hh));

        var chartRow = new Div();
        chartRow.addClassName("mise-reports-chart-row");
        chartRow.add(widgetShell("Weekly cost trend", ReportsWidgets.KEY_TREND, widgets.trendChart()));
        chartRow.add(widgetShell("Cost by category", ReportsWidgets.KEY_CATEGORY, widgets.categoryChart()));
        panel.add(chartRow);

        panel.add(widgetShell("Per-meal leaderboard", ReportsWidgets.KEY_LEADERBOARD, widgets.leaderboardGrid()));
        panel.add(buildInsightsBlock(hh.getId()));

        contentArea.add(panel);

        // Re-apply a very recent "edited" highlight onto the freshly built shell
        // (the per-turn refresh rebuilds the panel and would otherwise wipe it).
        if (lastEditedKey != null
                && lastEditedAt.isAfter(java.time.Instant.now().minusSeconds(6))) {
            Div shell = widgetShells.get(lastEditedKey);
            if (shell != null) {
                shell.addClassName("mise-reports-widget-edited");
            }
        }
    }

    /** Applies persisted-or-default widget states; re-runs queries → fresh data. */
    private void widgetStates() {
        if (widgets != null) {
            widgets.applyStates(viewedWeekStart);
        }
    }

    /** BR-09: brief inset highlight on the widget the AI just reshaped. */
    private void highlightWidget(String widgetKey) {
        lastEditedKey = widgetKey;
        lastEditedAt = java.time.Instant.now();
        Div shell = widgetShells.get(widgetKey);
        if (shell != null) {
            shell.removeClassName("mise-reports-widget-edited");
            // re-trigger the CSS fade even on consecutive edits of the same widget
            shell.getElement().executeJs("void this.offsetWidth; this.classList.add('mise-reports-widget-edited')");
        }
    }

    /**
     * Titled widget card with a reset affordance; the AI-drivable component is
     * adopted into the card body (re-parenting it from any previous view instance).
     */
    private Div widgetShell(String title, String widgetKey, Component content) {
        var widget = new Div();
        widget.setId("mise-reports-widget-" + widgetKey);
        widget.addClassName("mise-reports-widget");
        widgetShells.put(widgetKey, widget);

        var heading = new H3(title);
        heading.addClassName("mise-reports-widget-title");

        var header = new HorizontalLayout(heading);
        header.addClassName("mise-reports-widget-header");
        header.setWidthFull();
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.setAlignItems(Alignment.CENTER);

        var resetBtn = new Button(VaadinIcon.ROTATE_LEFT.create());
        resetBtn.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY_INLINE);
        resetBtn.getElement().setAttribute("data-testid", "report-reset-" + widgetKey);
        resetBtn.getElement().setAttribute("aria-label", "Revert " + title + " to defaults");
        resetBtn.getElement().setAttribute("title", "Revert to defaults");
        resetBtn.addClassName("mise-reports-reset-btn");
        resetBtn.addClickListener(e -> widgets.resetWidget(widgetKey, viewedWeekStart));
        header.add(resetBtn);

        var body = new Div(content);
        body.addClassName("mise-reports-widget-body");

        widget.add(header, body);
        return widget;
    }

    // ── KPI strip (M-5) — static, sourced from ReportService ─────────────────

    private Div buildKpiStrip(Household hh) {
        var strip = new Div();
        strip.setId("mise-reports-kpi-strip");
        strip.addClassName("mise-kpi-strip");
        strip.getElement().setAttribute("data-testid", "reports-kpi-strip");

        WeeklyCostTrend trend = reportService.computeCostTrend(hh.getId());
        BigDecimal fourWeekAvg = reportService.weeklyAverage(hh.getId(), 4);
        BigDecimal thisWeek = trend.points().isEmpty() ? BigDecimal.ZERO
                : trend.points().get(trend.points().size() - 1).totalCost();
        BigDecimal avgMeal = thisWeek.divide(BigDecimal.valueOf(7), 2, java.math.RoundingMode.HALF_UP);

        strip.add(new KpiCard("4-week avg", "€" + String.format("%.2f", fourWeekAvg)));
        strip.add(new KpiCard("this week",  "€" + String.format("%.2f", thisWeek)));
        strip.add(new KpiCard("avg/meal",   "€" + String.format("%.2f", avgMeal)));

        boolean budgetSet = hh.getWeeklyBudget() != null
                && hh.getWeeklyBudget().compareTo(BigDecimal.ZERO) > 0;
        if (budgetSet) {
            BigDecimal vsTarget = thisWeek.subtract(hh.getWeeklyBudget());
            String vsLabel = (vsTarget.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                    + String.format("%.2f", vsTarget);
            strip.add(new KpiCard("vs target", "€" + vsLabel, vsTarget.compareTo(BigDecimal.ZERO) > 0));
        } else {
            strip.add(new KpiCard("vs target", "—"));
        }
        return strip;
    }

    // ── R-F-01: AI insights block (non-dismissable) ───────────────────────────

    private Div buildInsightsBlock(Long householdId) {
        var block = new Div();
        block.setId("mise-reports-insights-block");
        block.addClassName("mise-reports-insights-block");
        block.getElement().setAttribute("data-testid", "reports-insights-block");

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
}
