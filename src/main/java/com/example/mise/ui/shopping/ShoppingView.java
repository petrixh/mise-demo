package com.example.mise.ui.shopping;

import com.example.mise.ui.shared.ViewRefreshBroadcaster;
import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceService;
import com.example.mise.domain.shopping.*;
import com.example.mise.ui.ViewedWeekService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.math.BigDecimal;
import java.util.*;

/**
 * UC-005 Shopping list view at /shopping.
 * Shows a derived, aisle-grouped shopping list with store-mode toggle and pantry section.
 */
@Route(value = "shopping", layout = com.example.mise.ui.MainLayout.class)
@PageTitle("Mise — Shopping")
public class ShoppingView extends VerticalLayout implements BeforeEnterObserver {

    private final HouseholdService householdService;
    private final ShoppingService shoppingService;
    private final PantryService pantryService;
    private final ViewPreferenceService viewPreferenceService;
    private final ViewRefreshBroadcaster refreshBroadcaster;
    private final ViewedWeekService viewedWeekService;
    private final DetourEvaluator detourEvaluator;
    private final PriceCatalog priceCatalog;

    /** Session-local check-off state (BR-07 — not persisted). */
    private final Set<String> checkedItems = new HashSet<>();

    /** Current plan id — used to detect plan changes so check-off state can be cleared (BR-07). */
    private Long lastSeenPlanId;

    /** Current store mode — reflected in the UI toggle. */
    private StoreMode currentStoreMode = StoreMode.ONE_STORE;

    /** The non-active mode's derived list — feeds the toggle's trade-off summary. */
    private ShoppingList alternativeList;

    /** Held as a field so we can deregister the exact same lambda on detach. */
    private Runnable refreshHook;

    /** Currently derived list (null before first load). */
    private ShoppingList currentList;

    /** Currently viewed plan (null = active plan). Set in beforeEnter from ?week= param. */
    private Plan viewedPlan;

