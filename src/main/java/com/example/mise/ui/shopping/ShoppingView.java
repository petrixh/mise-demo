package com.example.mise.ui.shopping;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceService;
import com.example.mise.domain.shopping.*;
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
import java.util.stream.Collectors;

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
    private final ShoppingRefreshBroadcaster refreshBroadcaster;
    private final DetourEvaluator detourEvaluator;
    private final PriceCatalog priceCatalog;

    /** Session-local check-off state (BR-07 — not persisted). */
    private final Set<String> checkedItems = new HashSet<>();

    /** Current plan id — used to detect plan changes so check-off state can be cleared (BR-07). */
    private Long lastSeenPlanId;

    /** Current store mode — reflected in the UI toggle. */
    private StoreMode currentStoreMode = StoreMode.ONE_STORE;

    /** Held as a field so we can deregister the exact same lambda on detach. */
    private Runnable refreshHook;

    /** Currently derived list (null before first load). */
    private ShoppingList currentList;

    public ShoppingView(HouseholdService householdService,
                        ShoppingService shoppingService,
                        PantryService pantryService,
                        ViewPreferenceService viewPreferenceService,
                        ShoppingRefreshBroadcaster refreshBroadcaster,
                        DetourEvaluator detourEvaluator,
                        PriceCatalog priceCatalog) {
        this.householdService = householdService;
        this.shoppingService = shoppingService;
        this.pantryService = pantryService;
        this.viewPreferenceService = viewPreferenceService;
        this.refreshBroadcaster = refreshBroadcaster;
        this.detourEvaluator = detourEvaluator;
        this.priceCatalog = priceCatalog;

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

        currentList = shoppingService.deriveList(hh.getId(), currentStoreMode);
        buildUI(hh.getId(), currentList);
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

    /** Builds the store-mode segmented control. Used in both header strip (mobile) and recommendation panel (desktop). */
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

        var oneStoreBtn = new Button("One store");
        oneStoreBtn.getElement().setAttribute("data-testid", "store-mode-one");
        oneStoreBtn.addClassName("mise-shopping-mode-btn");
        if (currentStoreMode == StoreMode.ONE_STORE) oneStoreBtn.addClassName("active");
        oneStoreBtn.addClickListener(e -> onStoreModeChange(householdId, StoreMode.ONE_STORE));

        var cheapestMixBtn = new Button("Cheapest mix");
        cheapestMixBtn.getElement().setAttribute("data-testid", "store-mode-mix");
        cheapestMixBtn.addClassName("mise-shopping-mode-btn");
        if (currentStoreMode == StoreMode.CHEAPEST_MIX) cheapestMixBtn.addClassName("active");
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
     * Shows: recommended store headline, total cost, cost-by-aisle breakdown, mode toggle.
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

        // Total cost — shown in summary row (always visible on mobile even when collapsed)
        BigDecimal total = list.totalCost() != null ? list.totalCost() : BigDecimal.ZERO;
        var totalValue = new Span();
        totalValue.addClassName("mise-shopping-rec-total-value");
        totalValue.getElement().setAttribute("data-testid", "shopping-total-cost");
        totalValue.setText("€" + String.format("%.2f", total));

        // Chevron toggle button — visible on mobile, hidden on desktop
        var chevron = new Button(VaadinIcon.CHEVRON_DOWN.create());
        chevron.addClassName("mise-shopping-rec-chevron");
        chevron.getElement().setAttribute("aria-label", "Expand store details");
        chevron.getElement().setAttribute("data-testid", "rec-panel-toggle");

        // Summary header — always visible; contains label, store name, total, chevron
        var header = new Div();
        header.addClassName("mise-shopping-rec-header");

        var headerLeft = new Div();
        headerLeft.addClassName("mise-shopping-rec-header-left");
        headerLeft.add(label, storeHeadline);

        var headerRight = new Div();
        headerRight.addClassName("mise-shopping-rec-header-right");
        headerRight.add(totalValue, chevron);

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

        // Total cost row (full label+value row in expanded body)
        var totalRow = new Div();
        totalRow.addClassName("mise-shopping-rec-total-row");
        var totalLabel = new Span("Total");
        totalLabel.addClassName("mise-shopping-rec-total-label");
        var totalValueBody = new Span("€" + String.format("%.2f", total));
        totalValueBody.addClassName("mise-shopping-rec-total-value");
        totalRow.add(totalLabel, totalValueBody);
        body.add(totalRow);

        // Cost-by-aisle breakdown
        if (!list.aisleGroups().isEmpty()) {
            var breakdownDiv = new Div();
            breakdownDiv.addClassName("mise-shopping-rec-breakdown");

            // Compute cost per aisle
            Map<String, BigDecimal> costByAisle = list.aisleGroups().stream()
                    .collect(Collectors.toMap(
                            AisleGroup::aisle,
                            g -> g.items().stream()
                                    .map(item -> item.recommendedPrice() != null
                                            ? item.recommendedPrice() : BigDecimal.ZERO)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    ));

            // Render in aisle order (same order as the list)
            for (var group : list.aisleGroups()) {
                BigDecimal aisleTotal = costByAisle.getOrDefault(group.aisle(), BigDecimal.ZERO);
                if (aisleTotal.compareTo(BigDecimal.ZERO) == 0) continue;

                var row = new Div();
                row.addClassName("mise-shopping-rec-breakdown-row");
                row.getElement().setAttribute("data-testid", "cost-by-category-row");

                String aisleKey = group.aisle().toLowerCase();
                var rowLabel = new Span(group.aisle());
                rowLabel.addClassName("mise-shopping-rec-breakdown-label");
                // Apply category color class based on aisle name
                if (aisleKey.contains("produce") || aisleKey.contains("fruit") || aisleKey.contains("veg")) {
                    rowLabel.addClassName("mise-shopping-cat-produce");
                } else if (aisleKey.contains("protein") || aisleKey.contains("meat") || aisleKey.contains("fish") || aisleKey.contains("seafood")) {
                    rowLabel.addClassName("mise-shopping-cat-protein");
                } else if (aisleKey.contains("dairy") || aisleKey.contains("egg")) {
                    rowLabel.addClassName("mise-shopping-cat-dairy");
                } else if (aisleKey.contains("pantry") || aisleKey.contains("dry") || aisleKey.contains("canned") || aisleKey.contains("oil")) {
                    rowLabel.addClassName("mise-shopping-cat-pantry");
                } else {
                    rowLabel.addClassName("mise-shopping-cat-other");
                }

                var rowValue = new Span("€" + String.format("%.2f", aisleTotal));
                rowValue.addClassName("mise-shopping-rec-breakdown-value");

                row.add(rowLabel, rowValue);
                breakdownDiv.add(row);
            }
            body.add(breakdownDiv);
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

        // Price display
        if (item.recommendedPrice() != null && item.recommendedPrice().compareTo(BigDecimal.ZERO) > 0) {
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
        currentStoreMode = newMode;
        // Persist preference
        viewPreferenceService.saveSettings(householdId, ViewPreference.View.SHOPPING, "storeMode",
                Map.of("mode", newMode.name()));
        // Re-derive and rebuild
        currentList = shoppingService.deriveList(householdId, currentStoreMode);
        buildUI(householdId, currentList);
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
     * Called via UI.access() from the ShoppingRefreshBroadcaster after an AI turn
     * or after a plan mutation (BR-08). Re-derives the list.
     */
    private void aiRefresh() {
        var hhOpt = householdService.findHousehold();
        if (hhOpt.isEmpty()) return;
        var hh = hhOpt.get();

        currentList = shoppingService.deriveList(hh.getId(), currentStoreMode);
        buildUI(hh.getId(), currentList);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * M-1: Builds the 1-2 sentence comparison narrative for the recommendation panel.
     * Scope: private, inline in this view — no new public service method.
     *
     * Format: "Compared {N} stores — {DefaultStore} covers everything."
     * If Lidl detour is WORTH_IT: append "Lidl saves €X.XX across {N} items — worth a detour."
     * If NOT_WORTH_IT: append "Lidl only saves €X.XX — not worth a second stop."
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
