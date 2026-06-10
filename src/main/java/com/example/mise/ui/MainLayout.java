package com.example.mise.ui;

import com.example.mise.ai.HouseholdOrchestrator;
import com.example.mise.ai.MiseDatabaseProvider;
import com.example.mise.ai.tools.InsightTools;
import com.example.mise.ai.tools.NavigationTools;
import com.example.mise.ai.tools.PlanTools;
import com.example.mise.ai.tools.ReportingTools;
import com.example.mise.ai.tools.ShoppingTools;
import com.example.mise.domain.conversation.ConversationMessage;
import com.example.mise.domain.conversation.ConversationService;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.insights.Insight;
import com.example.mise.domain.insights.InsightService;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.domain.preferences.ViewPreferenceService;
import com.example.mise.ui.reports.ReportsWidgets;
import com.example.mise.ui.shared.ViewRefreshBroadcaster;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.RouterLayout;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * UC-002 MainLayout: header + tabs (Plan/Shopping/Reports) + view outlet + chat dock.
 * The shared {@link HouseholdOrchestrator} lives here and persists across view changes.
 */
public class MainLayout extends VerticalLayout
        implements RouterLayout, AfterNavigationObserver {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MainLayout.class);

    private static final DateTimeFormatter WEEK_FMT = DateTimeFormatter.ofPattern("MMM d");

    /**
     * Tabler ti-tools-kitchen-2 icon, copied verbatim from
     * ai-meal-planner/mise/html-mockups-initial/tools-kitchen-2.svg so the
     * stroke="currentColor" path inherits the brand text color when inlined.
     * Same markup lives at /icons/tools-kitchen-2.svg for external references.
     */
    private static final String MISE_LOGO_SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" "
            + "fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" "
            + "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
            + "<path stroke=\"none\" d=\"M0 0h24v24H0z\" fill=\"none\"/>"
            + "<path d=\"M19 3v12h-5c-.023 -3.681 .184 -7.406 5 -12m0 12v6h-1v-3m-10 -14v17m-3 -17v3a3 3 0 1 0 6 0v-3\"/>"
            + "</svg>";

    private final HouseholdOrchestrator household;
    /** UC-012: per-UI Reports widgets + AI controllers; adopted by ReportsView on attach. */
    private final ReportsWidgets reportsWidgets;
    private final MessageList messageList;
    private final Span lastAiMessageText;
    /** The chat dock container — assigned in buildChatDock(); referenced by the
     *  submit + responseComplete hooks to toggle the .ai-working class for the
     *  thinking indicator (wand pulse when collapsed, avatar glow when expanded). */
    private Div chatDock;
    /** UC-004: holds the "Undo last AI change" button strip shown above the chat input. Hidden when no recent edit. */
    private final Div chatUndoStrip;
    /** UC-009: insight banner shown above the view outlet. Hidden when no undismissed insight. */
    private final Div insightBanner;
    private final InsightService insightService;
    private final HouseholdService householdServiceRef;
    private final ViewedWeekService viewedWeekService;
    private final ViewedWeekState viewedWeekState;
    private UI ui;

    // Tab elements kept as fields for active-state management
    private final Div planTab;
    private final Div shoppingTab;
    private final Div reportsTab;

    // UC-010: week navigator controls — kept as fields so afterNavigation can update them
    private Button prevBtn;
    private Button nextBtn;
    private Span weekBadge;
    private DatePicker weekPicker;

    /** UC-010: the current route path (without ?week=) used when navigating prev/next. */
    private String currentRoutePath = "plan";

    public MainLayout(LLMProvider llmProvider,
                      ConversationService conversationService,
                      HouseholdService householdService,
                      PlanService planService,
                      PlanTools planTools,
                      ShoppingTools shoppingTools,
                      ReportingTools reportingTools,
                      NavigationTools navigationTools,
                      InsightTools insightTools,
                      InsightService insightService,
                      ViewRefreshBroadcaster refreshBroadcaster,
                      MiseDatabaseProvider databaseProvider,
                      ViewPreferenceService viewPreferenceService,
                      ViewedWeekService viewedWeekService,
                      ViewedWeekState viewedWeekState) {
        this.insightService = insightService;
        this.householdServiceRef = householdService;
        this.viewedWeekService = viewedWeekService;
        this.viewedWeekState = viewedWeekState;

        // UC-012: per-UI Reports widgets + controllers. Built before the
        // orchestrator so the controllers can be registered at build time
        // (reconnect() is deserialization-only). ReportsView adopts the
        // components on attach via reportsWidgets().
        this.reportsWidgets = new ReportsWidgets(databaseProvider, viewPreferenceService, householdService);
        // ── Chat components shared across all views ───────────────────────
        messageList = new MessageList();
        messageList.setMarkdown(true);
        messageList.setSizeFull();

        var messageInput = new MessageInput();
        messageInput.setWidthFull();

        // AI-thinking indicator (paired with the removeClassName in the response-
        // complete callback below). Set on submit, cleared when streaming finishes.
        // chatDock is assigned later in buildChatDock(); null-check guards the
        // (impossible-in-practice) case where a submit fires before that runs.
        // Also clears any prior .ai-error class so the indicator returns to blue
        // for each new attempt (a successful turn afterwards keeps it cleared).
        messageInput.addSubmitListener(e -> {
            if (chatDock != null) {
                chatDock.removeClassName("ai-error");
                chatDock.addClassName("ai-working");
            }
        });

        // Capture UI reference (on UI thread) for use in the response-complete
        // callback and NavigationTools which both run on background streaming threads.
        this.ui = UI.getCurrent();

        // UC-008: wire the UI supplier into NavigationTools so goToView can call
        // ui.access(() -> ui.navigate(route)) from the background streaming thread.
        navigationTools.setUiSupplier(() -> this.ui);

        this.household = new HouseholdOrchestrator(
                llmProvider, conversationService, messageList, messageInput,
                text -> {
                    if (ui != null && !ui.isClosing()) {
                        ui.access(() -> {
                            updateLastAiMessage(text);
                            // Streaming finished — clear the thinking indicator.
                            if (chatDock != null) chatDock.removeClassName("ai-working");
                        });
                    }
                    // UC-008 BR-08: one refresh channel — every attached view
                    // re-reads its data after each AI turn.
                    refreshBroadcaster.fireRefresh();
                },
                List.of(reportsWidgets.controller()),
                planTools, shoppingTools, reportingTools, navigationTools, insightTools);

        // Error path: LLM unreachable / empty response → red indicator + toast.
        // Runs on the background streaming thread; UI mutations need ui.access().
        this.household.setResponseErrorCallback(errorText -> {
            if (ui == null || ui.isClosing()) return;
            ui.access(() -> {
                if (chatDock != null) {
                    chatDock.removeClassName("ai-working");
                    chatDock.addClassName("ai-error");
                }
                var n = Notification.show(errorText, 4000, Notification.Position.BOTTOM_CENTER);
                n.addThemeVariants(NotificationVariant.LUMO_ERROR);
            });
        });

        // ── Shell layout ─────────────────────────────────────────────────
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        addClassName("mise-shell");

        // Tabs (constructed before header so the header can include them on desktop)
        planTab = makeTab("Plan", "plan");
        shoppingTab = makeTab("Shopping", "shopping");
        reportsTab = makeTab("Reports", "reports");

        var tabsBar = new Div(planTab, shoppingTab, reportsTab);
        tabsBar.addClassName("mise-tabs");

        // Header — tabs live inside it so desktop renders one row (brand · week · budget · nav).
        // On mobile the .mise-tabs child wraps to a second row via flex-wrap (see CSS).
        Div header = buildHeader(householdService, planService);
        header.add(tabsBar);
        add(header);

        // UC-009: insight banner — sits between the tabs and the view outlet.
        // Populated/hidden in afterNavigation.
        insightBanner = new Div();
        insightBanner.setId("mise-insight-banner");
        insightBanner.addClassName("mise-insight-banner");
        insightBanner.getElement().setAttribute("data-testid", "insight-banner");
        insightBanner.setVisible(false);
        add(insightBanner);

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
        // ── Logo + wordmark (left) ───────────────────────────────────────────
        var logo = new Span();
        logo.addClassName("mise-brand-logo");
        logo.getElement().setAttribute("aria-hidden", "true");
        logo.getElement().setProperty("innerHTML", MISE_LOGO_SVG);

        var wordmark = new H1("Mise");
        wordmark.addClassName("mise-brand-wordmark");

        var brand = new Div(logo, wordmark);
        brand.addClassName("mise-brand");
        brand.getElement().setAttribute("data-testid", "app-header-wordmark");
        // UC-010: clicking the brand goes "home" (ACTIVE plan, no ?week= param)
        brand.getElement().setAttribute("role", "button");
        brand.getElement().setAttribute("aria-label", "Go to current week");
        brand.addClickListener(e -> UI.getCurrent().navigate("plan"));

        // ── Week navigator: prev + badge + next + hidden DatePicker ──────────
        // UC-010: buttons are now live. afterNavigation sets enabled state per BR-02.
        prevBtn = new Button(VaadinIcon.ANGLE_LEFT.create());
        prevBtn.addClassName("mise-header-week-nav-btn");
        prevBtn.setId("mise-week-prev");
        prevBtn.getElement().setAttribute("aria-label", "Previous week");
        prevBtn.addClickListener(e -> navigateWeek(-1));

        String weekLabel = buildWeekLabel(householdService, planService);
        weekBadge = new Span(weekLabel);
        weekBadge.addClassName("mise-week-badge");
        weekBadge.setId("mise-week-badge");
        weekBadge.getElement().setAttribute("data-testid", "app-header-week");

        nextBtn = new Button(VaadinIcon.ANGLE_RIGHT.create());
        nextBtn.addClassName("mise-header-week-nav-btn");
        nextBtn.setId("mise-week-next");
        nextBtn.getElement().setAttribute("aria-label", "Next week");
        nextBtn.addClickListener(e -> navigateWeek(+1));

        // UC-010: DatePicker overlay — input field hidden, only the calendar overlay is used.
        // Opened programmatically from the badge click / Enter keydown (BR-07).
        weekPicker = new DatePicker();
        weekPicker.setId("mise-week-datepicker");
        weekPicker.getElement().setAttribute("aria-label", "Select week");
        weekPicker.addClassName("mise-week-datepicker");
        weekPicker.addValueChangeListener(ev -> {
            LocalDate picked = ev.getValue();
            if (picked == null) return;
            LocalDate monday = viewedWeekService.snapToMonday(picked);
            navigateToWeek(monday);
        });
        // Badge click opens the picker
        weekBadge.addClickListener(e -> weekPicker.open());
        weekBadge.getElement().setAttribute("tabindex", "0");
        weekBadge.getElement().addEventListener("keydown", ev -> weekPicker.open())
                .addEventData("event.key")
                .setFilter("event.key === 'Enter' || event.key === ' '");

        // Initially disable nav buttons — afterNavigation enables them
        prevBtn.setEnabled(false);
        nextBtn.setEnabled(false);

        var weekNav = new Div(prevBtn, weekBadge, nextBtn, weekPicker);
        weekNav.addClassName("mise-header-week-nav");

        var header = new Div(brand, weekNav);
        header.setId("mise-app-header");
        header.getElement().setAttribute("data-testid", "app-header");
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
        } catch (Exception e) {
            log.warn("Week label fell back to the current calendar week: {}", e.getMessage());
        }
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return "Week of " + monday.format(WEEK_FMT);
    }

    private Div makeTab(String label, String route) {
        var tab = new Div(new Span(label));
        tab.addClassName("mise-tab");
        if (route != null) {
            tab.addClickListener(e -> {
                // UC-010: preserve the current ?week= param when switching tabs (BR-05)
                String weekParam = viewedWeekState.getCurrentParam();
                if (weekParam != null) {
                    UI.getCurrent().navigate(route,
                            QueryParameters.of("week", weekParam));
                } else {
                    UI.getCurrent().navigate(route);
                }
            });
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
        var sparkleIcon = VaadinIcon.MAGIC.create();
        sparkleIcon.addClassName("sparkle");
        lastAiMessageText.setText("Ask Mise anything about your week…");

        var lastMsgRow = new Div(sparkleIcon, lastAiMessageText);
        lastMsgRow.addClassName("mise-last-ai-message");
        lastMsgRow.getElement().setAttribute("data-testid", "chat-last-ai-message");

        // Message history scrollable region — visible in DOM at page load (Finding 1).
        // Collapsed to a fixed height so it doesn't dominate the viewport;
        // CSS (.mise-chat-dock:focus-within) can grow it via a focus affordance.
        messageList.addClassName("mise-chat-history");
        messageList.getElement().setAttribute("data-testid", "chat-message-list");

        // C-L-01: right-align user messages. AIOrchestrator doesn't expose per-item
        // theming hooks, so a MutationObserver tags non-Mise messages with .current-user.
        // Detection is brittle because vaadin-message sets userName as a property and
        // vaadin-avatar's name is reflected via the inner shadow DOM — checking only the
        // host's getAttribute('name') silently mis-classifies every turn (resolves to null,
        // null !== 'Mise' tags everything). We probe both the property and the attribute
        // on the message host AND the rendered avatar, and only tag once we have a
        // resolved name (otherwise the observer re-runs on the next hydration mutation).
        messageList.getElement().executeJs("""
            const ASSISTANT = 'Mise';
            const resolveName = (msg) => {
              const av = msg.querySelector('vaadin-avatar');
              return msg.userName
                || msg.getAttribute('user-name')
                || (av && (av.name || av.getAttribute('name')))
                || '';
            };
            const mark = () => {
              this.querySelectorAll('vaadin-message').forEach(msg => {
                const name = resolveName(msg);
                if (!name) return;                          // not hydrated yet
                if (msg.dataset.aligned === '1') return;    // already classified
                msg.dataset.aligned = '1';
                if (name !== ASSISTANT) {
                  msg.classList.add('current-user');
                }
              });
            };
            new MutationObserver(mark).observe(this, {
              childList: true,
              subtree: true,
              attributes: true,
              attributeFilter: ['user-name', 'name']
            });
            mark();
            """);

        messageInput.setWidthFull();

        // UC-004: undo strip sits between the last-AI-message row and the input field
        var dock = new Div(messageList, lastMsgRow, undoStrip, messageInput);
        dock.addClassName("mise-chat-dock");
        dock.getElement().setAttribute("data-testid", "chat-dock");
        this.chatDock = dock;  // exposed to constructor hooks for .ai-working toggling

        // On expand (focus enters the dock), scroll the message history to the
        // most recent turn. scrollHeight is read at transitionend so the
        // container has its final height before we assign scrollTop.
        dock.getElement().executeJs("""
            this.addEventListener('focusin', () => {
              const h = this.querySelector('.mise-chat-history');
              if (!h) return;
              const scrollNow = () => { h.scrollTop = h.scrollHeight; };
              scrollNow();
              h.addEventListener('transitionend', scrollNow, { once: true });
            });
            """);
        return dock;
    }

    /** Sync active tab indicator, orchestrator view context, insight banner, and week nav after navigation. */
    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        String location = event.getLocation().getPath();
        planTab.getElement().removeAttribute("active");
        shoppingTab.getElement().removeAttribute("active");
        reportsTab.getElement().removeAttribute("active");

        ConversationMessage.ViewContext viewContext;
        if (location.startsWith("plan") || location.isEmpty()) {
            planTab.getElement().setAttribute("active", true);
            viewContext = ConversationMessage.ViewContext.PLAN;
            currentRoutePath = "plan";
        } else if (location.startsWith("shopping")) {
            shoppingTab.getElement().setAttribute("active", true);
            viewContext = ConversationMessage.ViewContext.SHOPPING;
            currentRoutePath = "shopping";
        } else if (location.startsWith("reports")) {
            reportsTab.getElement().setAttribute("active", true);
            viewContext = ConversationMessage.ViewContext.REPORTS;
            currentRoutePath = "reports";
        } else {
            viewContext = ConversationMessage.ViewContext.PLAN;
        }

        // UC-008 (BR-03): keep the orchestrator's view context in sync with the active route
        household.setCurrentView(viewContext);

        // UC-010: read ?week= param and update badge + buttons + session state
        String weekParam = event.getLocation().getQueryParameters()
                .getParameters().getOrDefault("week", List.of()).stream()
                .findFirst().orElse(null);
        updateWeekNav(weekParam);

        // UC-009: update insight banner
        boolean isWelcome = location.startsWith("welcome") || location.startsWith("onboarding");
        boolean isPlan = location.startsWith("plan") || location.isEmpty();
        boolean isReports = location.startsWith("reports");
        updateInsightBanner(isWelcome || isPlan || isReports);
    }

    /**
     * UC-010: Updates the week badge label, badge modifier classes, prev/next enabled state,
     * DatePicker bounds, and the ViewedWeekState session bean after each navigation.
     */
    private void updateWeekNav(String weekParam) {
        try {
            var hhOpt = householdServiceRef.findHousehold();
            if (hhOpt.isEmpty()) {
                // BR-08: pre-onboarding fallback — show placeholder, disable everything
                weekBadge.setText("Week of " + LocalDate.now()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .format(WEEK_FMT));
                weekBadge.getElement().removeAttribute("tabindex");
                prevBtn.setEnabled(false);
                nextBtn.setEnabled(false);
                viewedWeekState.clear();
                return;
            }
            var hh = hhOpt.get();

            // Resolve the viewed plan
            var viewedPlan = viewedWeekService.resolveViewedPlan(hh.getId(), weekParam).orElse(null);
            if (viewedPlan == null) {
                // No plans at all — same fallback
                weekBadge.setText("Week of " + LocalDate.now()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .format(WEEK_FMT));
                prevBtn.setEnabled(false);
                nextBtn.setEnabled(false);
                viewedWeekState.clear();
                return;
            }

            LocalDate viewedMonday = viewedPlan.getWeekStartDate();

            // Sync session state for chat tools (BR-06)
            viewedWeekState.setViewedMonday(viewedMonday);

            // Update badge label
            weekBadge.setText("Week of " + viewedMonday.format(WEEK_FMT));

            // Update badge modifier class (UC-010 visual distinction)
            weekBadge.removeClassNames("mise-week-badge--past", "mise-week-badge--future");
            if (viewedPlan.getStatus() == Plan.Status.ACTIVE) {
                // default styling (no modifier)
            } else {
                var activePlanOpt = viewedWeekService.resolveViewedPlan(hh.getId(), null);
                boolean isAfterActive = activePlanOpt.isPresent()
                        && viewedMonday.isAfter(activePlanOpt.get().getWeekStartDate());
                if (isAfterActive) {
                    weekBadge.addClassName("mise-week-badge--future");
                } else {
                    weekBadge.addClassName("mise-week-badge--past");
                }
            }

            // Update prev/next enabled state (BR-02)
            var prevPlan = viewedWeekService.previousPlan(hh.getId(), viewedMonday);
            var nextPlan = viewedWeekService.nextPlan(hh.getId(), viewedMonday);
            prevBtn.setEnabled(prevPlan.isPresent());
            nextBtn.setEnabled(nextPlan.isPresent());

            // Update DatePicker bounds (BR-07)
            var allPlans = viewedWeekService.allPlansOrderedAsc(hh.getId());
            if (!allPlans.isEmpty()) {
                LocalDate minDate = allPlans.get(0).getWeekStartDate();
                LocalDate maxDate = allPlans.get(allPlans.size() - 1).getWeekStartDate().plusDays(6);
                weekPicker.setMin(minDate);
                weekPicker.setMax(maxDate);
            }
            // Suppress value-change event when setting picker to the current week
            weekPicker.setValue(viewedMonday);

        } catch (Exception e) {
            // Never break navigation for week-nav rendering
        }
    }

    /**
     * UC-010: Navigates to the prev (-1) or next (+1) plan relative to the current viewed week.
     */
    private void navigateWeek(int direction) {
        try {
            var hhOpt = householdServiceRef.findHousehold();
            if (hhOpt.isEmpty()) return;
            var hh = hhOpt.get();

            LocalDate current = viewedWeekState.getViewedMonday();
            if (current == null) {
                var activePlan = viewedWeekService.resolveViewedPlan(hh.getId(), null).orElse(null);
                if (activePlan == null) return;
                current = activePlan.getWeekStartDate();
            }

            var targetPlan = direction < 0
                    ? viewedWeekService.previousPlan(hh.getId(), current)
                    : viewedWeekService.nextPlan(hh.getId(), current);

            targetPlan.ifPresent(p -> navigateToWeek(p.getWeekStartDate()));
        } catch (Exception ignored) {}
    }

    /**
     * UC-010: Navigates to the current route with ?week=YYYY-MM-DD for the given Monday.
     * If the target is the ACTIVE plan's week, navigates without the param (clean URL).
     */
    private void navigateToWeek(LocalDate monday) {
        try {
            var hhOpt = householdServiceRef.findHousehold();
            if (hhOpt.isEmpty()) return;
            var hh = hhOpt.get();

            // If we're navigating to the same week already shown, skip (no-op per AC)
            if (monday.equals(viewedWeekState.getViewedMonday())) return;

            // Check if the target is the ACTIVE plan — if so, navigate without param
            var activePlan = viewedWeekService.resolveViewedPlan(hh.getId(), null).orElse(null);
            if (activePlan != null && monday.equals(activePlan.getWeekStartDate())) {
                UI.getCurrent().navigate(currentRoutePath);
            } else {
                UI.getCurrent().navigate(currentRoutePath,
                        QueryParameters.of("week", monday.toString()));
            }
        } catch (Exception ignored) {}
    }

    /**
     * UC-009: Rebuilds the insight banner from the current undismissed insight.
     * Hidden on the welcome/onboarding route (spec: "No insights shown on /welcome").
     */
    private void updateInsightBanner(boolean hideForWelcome) {
        if (hideForWelcome) {
            insightBanner.setVisible(false);
            insightBanner.removeAll();
            return;
        }

        try {
            var hhOpt = householdServiceRef.findHousehold();
            if (hhOpt.isEmpty()) {
                insightBanner.setVisible(false);
                return;
            }

            var insightOpt = insightService.currentInsight(hhOpt.get().getId());
            if (insightOpt.isEmpty()) {
                insightBanner.setVisible(false);
                insightBanner.removeAll();
                return;
            }

            Insight insight = insightOpt.get();
            renderInsightBanner(insight);
        } catch (Exception e) {
            // Never break navigation for insight rendering
            insightBanner.setVisible(false);
        }
    }

    private void renderInsightBanner(Insight insight) {
        insightBanner.removeAll();

        // M-7: bulb icon prefix per design system §"AI insight callout"
        var bulbIcon = VaadinIcon.LIGHTBULB.create();
        bulbIcon.addClassName("mise-insight-banner-icon");

        var bodySpan = new Span(insight.getBody());
        bodySpan.getElement().setAttribute("data-testid", "insight-banner-body");
        bodySpan.addClassName("mise-insight-banner-body");

        // Derive the "act on it" phrase
        String actPhrase = deriveActPhrase(insight.getBody());
        var actBtn = new Button("Act on it");
        actBtn.addClassName("mise-insight-banner-act");
        actBtn.getElement().setAttribute("data-testid", "insight-banner-act");
        actBtn.getElement().setAttribute("aria-label", "Act on this insight");
        actBtn.addClickListener(e -> submitChatMessage(actPhrase));

        var dismissBtn = new Button("×");
        dismissBtn.addClassName("mise-insight-banner-dismiss");
        dismissBtn.getElement().setAttribute("data-testid", "insight-banner-dismiss");
        dismissBtn.getElement().setAttribute("aria-label", "Dismiss insight");
        dismissBtn.addClickListener(e -> {
            try {
                insightService.dismiss(insight.getId());
            } catch (Exception ex) {
                // ignore — banner disappears anyway
            }
            insightBanner.setVisible(false);
            insightBanner.removeAll();
        });

        insightBanner.add(bulbIcon, bodySpan, actBtn, dismissBtn);
        insightBanner.setVisible(true);
    }

    /**
     * Derives the "act on it" chat phrase from the insight body.
     * If the insight mentions "vegetarian", pre-fills a plan-lock request.
     * Otherwise sends the insight body verbatim for the model to interpret.
     */
    private String deriveActPhrase(String body) {
        if (body != null && body.toLowerCase().contains("vegetarian")) {
            return "lock in 3 vegetarian dinners this week";
        }
        return body;
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

    /** UC-012: the Reports widgets owned by this UI's layout. */
    public ReportsWidgets reportsWidgets() {
        return reportsWidgets;
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