    public ShoppingView(HouseholdService householdService,
                        ShoppingService shoppingService,
                        PantryService pantryService,
                        ViewPreferenceService viewPreferenceService,
                        ViewRefreshBroadcaster refreshBroadcaster,
                        DetourEvaluator detourEvaluator,
                        PriceCatalog priceCatalog,
                        ViewedWeekService viewedWeekService) {
        this.householdService = householdService;
        this.shoppingService = shoppingService;
        this.pantryService = pantryService;
        this.viewPreferenceService = viewPreferenceService;
        this.refreshBroadcaster = refreshBroadcaster;
        this.detourEvaluator = detourEvaluator;
        this.priceCatalog = priceCatalog;
        this.viewedWeekService = viewedWeekService;

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
        // UC-010: resolve the viewed plan from the ?week= query param
        String weekParam = event.getLocation().getQueryParameters()
                .getParameters().getOrDefault("week", java.util.List.of()).stream()
                .findFirst().orElse(null);
        var hhOpt = householdService.findHousehold();
        if (hhOpt.isPresent()) {
            viewedPlan = viewedWeekService.resolveViewedPlan(hhOpt.get().getId(), weekParam).orElse(null);
        }
        loadAndRender();
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private void loadAndRender() {
        var hhOpt = householdService.findHousehold();
        if (hhOpt.isEmpty()) return;
        var hh = hhOpt.get();

        // Resolve stored store mode
        currentStoreMode = viewPreferenceService
                .getSettings(hh.getId(), ViewPreference.View.SHOPPING, "storeMode")
                .map(s -> {
                    Object val = s.get("mode");
                    return "CHEAPEST_MIX".equals(val) ? StoreMode.CHEAPEST_MIX : StoreMode.ONE_STORE;
                })
                .orElse(StoreMode.ONE_STORE);

        // UC-010: derive list for the viewed plan when one is selected (BR-03)
        // The alternative mode's list is derived too — the mode toggle shows the
        // other option's total + stop count so the store trade-off is visible
        // at a glance (concept mockup: "Cheapest mix · €84.40, 2 stops").
        StoreMode altMode = currentStoreMode == StoreMode.ONE_STORE
                ? StoreMode.CHEAPEST_MIX : StoreMode.ONE_STORE;
        if (viewedPlan != null) {
            currentList = shoppingService.deriveListForPlan(hh.getId(), viewedPlan.getId(), currentStoreMode);
            alternativeList = shoppingService.deriveListForPlan(hh.getId(), viewedPlan.getId(), altMode);
        } else {
            currentList = shoppingService.deriveList(hh.getId(), currentStoreMode);
            alternativeList = shoppingService.deriveList(hh.getId(), altMode);
        }
        buildUI(hh.getId(), currentList);
    }

    /** "€84.40, 2 stops" — the mode trade-off summary for the toggle pill. */
    private String modeSummary(ShoppingList list) {
        if (list == null) return "";
        var tradeoff = shoppingService.tradeoff(list);
        return String.format("€%.2f, %d %s", tradeoff.total(), tradeoff.stops(),
                tradeoff.stops() == 1 ? "stop" : "stops");
    }

    private int itemCount(ShoppingList list) {
        return list.aisleGroups().stream().mapToInt(g -> g.items().size()).sum();
    }

    private void buildUI(Long householdId, ShoppingList list) {
        removeAll();

        var content = new Div();
        content.addClassName("mise-shopping-layout");

        // ── Recommendation panel — always at the top of the view ─────────────
        // Design puts "Best store this week" as the leading element so users
        // immediately see which store to shop and the total cost before the list.
        content.add(buildRecommendationPanel(householdId, list));

        // ── Header strip (mobile-only: mode toggle fallback) ──────────────────
        // On desktop the panel above covers store identity; strip is hidden via CSS.
        content.add(buildHeaderStrip(householdId, list));

        // ── Shopping list column ───────────────────────────────────────────────
        var listCol = new Div();
        listCol.addClassName("mise-shopping-list-col");

        // ── "This week" section heading with AI-generated indicator ──────────
        var weekHeading = new Div();
        weekHeading.addClassName("mise-shopping-week-heading");
        weekHeading.getElement().setAttribute("title", "This list is AI-generated from your weekly plan");

        var wandIcon = VaadinIcon.MAGIC.create();
        wandIcon.addClassName("mise-shopping-ai-wand");
        weekHeading.add(wandIcon, new Span("This week"));
        listCol.add(weekHeading);

        // ── Pantry "You already have" section ─────────────────────────────────
        listCol.add(buildPantrySection(householdId, list.pantrySection()));

        // ── Aisle groups ──────────────────────────────────────────────────────
        if (list.aisleGroups().isEmpty()) {
            var empty = new Paragraph("Your shopping list is empty — pantry covers everything.");
            empty.addClassName("mise-shopping-empty");
            listCol.add(empty);
        } else {
            for (var group : list.aisleGroups()) {
                listCol.add(buildAisleGroup(householdId, group, list));
            }
        }

        content.add(listCol);

        add(content);
        expand(content);
    }

    private Div buildHeaderStrip(Long householdId, ShoppingList list) {
        var strip = new Div();
        strip.addClassName("mise-shopping-header-strip");

        // Recommended store badge (shown on mobile; desktop defers to recommendation panel)
        var storeBadge = new Span();
        storeBadge.setId("mise-shopping-store-recommended");
        storeBadge.getElement().setAttribute("data-testid", "recommended-store");
        storeBadge.addClassName("mise-shopping-store-badge");
        if (list.recommendedStore() != null) {
            storeBadge.setText(list.recommendedStore().getName());
        } else {
            storeBadge.setText("—");
        }
        strip.add(storeBadge);

        // Total cost (header strip — mobile only; testid lives on the panel span which is always visible)
        var totalCostSpan = new Span();
        totalCostSpan.addClassName("mise-shopping-total-cost");
        BigDecimal total = list.totalCost() != null ? list.totalCost() : BigDecimal.ZERO;
        totalCostSpan.setText("€" + total.toPlainString());
        strip.add(totalCostSpan);

        // Store-mode segmented control (mobile only — desktop version lives in recommendation panel)
        strip.add(buildModeControl(householdId, "mise-shopping-store-mode", "mise-shopping-mode-control-mobile"));

        return strip;
    }

    /**
     * Builds the store-mode segmented control. Used in both header strip (mobile)
     * and recommendation panel (desktop). Per the concept mockup the non-active
     * option carries its trade-off summary ("Cheapest mix · €84.40, 2 stops") so
     * the user sees what switching buys before clicking.
     */
    private Div buildModeControl(Long householdId, String id, String extraClass) {
        // Outer wrapper carries the visible id / extra class for CSS visibility toggling
        var modeControl = new Div();
        if (id != null) modeControl.setId(id);
        modeControl.getElement().setAttribute("data-testid", "store-mode-toggle");
        modeControl.addClassName("mise-shopping-mode-control");
        if (extraClass != null) modeControl.addClassName(extraClass);

        // M-2: shared track container per design-system §"Toggle track"
        var track = new Div();
        track.addClassName("mise-shopping-mode-track");

        boolean oneStoreActive = currentStoreMode == StoreMode.ONE_STORE;
        String altSummary = modeSummary(alternativeList);

        var oneStoreBtn = new Button(oneStoreActive || altSummary.isEmpty()
                ? "One store" : "One store · " + altSummary);
        oneStoreBtn.getElement().setAttribute("data-testid", "store-mode-one");
        oneStoreBtn.addClassName("mise-shopping-mode-btn");
        if (oneStoreActive) oneStoreBtn.addClassName("active");
        oneStoreBtn.addClickListener(e -> onStoreModeChange(householdId, StoreMode.ONE_STORE));

        var cheapestMixBtn = new Button(!oneStoreActive || altSummary.isEmpty()
                ? "Cheapest mix" : "Cheapest mix · " + altSummary);
        cheapestMixBtn.getElement().setAttribute("data-testid", "store-mode-mix");
        cheapestMixBtn.addClassName("mise-shopping-mode-btn");
        if (!oneStoreActive) cheapestMixBtn.addClassName("active");
        cheapestMixBtn.addClickListener(e -> onStoreModeChange(householdId, StoreMode.CHEAPEST_MIX));

        track.add(oneStoreBtn, cheapestMixBtn);
        modeControl.add(track);
        return modeControl;
    }

    private Details buildPantrySection(Long householdId, PantrySection section) {
        var pantryDiv = new Div();
        pantryDiv.addClassName("mise-shopping-pantry-chips");

        if (section.items().isEmpty()) {
            pantryDiv.add(new Span("No pantry items — add staples via chat."));
        } else {
            for (var item : section.items()) {
                var chipWrap = new Div();
                chipWrap.addClassName("mise-shopping-pantry-chip-wrap");
                chipWrap.getElement().setAttribute("data-testid", "pantry-chip");

                var label = new Span(item.getIngredientName() + (item.isStaple() ? " ✦" : ""));
                label.addClassName("mise-shopping-pantry-chip-label");

                var removeBtn = new Button("×");
                removeBtn.addClassName("mise-shopping-pantry-remove-btn");
                removeBtn.getElement().setAttribute("aria-label", "Remove " + item.getIngredientName() + " from pantry");
                removeBtn.getElement().setAttribute("data-testid", "pantry-chip-remove");
                removeBtn.addClickListener(e -> handlePantryRemove(householdId, item));

                chipWrap.add(label, removeBtn);
                pantryDiv.add(chipWrap);
            }
        }

        var details = new Details("You already have (" + section.items().size() + ")", pantryDiv);
        details.setId("mise-shopping-pantry-section");
        details.getElement().setAttribute("data-testid", "pantry-section");
        details.addClassName("mise-shopping-pantry-section");
        details.setOpened(false); // collapsed by default

        return details;
    }

    /**
     * Right-column recommendation panel (desktop) / top-stacked collapsible block (mobile).
     * Per the concept mockup the panel is about <b>which store to go to</b>: store
     * headline with "€total · N items · M stops" meta, a comparison narrative, and
     * the mode toggle carrying the alternative's trade-off — no per-category pricing
     * (category costs live in the Plan sidebar and Reports).
     * On mobile the body is collapsed by default (Issue #21); a chevron toggle in the
     * header lets the user expand/collapse inline.
     */
    private Div buildRecommendationPanel(Long householdId, ShoppingList list) {
        var panel = new Div();
        panel.setId("mise-shopping-recommendation-panel");
        panel.getElement().setAttribute("data-testid", "recommendation-panel");
        panel.addClassName("mise-shopping-recommendation-panel");

        // Label row — dynamic per store mode, uppercase
        String labelText = currentStoreMode == StoreMode.CHEAPEST_MIX
                ? "CHEAPEST MIX THIS WEEK"
                : "BEST STORE THIS WEEK";
        var label = new Span(labelText);
        label.addClassName("mise-shopping-rec-label");

        // Store name as headline
        var storeHeadline = new H2();
        storeHeadline.addClassName("mise-shopping-rec-store-name");
        String defaultStoreName = list.recommendedStore() != null
                ? list.recommendedStore().getName() : "—";
        storeHeadline.setText(defaultStoreName);

        // Meta line next to the headline: "€87.40 · 13 items · 1 stop" (mockup) —
        // mode-faithful numbers (one-store basket vs cheapest-mix total).
        // Always visible (lives in the header), so mobile sees the total when collapsed.
        var tradeoff = shoppingService.tradeoff(list);
        int items = itemCount(list);
        var meta = new Span(String.format("€%.2f · %d %s · %d %s",
                tradeoff.total(), items, items == 1 ? "item" : "items",
                tradeoff.stops(), tradeoff.stops() == 1 ? "stop" : "stops"));
        meta.addClassName("mise-shopping-rec-meta");
        meta.getElement().setAttribute("data-testid", "shopping-total-cost");

        // Chevron toggle button — visible on mobile, hidden on desktop
        var chevron = new Button(VaadinIcon.CHEVRON_DOWN.create());
        chevron.addClassName("mise-shopping-rec-chevron");
        chevron.getElement().setAttribute("aria-label", "Expand store details");
        chevron.getElement().setAttribute("data-testid", "rec-panel-toggle");

        // Summary header — always visible; contains label, store name + meta, chevron
        var header = new Div();
        header.addClassName("mise-shopping-rec-header");

        var headlineRow = new Div();
        headlineRow.addClassName("mise-shopping-rec-headline-row");
        headlineRow.add(storeHeadline, meta);

        var headerLeft = new Div();
        headerLeft.addClassName("mise-shopping-rec-header-left");
        headerLeft.add(label, headlineRow);

        var headerRight = new Div();
        headerRight.addClassName("mise-shopping-rec-header-right");
        headerRight.add(chevron);

        header.add(headerLeft, headerRight);
        panel.add(header);

        // Collapsible body — hidden on mobile by default, always visible on desktop
        var body = new Div();
        body.addClassName("mise-shopping-rec-body");
        body.addClassName("collapsed"); // starts collapsed on mobile (CSS hides it)
        body.getElement().setAttribute("data-testid", "rec-panel-body");

        // M-1: comparison narrative — context sentence per design system §"Recommendation card"
        String narrative = buildComparisonNarrative(householdId, list, defaultStoreName);
        if (narrative != null && !narrative.isBlank()) {
            var narrativeSpan = new Span(narrative);
            narrativeSpan.addClassName("mise-shopping-rec-narrative");
            body.add(narrativeSpan);
        }

        // Mode toggle — always visible in the panel (panel is now top-of-view on all breakpoints)
        body.add(buildModeControl(householdId, null, "mise-shopping-mode-control-panel"));

        panel.add(body);

        // Chevron click: toggle 'collapsed' class on body; 'open' on chevron = expanded state
        chevron.addClickListener(e -> {
            boolean isCollapsed = body.hasClassName("collapsed");
            if (isCollapsed) {
                // Expand: show body, rotate chevron up
                body.removeClassName("collapsed");
                chevron.addClassName("open");
                chevron.getElement().setAttribute("aria-label", "Collapse store details");
            } else {
                // Collapse: hide body, reset chevron
                body.addClassName("collapsed");
                chevron.removeClassName("open");
                chevron.getElement().setAttribute("aria-label", "Expand store details");
            }
        });

        return panel;
    }

    private Div buildAisleGroup(Long householdId, AisleGroup group, ShoppingList list) {
        var aisleDiv = new Div();
        String aisleSlug = group.aisle().toLowerCase().replace(' ', '-');
        aisleDiv.setId("mise-shopping-aisle-" + aisleSlug);
        aisleDiv.addClassName("mise-shopping-aisle-group");

        var header = new Div(new Span(group.aisle().toUpperCase()));
        header.addClassName("mise-shopping-aisle-header");
        aisleDiv.add(header);

        // Sort: unchecked items first, checked items at bottom (BR: list reflows)
        var items = new ArrayList<>(group.items());
        items.sort(Comparator.comparing(item -> checkedItems.contains(item.ingredientName().toLowerCase())));

        for (var item : items) {
            aisleDiv.add(buildShoppingRow(householdId, item, list));
        }

        return aisleDiv;
    }

    private Div buildShoppingRow(Long householdId, ShoppingItem item, ShoppingList list) {
        var row = new Div();
        String slug = item.ingredientName().toLowerCase().replaceAll("[^a-z0-9]", "-");
        row.setId("mise-shopping-row-" + slug);
        row.getElement().setAttribute("data-testid", "shopping-row");
        row.addClassName("mise-shopping-row");
        boolean isChecked = checkedItems.contains(item.ingredientName().toLowerCase());
        if (isChecked) row.addClassName("checked");

        // Check-off button
        var checkBtn = new Button(isChecked ? "✓" : "○");
        checkBtn.addClassName("mise-shopping-check-btn");
        checkBtn.getElement().setAttribute("aria-label",
                (isChecked ? "Uncheck " : "Check ") + item.ingredientName());
        checkBtn.addClickListener(e -> {
            String key = item.ingredientName().toLowerCase();
            if (checkedItems.contains(key)) {
                checkedItems.remove(key);
            } else {
                checkedItems.add(key);
            }
            // Reflow: rebuild the list while preserving sort order within each group
            currentList = shoppingService.deriveList(householdId, currentStoreMode);
            buildUI(householdId, currentList);
        });
        row.add(checkBtn);

        // Item info
        var info = new Div();
        info.addClassName("mise-shopping-item-info");

        var nameLine = new Span(item.ingredientName());
        nameLine.addClassName("mise-shopping-item-name");
        info.add(nameLine);

        // Quantity + unit
        var qtySpan = new Span(formatQuantity(item.quantity()) + " " + (item.unit() != null ? item.unit() : ""));
        qtySpan.addClassName("mise-shopping-item-qty");
        info.add(qtySpan);

        // Store label in CHEAPEST_MIX mode (each item may come from a different store)
        if (currentStoreMode == StoreMode.CHEAPEST_MIX && item.recommendedStoreId() != null) {
            String storeLabel = resolveStoreName(item.recommendedStoreId(), list);
            var storeSpan = new Span(storeLabel);
            storeSpan.addClassName("mise-shopping-item-store");
            info.add(storeSpan);
        }

        // "Saves €X at Y" amber strip (BR-06, ONE_STORE mode) — design system §"Save-elsewhere hint"
        if (currentStoreMode == StoreMode.ONE_STORE && item.cheapestAlternative() != null) {
            var alt = item.cheapestAlternative();
            if (item.recommendedPrice() != null) {
                BigDecimal saving = item.recommendedPrice().subtract(alt.price());
                if (saving.compareTo(BigDecimal.ZERO) > 0) {
                    var savingsStrip = new Div();
                    savingsStrip.addClassName("mise-shopping-item-savings-strip");
                    savingsStrip.getElement().setAttribute("data-testid", "savings-strip");

                    var tagIcon = VaadinIcon.TAG.create();
                    tagIcon.addClassName("mise-shopping-item-savings-icon");

                    var savingsText = new Span(
                            "saves €" + String.format("%.2f", saving) + " at " + alt.storeName());
                    savingsText.addClassName("mise-shopping-item-savings-text");

                    savingsStrip.add(tagIcon, savingsText);
                    info.add(savingsStrip);
                }
            }
        }

        row.add(info);

        // Price display — show whenever a price is set (priceItems always picks
        // the cheapest available across stores, so a non-null price is always
        // meaningful regardless of the One-Store / Cheapest-Mix toggle).
        if (item.recommendedPrice() != null) {
            var priceSpan = new Span("€" + item.recommendedPrice().toPlainString());
            priceSpan.addClassName("mise-shopping-item-price");
            row.add(priceSpan);
        }

        // "Already have" button (BR-04)
        var alreadyHaveBtn = new Button("✓ Have");
        alreadyHaveBtn.addClassName("mise-shopping-already-have-btn");
        alreadyHaveBtn.getElement().setAttribute("aria-label", "Mark " + item.ingredientName() + " as already have");
        alreadyHaveBtn.getElement().setAttribute("data-testid", "row-already-have");
        alreadyHaveBtn.addClickListener(e -> handleAlreadyHave(householdId, item));
        row.add(alreadyHaveBtn);

        return row;
    }

    // ── event handlers ────────────────────────────────────────────────────────

    private void onStoreModeChange(Long householdId, StoreMode newMode) {
        if (newMode == currentStoreMode) return;
        // Persist preference, then go through the normal load path so BOTH lists
        // (current + alternative for the toggle's trade-off summary) re-derive and
        // the viewed week (UC-010) stays honored.
        viewPreferenceService.saveSettings(householdId, ViewPreference.View.SHOPPING, "storeMode",
                Map.of("mode", newMode.name()));
        loadAndRender();
    }

    /**
     * Removes a pantry item (un-have): deletes it and moves the ingredient back to the
     * active shopping list by reflowing (BR-04 reverse).
     */
    private void handlePantryRemove(Long householdId, PantryItem item) {
        pantryService.remove(item.getId());

        var n = Notification.show(item.getIngredientName() + " moved back to shopping list", 2000,
                Notification.Position.BOTTOM_CENTER);
        n.addThemeVariants(NotificationVariant.LUMO_CONTRAST);

        // Reflow
        currentList = shoppingService.deriveList(householdId, currentStoreMode);
        buildUI(householdId, currentList);
    }

    /**
     * Marks an ingredient as "already have" (BR-04): creates a PantryItem and reflows.
     */
    private void handleAlreadyHave(Long householdId, ShoppingItem item) {
        var pantryItem = new PantryItem();
        pantryItem.setHouseholdId(householdId);
        pantryItem.setIngredientName(item.ingredientName());
        pantryItem.setStaple(false); // BR-04: not a staple unless explicitly upgraded
        pantryService.save(pantryItem);

        var n = Notification.show(item.ingredientName() + " added to pantry", 2000,
                Notification.Position.BOTTOM_CENTER);
        n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);

        // Reflow
        currentList = shoppingService.deriveList(householdId, currentStoreMode);
        buildUI(householdId, currentList);
    }

