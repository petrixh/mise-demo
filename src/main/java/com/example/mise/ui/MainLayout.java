package com.example.mise.ui;

import com.example.mise.ai.HouseholdOrchestrator;
import com.example.mise.ai.tools.PlanTools;
import com.example.mise.ai.tools.ReportsTools;
import com.example.mise.ai.tools.ShoppingTools;
import com.example.mise.domain.conversation.ConversationService;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.ui.plan.PlanRefreshBroadcaster;
import com.example.mise.ui.reports.ReportsRefreshBroadcaster;
import com.example.mise.ui.shopping.ShoppingRefreshBroadcaster;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.RouterLayout;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

/**
 * UC-002 MainLayout: header + tabs (Plan/Shopping/Reports) + view outlet + chat dock.
 * The shared {@link HouseholdOrchestrator} lives here and persists across view changes.
 */
public class MainLayout extends VerticalLayout
        implements RouterLayout, AfterNavigationObserver {

    private static final DateTimeFormatter WEEK_FMT = DateTimeFormatter.ofPattern("MMM d");

    private final HouseholdOrchestrator household;
    private final MessageList messageList;
    private final Span lastAiMessageText;
    /** UC-004: holds the "Undo last AI change" button strip shown above the chat input. Hidden when no recent edit. */
    private final Div chatUndoStrip;
    private UI ui;

    // Tab elements kept as fields for active-state management
    private final Div planTab;
    private final Div shoppingTab;
    private final Div reportsTab;

    public MainLayout(LLMProvider llmProvider,
                      ConversationService conversationService,
                      HouseholdService householdService,
                      PlanService planService,
                      PlanTools planTools,
                      ShoppingTools shoppingTools,
                      ReportsTools reportsTools,
                      PlanRefreshBroadcaster planRefreshBroadcaster,
                      ShoppingRefreshBroadcaster shoppingRefreshBroadcaster,
                      ReportsRefreshBroadcaster reportsRefreshBroadcaster) {
        // ── Chat components shared across all views ───────────────────────
        messageList = new MessageList();
        messageList.setMarkdown(true);
        messageList.setSizeFull();

        var messageInput = new MessageInput();
        messageInput.setWidthFull();

        // Capture UI reference (on UI thread) for use in the response-complete
        // callback which runs on a background streaming thread.
        this.ui = UI.getCurrent();

        this.household = new HouseholdOrchestrator(
                llmProvider, conversationService, messageList, messageInput,
                text -> {
                    if (ui != null && !ui.isClosing()) {
                        ui.access(() -> updateLastAiMessage(text));
                    }
                    // BR-08: push plan refresh to all attached PlanView instances after every AI turn
                    planRefreshBroadcaster.fireRefresh();
                    // BR-08: push shopping refresh to all attached ShoppingView instances after every AI turn
                    // Both broadcasters fire here so meal mutations propagate to both views simultaneously.
                    // UC-008 will introduce view-scoped tool registration; for now both tool sets are global.
                    shoppingRefreshBroadcaster.fireRefresh();
                    // UC-007: push reports refresh to all attached ReportsView instances after every AI turn
                    reportsRefreshBroadcaster.fireRefresh();
                },
                planTools, shoppingTools, reportsTools);

        // ── Shell layout ─────────────────────────────────────────────────
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        addClassName("mise-shell");

        // Header
        add(buildHeader(householdService, planService));

        // Tabs
        planTab = makeTab("Plan", "plan");
        shoppingTab = makeTab("Shopping", "shopping");
        reportsTab = makeTab("Reports", "reports");

        var tabsBar = new Div(planTab, shoppingTab, reportsTab);
        tabsBar.addClassName("mise-tabs");
        add(tabsBar);

        // View outlet — Vaadin RouterLayout injects the child route's component here
        // We use a Div as a container; content is managed by RouterLayout
        var outlet = new Div();
        outlet.addClassName("mise-view-outlet");
        outlet.setSizeFull();
        add(outlet);
        expand(outlet);

        // Chat dock
        lastAiMessageText = new Span();
        chatUndoStrip = new Div();
        chatUndoStrip.addClassName("mise-chat-undo-strip");
        chatUndoStrip.setId("mise-chat-undo-last");
        chatUndoStrip.setVisible(false);
        add(buildChatDock(messageInput, chatUndoStrip));
    }

    // ── RouterLayout contract: the framework will call getContent() to find where to put the child ──
    // We need to override the content host. Vaadin looks for HasComponents to inject into.
    // By implementing RouterLayout, the outlet content goes into the VerticalLayout automatically
    // (VerticalLayout implements HasComponents). We just need the visual structure right.
    // The VerticalLayout's last "expand" slot holds the route content.

    private Div buildHeader(HouseholdService householdService, PlanService planService) {
        var brand = new H1("Mise");
        brand.addClassName("mise-brand");

        String weekLabel = buildWeekLabel(householdService, planService);
        var weekBadge = new Span(weekLabel);
        weekBadge.addClassName("mise-week-badge");

        var header = new Div(brand, weekBadge);
        header.addClassName("mise-header");
        return header;
    }

    private String buildWeekLabel(HouseholdService householdService, PlanService planService) {
        try {
            if (householdService.exists()) {
                var hh = householdService.findHousehold().orElse(null);
                if (hh != null) {
                    var plan = planService.findActivePlan(hh.getId()).orElse(null);
                    if (plan != null) {
                        LocalDate start = plan.getWeekStartDate();
                        return "Week of " + start.format(WEEK_FMT);
                    }
                }
            }
        } catch (Exception ignored) {}
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return "Week of " + monday.format(WEEK_FMT);
    }

    private Div makeTab(String label, String route) {
        var tab = new Div(new Span(label));
        tab.addClassName("mise-tab");
        if (route != null) {
            tab.addClickListener(e -> UI.getCurrent().navigate(route));
        } else {
            // Disabled placeholder tabs show a "coming soon" notification
            tab.addClickListener(e -> {
                String msg = "Shopping".equals(label)
                        ? "Shopping coming in UC-005"
                        : "Reports coming in UC-007";
                var n = Notification.show(msg, 2500, Notification.Position.BOTTOM_CENTER);
                n.addThemeVariants(NotificationVariant.LUMO_PRIMARY);
            });
        }
        return tab;
    }

    private Div buildChatDock(MessageInput messageInput, Div undoStrip) {
        var sparkle = new Span("✦");
        sparkle.addClassName("sparkle");
        lastAiMessageText.setText("Ask Mise anything about your week…");

        var lastMsgRow = new Div(sparkle, lastAiMessageText);
        lastMsgRow.addClassName("mise-last-ai-message");
        lastMsgRow.getElement().setAttribute("data-testid", "chat-last-ai-message");

        // Message history scrollable region — visible in DOM at page load (Finding 1).
        // Collapsed to a fixed height so it doesn't dominate the viewport;
        // CSS (.mise-chat-dock:focus-within) can grow it via a focus affordance.
        messageList.addClassName("mise-chat-history");
        messageList.getElement().setAttribute("data-testid", "chat-message-list");

        messageInput.setWidthFull();

        // UC-004: undo strip sits between the last-AI-message row and the input field
        var dock = new Div(messageList, lastMsgRow, undoStrip, messageInput);
        dock.addClassName("mise-chat-dock");
        dock.getElement().setAttribute("data-testid", "chat-dock");
        return dock;
    }

    /** Sync active tab indicator after navigation. */
    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        String location = event.getLocation().getPath();
        planTab.getElement().removeAttribute("active");
        shoppingTab.getElement().removeAttribute("active");
        reportsTab.getElement().removeAttribute("active");

        if (location.startsWith("plan") || location.isEmpty()) {
            planTab.getElement().setAttribute("active", true);
        } else if (location.startsWith("shopping")) {
            shoppingTab.getElement().setAttribute("active", true);
        } else if (location.startsWith("reports")) {
            reportsTab.getElement().setAttribute("active", true);
        }
    }

    /** Updates the most-recent AI message line in the chat dock. */
    public void updateLastAiMessage(String text) {
        if (text != null && !text.isBlank()) {
            lastAiMessageText.setText(text.length() > 120 ? text.substring(0, 117) + "…" : text);
        }
    }

    public HouseholdOrchestrator household() {
        return household;
    }

    public MessageList messageList() {
        return messageList;
    }

    /**
     * UC-004: Programmatically submits a message to the AI orchestrator as if the user typed it.
     * Used by MealGrid's "why?" button to pre-fill and send the chat prompt.
     * Must be called from the UI thread (or wrapped in {@code UI.access(...)}).
     */
    public void submitChatMessage(String text) {
        if (text != null && !text.isBlank()) {
            household.orchestrator().prompt(text);
        }
    }

    /**
     * UC-004: Shows a compact "Undo: [dayLabel]'s [recipe]" pill in the chat dock.
     * Clicking the pill calls the provided undoAction. Hidden when undoAction is null.
     * Must be called from the UI thread.
     *
     * @param dayLabel  e.g. "Thursday"
     * @param recipeName e.g. "Green Thai Curry" (the recipe that was just replaced)
     * @param undoAction the action to run when the user clicks the undo pill; null = hide the strip
     */
    public void showChatUndoStrip(String dayLabel, String recipeName, Runnable undoAction) {
        chatUndoStrip.removeAll();
        if (undoAction == null) {
            chatUndoStrip.setVisible(false);
            return;
        }
        var undoBtn = new com.vaadin.flow.component.button.Button(
                "↩ Undo: " + dayLabel + "'s " + recipeName);
        undoBtn.addClassName("mise-chat-undo-btn");
        undoBtn.setId("mise-chat-undo-last");
        undoBtn.getElement().setAttribute("aria-label", "Undo last AI change for " + dayLabel);
        undoBtn.getElement().setAttribute("data-testid", "chat-action-undo-last");
        undoBtn.addClickListener(e -> {
            undoAction.run();
            chatUndoStrip.setVisible(false);
            chatUndoStrip.removeAll();
        });
        chatUndoStrip.add(undoBtn);
        chatUndoStrip.setVisible(true);
    }

    /**
     * UC-004: Hides the chat-reply undo strip without doing any action.
     * Call when the undo is no longer the most-recent edit for the affected meal.
     */
    public void hideChatUndoStrip() {
        chatUndoStrip.setVisible(false);
        chatUndoStrip.removeAll();
    }
}
