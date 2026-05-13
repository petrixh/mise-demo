package com.example.mise.ui.plan;

import com.example.mise.ai.tools.PlanTools;
import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationService;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.ui.MainLayout;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

/**
 * UC-002 Plan view — weekly meal grid + KPI strip + cost-by-category sidebar.
 * The shared chat dock lives in {@link MainLayout}; this view registers PlanTools
 * on the shared orchestrator when active and removes them when leaving.
 */
@Route(value = "plan", layout = MainLayout.class)
@PageTitle("Mise — Plan")
public class PlanView extends VerticalLayout
        implements BeforeEnterObserver, AfterNavigationObserver {

    private final HouseholdService householdService;
    private final PlanService planService;
    private final RecipeCatalog recipeCatalog;
    private final PriceCatalog priceCatalog;
    private final MealCostCalculator mealCostCalculator;

    private Plan activePlan;

    public PlanView(HouseholdService householdService,
                    PlanService planService,
                    RecipeCatalog recipeCatalog,
                    PriceCatalog priceCatalog,
                    MealCostCalculator mealCostCalculator,
                    ConversationService conversationService,
                    PlanTools planTools) {
        this.householdService = householdService;
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.priceCatalog = priceCatalog;
        this.mealCostCalculator = mealCostCalculator;
        // conversationService and planTools are wired in MainLayout; kept as params for Spring DI

        setSizeFull();
        setPadding(false);
        setSpacing(false);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!householdService.exists()) {
            event.forwardTo("welcome");
            return;
        }
        var household = householdService.findHousehold().orElseThrow();
        activePlan = planService.findActivePlan(household.getId()).orElse(null);
        buildUI();
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        // PlanTools are registered at construction time via HouseholdOrchestrator.
        // This hook is available for future per-view tool scoping when the API supports it.
    }

    private void buildUI() {
        removeAll();

        if (activePlan == null) {
            add(new H2("No active plan"));
            add(new Paragraph("Something went wrong during onboarding. Try restarting."));
            return;
        }

        // ── Three-column responsive layout ──────────────────────────────────
        // desktop: KPI+grid in main, category panel in sidebar
        // tablet/mobile: sidebar hidden via CSS

        var planLayout = new Div();
        planLayout.addClassName("mise-plan-layout");
        planLayout.setSizeFull();

        // Main column: KPI strip + meal grid
        var main = new Div();
        main.addClassName("mise-plan-main");

        var kpiStrip = new WeeklyStatsBar(activePlan, planService, recipeCatalog, mealCostCalculator);
        main.add(kpiStrip);

        var mealGrid = new MealGrid(
                activePlan, planService, recipeCatalog, mealCostCalculator,
                this::handlePinToggle,
                this::handleMarkCooked,
                this::handleMarkSkipped
        );
        main.add(mealGrid);

        planLayout.add(main);

        // Sidebar: cost by category
        var sidebar = new Div();
        sidebar.addClassName("mise-plan-sidebar");
        sidebar.add(new CostByCategoryPanel(activePlan, planService, recipeCatalog, priceCatalog));
        planLayout.add(sidebar);

        add(planLayout);
        expand(planLayout);
    }

    // ── Meal action handlers ──────────────────────────────────────────────

    private void handlePinToggle(Long mealId) {
        // Find current pin state and toggle it
        planService.findMeals(activePlan.getId()).stream()
                .filter(m -> m.getId().equals(mealId))
                .findFirst()
                .ifPresent(m -> {
                    planService.pinMeal(mealId, !m.isPinned());
                    refresh();
                    String msg = !m.isPinned() ? "Meal pinned" : "Meal unpinned";
                    Notification.show(msg, 1500, Notification.Position.BOTTOM_CENTER);
                });
    }

    private void handleMarkCooked(Long mealId) {
        planService.markStatus(mealId, com.example.mise.domain.plan.Meal.Status.COOKED);
        refresh();
        Notification.show("Marked as cooked", 1500, Notification.Position.BOTTOM_CENTER);
    }

    private void handleMarkSkipped(Long mealId) {
        planService.markStatus(mealId, com.example.mise.domain.plan.Meal.Status.SKIPPED);
        refresh();
        Notification.show("Marked as skipped", 1500, Notification.Position.BOTTOM_CENTER);
    }

    private void refresh() {
        // Re-fetch meals and rebuild UI in-place
        buildUI();
    }
}