    /**
     * Called via UI.access() from the ViewRefreshBroadcaster after an AI turn
     * or after a plan mutation (BR-08). Re-derives the list.
     */
    private void aiRefresh() {
        var hhOpt = householdService.findHousehold();
        if (hhOpt.isEmpty()) return;
        var hh = hhOpt.get();

        // UC-010: respect the viewed plan during AI-triggered refreshes
        if (viewedPlan != null) {
            currentList = shoppingService.deriveListForPlan(hh.getId(), viewedPlan.getId(), currentStoreMode);
        } else {
            currentList = shoppingService.deriveList(hh.getId(), currentStoreMode);
        }
        buildUI(hh.getId(), currentList);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * M-1: Builds the 1-2 sentence comparison narrative for the recommendation panel.
     * Scope: private, inline in this view — no new public service method.
     *
     * Format: "Compared {N} stores — {DefaultStore} covers everything."
     * If Lido detour is WORTH_IT: append "Lido saves €X.XX across {N} items — worth a detour."
     * If NOT_WORTH_IT: append "Lido only saves €X.XX — not worth a second stop."
     * If INSUFFICIENT_DATA: no second sentence.
     */
    private String buildComparisonNarrative(Long householdId, ShoppingList list, String defaultStoreName) {
        try {
            int storeCount = priceCatalog.findAllStores().size();
            String coverage = defaultStoreName.isBlank() ? "Your store" : defaultStoreName;
            String base = "Compared " + storeCount + " store" + (storeCount == 1 ? "" : "s")
                    + " — " + coverage + " covers everything.";

            // Find the first non-default store to evaluate as detour candidate
            String defaultStoreId = list.recommendedStore() != null
                    ? list.recommendedStore().getId() : null;
            String detourStoreId = priceCatalog.findAllStores().stream()
                    .filter(s -> !s.getId().equals(defaultStoreId))
                    .map(com.example.mise.capabilities.pricing.Store::getId)
                    .findFirst()
                    .orElse(null);

            if (detourStoreId == null || householdId == null) return base;

            DetourVerdict verdict = detourEvaluator.evaluate(householdId, detourStoreId);
            String detourName = verdict.storeName();

            return switch (verdict.verdict()) {
                case WORTH_IT -> base + " " + detourName + " saves €"
                        + String.format("%.2f", verdict.totalSavings())
                        + " across " + verdict.itemsWorthSwitching().size()
                        + " item" + (verdict.itemsWorthSwitching().size() == 1 ? "" : "s")
                        + " — worth a detour.";
                case NOT_WORTH_IT -> {
                    if (verdict.totalSavings().compareTo(java.math.BigDecimal.ZERO) > 0) {
                        yield base + " " + detourName + " only saves €"
                                + String.format("%.2f", verdict.totalSavings())
                                + " — not worth a second stop.";
                    }
                    yield base;
                }
                case INSUFFICIENT_DATA -> base;
            };
        } catch (Exception e) {
            // Never break the panel for a narrative failure
            return null;
        }
    }

    private String formatQuantity(double qty) {
        if (qty == Math.floor(qty) && !Double.isInfinite(qty)) {
            return String.valueOf((int) qty);
        }
        return String.format("%.1f", qty);
    }

    private String resolveStoreName(String storeId, ShoppingList list) {
        if (storeId == null) return "";
        if (list.recommendedStore() != null && storeId.equals(list.recommendedStore().getId())) {
            return list.recommendedStore().getName();
        }
        return storeId;
    }
}
