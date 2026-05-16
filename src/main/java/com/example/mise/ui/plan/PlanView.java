package com.example.mise.ui.plan;

import com.example.mise.ai.tools.PlanTools;
import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationService;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.PinnedMealException;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.ui.MainLayout;
import com.vaadin.flow.component.UI;
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

import java.time.format.DateTimeFormatter;

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
    private final PlanRefreshBroadcaster refreshBroadcaster;

    private static final DateTimeFormatter FULL_DAY_FMT = DateTimeFormatter.ofPattern("EEEE");

    /** Held as a field so we can deregister the exact same lambda on detach. */
    private Runnable refreshHook;

    private Plan activePlan;


    public PlanView(HouseholdService householdService,
                    PlanService planService,
                    RecipeCatalog recipeCatalog,
                    PriceCatalog priceCatalog,
                    MealCostCalculator mealCostCalculator,
                    ConversationService conversationService,
                    PlanTools planTools,
                    PlanRefreshBroadcaster refreshBroadcaster) {
        this.householdService = householdService;
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.priceCatalog = priceCatalog;
        this.mealCostCalculator = mealCostCalculator;
        this.refreshBroadcaster = refreshBroadcaster;
        // conversationService and planTools are wired in MainLayout; kept as params for Spring DI

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        // Register/deregister broadcaster hook so the AI streaming thread can trigger UI refresh
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

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!householdService.exists()) {
            event.forwardTo("welcome");
            return;
        }
        var household = householdService.findHousehold().orElseThrow();
        activePlan = planService.findActivePlan(household.getId()).orElse(null);
        buildUI();
        // Clear any undo strip that persisted from a previous AI swap in this session.
        // The strip re-appears only after the AI makes a new swap (via aiRefresh).
        getMainLayout().ifPresent(MainLayout::hideChatUndoStrip);
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
                this::handleUndo,
                this::handleSubmitChatMessage
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

    /**
     * UC-004: Called when the user clicks the inline "undo" button on an edited meal row.
     * Performs the undo directly (no LLM round-trip) and triggers a UI refresh.
     */
    private void handleUndo(Long mealId) {
        try {
            planService.undoLastEdit(mealId, Meal.Editor.USER);
            // Hide the chat undo strip for the undone meal
            getMainLayout().ifPresent(MainLayout::hideChatUndoStrip);
            refresh();
            Notification.show("Undo applied", 1500, Notification.Position.BOTTOM_CENTER);
        } catch (PinnedMealException e) {
            Notification.show("Cannot undo — meal is pinned. Unpin it first.", 3000,
                    Notification.Position.BOTTOM_CENTER);
        } catch (IllegalArgumentException e) {
            Notification.show("Nothing to undo: " + e.getMessage(), 3000,
                    Notification.Position.BOTTOM_CENTER);
        }
    }

    /**
     * UC-004: Forwards a chat message to the AI orchestrator via MainLayout.
     * Used by MealGrid's "why?" button.
     */
    private void handleSubmitChatMessage(String text) {
        getMainLayout().ifPresent(ml -> ml.submitChatMessage(text));
    }

    /** Returns the parent MainLayout if available. */
    private java.util.Optional<MainLayout> getMainLayout() {
        var parent = getParent();
        while (parent.isPresent()) {
            if (parent.get() instanceof MainLayout ml) {
                return java.util.Optional.of(ml);
            }
            parent = parent.get().getParent();
        }
        return java.util.Optional.empty();
    }

    private void refresh() {
        // Re-fetch meals and rebuild UI in-place (user-initiated, already on UI thread)
        var household = householdService.findHousehold().orElse(null);
        if (household != null) {
            activePlan = planService.findActivePlan(household.getId()).orElse(activePlan);
        }
        buildUI();
    }

    /**
     * Called via UI.access() from the PlanRefreshBroadcaster after an AI turn completes.
     * Re-fetches the active plan so newly-swapped meals appear immediately.
     * UC-004: Also detects which meals were just edited in this turn and updates the
     * chat-reply undo strip in the MainLayout chat dock.
     */
    private void aiRefresh() {
        var household = householdService.findHousehold().orElse(null);
        if (household == null) return;
        activePlan = planService.findActivePlan(household.getId()).orElse(activePlan);

        // UC-004: detect meals edited by AI since the last refresh snapshot.
        // We look for meals whose lastEditedBy=AI and whose most-recent MealEdit
        // is the newest among all meals in the plan. The chat-undo strip shows
        // only the first such meal to keep the UI uncluttered (single-swap case).
        // For multi-swap the strip shows the day name of the first affected meal.
        if (activePlan != null) {
            var meals = planService.findMeals(activePlan.getId());
            // Find the AI-edited meal with the most-recently-written MealEdit row
            com.example.mise.domain.plan.Meal candidate = null;
            java.time.Instant latestEdit = null;
            for (var meal : meals) {
                if (meal.getLastEditedBy() == Meal.Editor.AI) {
                    var edits = planService.findEdits(meal.getId());
                    if (!edits.isEmpty()) {
                        var editInstant = edits.get(0).getChangedAt();
                        if (latestEdit == null || editInstant.isAfter(latestEdit)) {
                            latestEdit = editInstant;
                            candidate = meal;
                        }
                    }
                }
            }

            if (candidate != null) {
                final var finalCandidate = candidate;
                String dayLabel = finalCandidate.getDate().format(FULL_DAY_FMT);
                var edits = planService.findEdits(finalCandidate.getId());
                // The edit row's previousRecipeRef is what was replaced — show that name as
                // "what we reverted" label in the undo button.
                String replacedRef = edits.isEmpty() ? finalCandidate.getRecipeRef()
                        : edits.get(0).getPreviousRecipeRef();

                getMainLayout().ifPresent(ml -> ml.showChatUndoStrip(dayLabel, replacedRef, () -> {
                    handleUndo(finalCandidate.getId());
                }));
            }
        }

        buildUI();
    }
}
