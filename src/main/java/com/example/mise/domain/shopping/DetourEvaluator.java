package com.example.mise.domain.shopping;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.pricing.Store;
import com.example.mise.capabilities.pricing.StoreItem;
import com.example.mise.domain.household.HouseholdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates whether a detour to a second store is worth it this week (UC-006).
 *
 * <p>The evaluation compares per-item prices between the household's default store
 * (the current recommended store, always 0 detour) and the queried detour store.
 * Items that are cheaper at the detour store are listed with their per-item savings.
 *
 * <p>Verdict heuristic: {@code WORTH_IT} if
 * {@code totalSavings >= detourMinutes * 0.50 EUR/min} — a reasonable
 * household time-value proxy. The model can override or argue around this;
 * the service surfaces the numbers + a default verdict.
 */
@Service
public class DetourEvaluator {

    private static final Logger log = LoggerFactory.getLogger(DetourEvaluator.class);

    /** Time-value heuristic: €0.50 per minute of detour. */
    private static final double EURO_PER_DETOUR_MINUTE = 0.50;

    private final HouseholdService householdService;
    private final ShoppingService shoppingService;
    private final PriceCatalog priceCatalog;

    public DetourEvaluator(HouseholdService householdService,
                           ShoppingService shoppingService,
                           PriceCatalog priceCatalog) {
        this.householdService = householdService;
        this.shoppingService = shoppingService;
        this.priceCatalog = priceCatalog;
    }

    /**
     * Evaluates whether a detour to {@code storeId} is worth it for the current
     * household's active shopping week.
     *
     * @param householdId the household to evaluate for
     * @param storeId     the candidate detour store id (e.g. "lidl")
     * @return a {@link DetourVerdict} with concrete savings numbers and a verdict
     */
    @Transactional(readOnly = true)
    public DetourVerdict evaluate(Long householdId, String storeId) {
        // ── 1. Resolve the queried store ──────────────────────────────────────
        Store queriedStore = priceCatalog.findAllStores().stream()
                .filter(s -> s.getId().equalsIgnoreCase(storeId))
                .findFirst()
                .orElse(null);

        if (queriedStore == null) {
            return new DetourVerdict(storeId, storeId, 0, BigDecimal.ZERO, List.of(),
                    DetourVerdict.Verdict.INSUFFICIENT_DATA,
                    "I don't have data for that store.");
        }

        // ── 2. Resolve the default store (the current recommended store) ──────
        Store defaultStore = priceCatalog.findDefaultStore().orElse(null);
        if (defaultStore == null) {
            return new DetourVerdict(storeId, queriedStore.getName(),
                    queriedStore.getDetourMinutesFromRoute(), BigDecimal.ZERO, List.of(),
                    DetourVerdict.Verdict.INSUFFICIENT_DATA,
                    "No default store is configured.");
        }

        // ── 3. Derive the shopping list to get the items needed this week ─────
        //    We derive it with ONE_STORE mode but then compare prices directly
        //    between the default store and the queried store.
        ShoppingList shoppingList;
        try {
            shoppingList = shoppingService.deriveList(householdId, StoreMode.ONE_STORE);
        } catch (Exception e) {
            log.warn("DetourEvaluator: could not derive shopping list for household {}: {}",
                    householdId, e.getMessage());
            return new DetourVerdict(storeId, queriedStore.getName(),
                    queriedStore.getDetourMinutesFromRoute(), BigDecimal.ZERO, List.of(),
                    DetourVerdict.Verdict.INSUFFICIENT_DATA,
                    "Could not derive shopping list: " + e.getMessage());
        }

        // Collect all items regardless of which store was recommended
        var items = shoppingList.aisleGroups().stream()
                .flatMap(g -> g.items().stream())
                .toList();

        if (items.isEmpty()) {
            return new DetourVerdict(storeId, queriedStore.getName(),
                    queriedStore.getDetourMinutesFromRoute(), BigDecimal.ZERO, List.of(),
                    DetourVerdict.Verdict.NOT_WORTH_IT,
                    "No items on the shopping list this week.");
        }

        // ── 4. Compare default store prices vs detour store prices ────────────
        var worthSwitching = new ArrayList<DetourVerdict.DetourItem>();
        BigDecimal totalSavings = BigDecimal.ZERO;

        for (var item : items) {
            String name = item.ingredientName();
            double defaultPrice = findPriceInStore(defaultStore, name).orElse(-1.0);
            double detourPrice = findPriceInStore(queriedStore, name).orElse(-1.0);

            // Only count if both stores have the item and detour is cheaper
            if (defaultPrice > 0 && detourPrice > 0 && detourPrice < defaultPrice) {
                BigDecimal saving = BigDecimal.valueOf(defaultPrice - detourPrice)
                        .setScale(2, RoundingMode.HALF_UP);
                worthSwitching.add(new DetourVerdict.DetourItem(name, saving));
                totalSavings = totalSavings.add(saving);
            }
        }

        totalSavings = totalSavings.setScale(2, RoundingMode.HALF_UP);
        int detourMinutes = queriedStore.getDetourMinutesFromRoute();

        // ── 5. Handle empty savings ───────────────────────────────────────────
        if (worthSwitching.isEmpty()) {
            return new DetourVerdict(storeId, queriedStore.getName(), detourMinutes,
                    BigDecimal.ZERO, List.of(),
                    DetourVerdict.Verdict.NOT_WORTH_IT,
                    "Nothing in this week's list is cheaper at " + queriedStore.getName() + ".");
        }

        // ── 6. Apply the time-value heuristic ─────────────────────────────────
        double threshold = detourMinutes * EURO_PER_DETOUR_MINUTE;
        DetourVerdict.Verdict verdict = totalSavings.doubleValue() >= threshold
                ? DetourVerdict.Verdict.WORTH_IT
                : DetourVerdict.Verdict.NOT_WORTH_IT;

        String reasoning = buildReasoning(queriedStore.getName(), totalSavings, detourMinutes,
                threshold, worthSwitching, verdict);

        return new DetourVerdict(storeId, queriedStore.getName(), detourMinutes,
                totalSavings, List.copyOf(worthSwitching), verdict, reasoning);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Optional<Double> findPriceInStore(Store store, String ingredientName) {
        if (store.getCatalog() == null) return Optional.empty();
        String lower = ingredientName.toLowerCase();
        return store.getCatalog().stream()
                .filter(si -> si.getIngredientName().toLowerCase().contains(lower)
                        || lower.contains(si.getIngredientName().toLowerCase()))
                .findFirst()
                .map(StoreItem::getPrice);
    }

    private String buildReasoning(String storeName, BigDecimal totalSavings, int detourMinutes,
                                   double threshold, List<DetourVerdict.DetourItem> items,
                                   DetourVerdict.Verdict verdict) {
        String itemNames = items.stream()
                .map(DetourVerdict.DetourItem::ingredientName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");

        if (verdict == DetourVerdict.Verdict.WORTH_IT) {
            return String.format(
                    "%s saves €%.2f across %d item(s) (%s). The detour adds %d min; "
                    + "at €%.2f/min that costs you €%.2f in time. The savings exceed the time cost — worth it.",
                    storeName, totalSavings, items.size(), itemNames,
                    detourMinutes, EURO_PER_DETOUR_MINUTE, threshold);
        } else {
            return String.format(
                    "%s saves €%.2f across %d item(s) (%s). The detour adds %d min; "
                    + "at €%.2f/min that costs you €%.2f in time. Not worth the trip.",
                    storeName, totalSavings, items.size(), itemNames,
                    detourMinutes, EURO_PER_DETOUR_MINUTE, threshold);
        }
    }
}
