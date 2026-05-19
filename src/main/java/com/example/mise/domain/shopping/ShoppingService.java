package com.example.mise.domain.shopping;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.pricing.Store;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.domain.preferences.ViewPreferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Derives the shopping list from the active plan, pantry items, price catalog, and
 * any extra items added via chat. The result is NOT persisted — it is always recalculated
 * (BR-01).
 */
@Service
public class ShoppingService {

    private static final Logger log = LoggerFactory.getLogger(ShoppingService.class);

    /** Minimum per-item price saving (in €) to surface a cheapest-alternative hint (BR-06). */
    private static final double CHEAPEST_ALT_THRESHOLD = 0.50;

    /** Aisle name used for ad-hoc chat-added items whose aisle is unknown. */
    public static final String EXTRAS_AISLE = "Extras";

    /**
     * Display order for known aisles. Unknown aisles sort at the end alphabetically.
     */
    private static final List<String> AISLE_ORDER = List.of(
            "Produce", "Protein", "Dairy", "Pantry", "Dry Goods", "Frozen", "Extras"
    );

    private final PlanService planService;
    private final RecipeCatalog recipeCatalog;
    private final PriceCatalog priceCatalog;
    private final PantryService pantryService;
    private final ViewPreferenceService viewPreferenceService;
    private final ExtraShoppingItemRepository extraShoppingItemRepository;

    public ShoppingService(PlanService planService,
                           RecipeCatalog recipeCatalog,
                           PriceCatalog priceCatalog,
                           PantryService pantryService,
                           ViewPreferenceService viewPreferenceService,
                           ExtraShoppingItemRepository extraShoppingItemRepository) {
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.priceCatalog = priceCatalog;
        this.pantryService = pantryService;
        this.viewPreferenceService = viewPreferenceService;
        this.extraShoppingItemRepository = extraShoppingItemRepository;
    }

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Derives the full shopping list for a household. Pure computation; no DB writes.
     *
     * @param householdId  the household to derive for
     * @param storeMode    override the stored preference; null = read from ViewPreference
     */
    @Transactional(readOnly = true)
    public ShoppingList deriveList(Long householdId, StoreMode storeModeOverride) {
        // ── 1. Resolve store mode ────────────────────────────────────────────
        StoreMode storeMode = resolveStoreMode(householdId, storeModeOverride);

        // ── 2. Collect plan ingredients ──────────────────────────────────────
        // Map: normalised ingredient name → list of contributions (qty, unit, aisle, recipeRef)
        var contributions = collectPlanIngredients(householdId);

        // ── 3. Consolidate per BR-02 ─────────────────────────────────────────
        var consolidated = consolidate(contributions);

        // ── 4. Subtract pantry items ─────────────────────────────────────────
        var pantryItems = pantryService.findByHousehold(householdId);
        var subtracted = subtractPantry(consolidated, pantryItems);
        var pantrySection = buildPantrySection(pantryItems, contributions);

        // ── 5. Add extra items from chat ─────────────────────────────────────
        addExtraItems(householdId, subtracted);

        // ── 6. Price items ───────────────────────────────────────────────────
        var allStores = priceCatalog.findAllStores();
        Store recommendedStore = resolveRecommendedStore(subtracted, allStores, storeMode);
        priceItems(subtracted, allStores, recommendedStore, storeMode);

        // ── 7. Group by aisle ────────────────────────────────────────────────
        var aisleGroups = groupByAisle(subtracted);

        // ── 8. Compute total cost ─────────────────────────────────────────────
        BigDecimal totalCost = computeTotalCost(subtracted, recommendedStore, allStores, storeMode);

        return new ShoppingList(aisleGroups, pantrySection, totalCost, recommendedStore, storeMode);
    }

    /**
     * Convenience overload that reads the store mode from the persisted preference.
     */
    @Transactional(readOnly = true)
    public ShoppingList deriveList(Long householdId) {
        return deriveList(householdId, null);
    }

    /**
     * UC-010: Derives the shopping list for a specific plan (viewed week).
     * Falls back to the active-plan logic when planId is null.
     */
    @Transactional(readOnly = true)
    public ShoppingList deriveListForPlan(Long householdId, Long planId, StoreMode storeModeOverride) {
        if (planId == null) return deriveList(householdId, storeModeOverride);

        StoreMode storeMode = resolveStoreMode(householdId, storeModeOverride);
        var contributions = collectPlanIngredientsForPlan(planId);
        var consolidated = consolidate(contributions);
        var pantryItems = pantryService.findByHousehold(householdId);
        var subtracted = subtractPantry(consolidated, pantryItems);
        var pantrySection = buildPantrySection(pantryItems, contributions);
        addExtraItems(householdId, subtracted);
        var allStores = priceCatalog.findAllStores();
        Store recommendedStore = resolveRecommendedStore(subtracted, allStores, storeMode);
        priceItems(subtracted, allStores, recommendedStore, storeMode);
        var aisleGroups = groupByAisle(subtracted);
        BigDecimal totalCost = computeTotalCost(subtracted, recommendedStore, allStores, storeMode);
        return new ShoppingList(aisleGroups, pantrySection, totalCost, recommendedStore, storeMode);
    }

    /**
     * UC-010: Collects plan ingredients for a specific plan id (viewed week).
     */
    public Map<String, List<Contribution>> collectPlanIngredientsForPlan(Long planId) {
        var meals = planService.findMeals(planId);
        Map<String, List<Contribution>> map = new LinkedHashMap<>();
        for (var meal : meals) {
            var recipeOpt = recipeCatalog.findById(meal.getRecipeRef());
            if (recipeOpt.isEmpty()) continue;
            var recipe = recipeOpt.get();
            if (recipe.getIngredients() == null) continue;
            for (var ing : recipe.getIngredients()) {
                String key = ing.getName().toLowerCase().trim();
                map.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Contribution(ing.getName(), ing.getQuantity(), ing.getUnit(),
                                normaliseAisle(ing.getAisle()), recipe.getId()));
            }
        }
        return map;
    }

    // ── internal / package-visible step implementations ───────────────────────

    private StoreMode resolveStoreMode(Long householdId, StoreMode override) {
        if (override != null) return override;
        return viewPreferenceService
                .getSettings(householdId, ViewPreference.View.SHOPPING, "storeMode")
                .map(s -> {
                    Object val = s.get("mode");
                    if ("CHEAPEST_MIX".equals(val)) return StoreMode.CHEAPEST_MIX;
                    return StoreMode.ONE_STORE;
                })
                .orElse(StoreMode.ONE_STORE);
    }

    /**
     * Collects all ingredients from the active plan's meals.
     * Returns a map: normalised name → list of {@link Contribution}.
     * Public so {@link com.example.mise.ai.tools.ShoppingTools} can use it for list-size explanations.
     */
    public Map<String, List<Contribution>> collectPlanIngredients(Long householdId) {
        var planOpt = planService.findActivePlan(householdId);
        if (planOpt.isEmpty()) return Map.of();

        var plan = planOpt.get();
        var meals = planService.findMeals(plan.getId());

        Map<String, List<Contribution>> map = new LinkedHashMap<>();
        for (var meal : meals) {
            var recipeOpt = recipeCatalog.findById(meal.getRecipeRef());
            if (recipeOpt.isEmpty()) continue;
            var recipe = recipeOpt.get();
            if (recipe.getIngredients() == null) continue;
            for (var ing : recipe.getIngredients()) {
                String key = ing.getName().toLowerCase().trim();
                map.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Contribution(ing.getName(), ing.getQuantity(), ing.getUnit(),
                                normaliseAisle(ing.getAisle()), recipe.getId()));
            }
        }
        return map;
    }

    /**
     * Consolidates contributions per BR-02: sum quantities when units match;
     * keep separate rows when units are incompatible.
     * Returns a mutable list of {@link ShoppingItem}.
     */
    List<ShoppingItem> consolidate(Map<String, List<Contribution>> contributions) {
        var result = new ArrayList<ShoppingItem>();
        for (var entry : contributions.entrySet()) {
            var contribs = entry.getValue();
            // Group sub-contributions by normalised unit
            Map<String, List<Contribution>> byUnit = contribs.stream()
                    .collect(Collectors.groupingBy(c -> c.unit().toLowerCase().trim()));

            for (var unitEntry : byUnit.entrySet()) {
                var group = unitEntry.getValue();
                double totalQty = group.stream().mapToDouble(Contribution::quantity).sum();
                String displayName = group.get(0).name(); // canonical capitalisation from first occurrence
                String aisle = group.get(0).aisle(); // aisle from first occurrence
                List<String> recipeRefs = group.stream().map(Contribution::recipeRef).distinct().toList();
                String unit = group.get(0).unit();
                result.add(new ShoppingItem(displayName, totalQty, unit, aisle, recipeRefs));
            }
        }
        return result;
    }

    /**
     * Removes items covered by pantry per BR-03 / BR-04.
     * Staples always subtract; non-staples subtract only when name matches.
     * Returns items NOT fully covered by the pantry.
     */
    List<ShoppingItem> subtractPantry(List<ShoppingItem> items, List<PantryItem> pantry) {
        Set<String> pantryNames = new HashSet<>();
        for (var p : pantry) {
            if (p.isStaple() || p.getIngredientName() != null) {
                pantryNames.add(p.getIngredientName().toLowerCase().trim());
            }
        }
        return items.stream()
                .filter(item -> !pantryNames.contains(item.ingredientName().toLowerCase().trim()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    PantrySection buildPantrySection(List<PantryItem> pantry, Map<String, List<Contribution>> allContributions) {
        var subtracted = new ArrayList<PantryItem>();
        Set<String> planNames = allContributions.keySet();
        for (var p : pantry) {
            String key = p.getIngredientName() == null ? "" : p.getIngredientName().toLowerCase().trim();
            if (p.isStaple() || planNames.contains(key)) {
                subtracted.add(p);
            }
        }
        return new PantrySection(subtracted);
    }

    /** Adds extra chat-added items to the mutable list (modifies in place). */
    void addExtraItems(Long householdId, List<ShoppingItem> items) {
        var extras = extraShoppingItemRepository.findByHouseholdId(householdId);
        for (var extra : extras) {
            // Best-effort aisle: search existing items for matching name fragment
            String aisle = EXTRAS_AISLE;
            for (var existing : items) {
                if (existing.ingredientName().toLowerCase().contains(extra.getIngredientName().toLowerCase())
                        || extra.getIngredientName().toLowerCase().contains(existing.ingredientName().toLowerCase())) {
                    aisle = existing.aisle();
                    break;
                }
            }
            items.add(new ShoppingItem(extra.getIngredientName(), extra.getQuantity(), extra.getUnit(),
                    aisle, List.of()));
        }
    }

    /**
     * In ONE_STORE mode: picks the store with the lowest total list cost.
     * In CHEAPEST_MIX mode: the "recommended store" is the default store (for display only).
     */
    Store resolveRecommendedStore(List<ShoppingItem> items, List<Store> allStores, StoreMode storeMode) {
        if (allStores.isEmpty()) return null;

        Store defaultStore = allStores.stream().filter(Store::isDefaultStore).findFirst()
                .orElse(allStores.get(0));

        if (storeMode == StoreMode.CHEAPEST_MIX) return defaultStore;

        // ONE_STORE: pick the store that covers the most items first, then breaks ties
        // by lowest total cost across the items it covers. The old version summed
        // findPriceInStore(...).orElse(0.0), which let a store with sparse coverage
        // win because its missing items counted as €0 — so a stub store like
        // "Local Market" (6 ingredients) beat the well-stocked Prima (53). The
        // coverage-first scoring matches the design intent: "best single store to
        // shop at" is the one that actually carries the week's list.
        Store best = defaultStore;
        int bestCovered = -1;
        double bestTotal = Double.MAX_VALUE;
        for (var store : allStores) {
            int covered = 0;
            double total = 0;
            for (var item : items) {
                var priceOpt = findPriceInStore(store, item.ingredientName());
                if (priceOpt.isPresent()) {
                    covered++;
                    total += priceOpt.get();
                }
            }
            if (covered > bestCovered || (covered == bestCovered && total < bestTotal)) {
                bestCovered = covered;
                bestTotal = total;
                best = store;
            }
        }
        return best;
    }

    /**
     * Populates price fields on each {@link ShoppingItem} (mutates the list via
     * replacement — ShoppingItem is a record). Pricing is independent of the
     * store-mode toggle: every item gets the cheapest available price across all
     * stores, along with that store's id. The toggle in the UI now only controls
     * whether the per-item store pill is shown, so users always see real numbers
     * regardless of which mode they're in.
     *
     * Items with no price in any store still get a null price — the view hides
     * the price cell for those rows rather than rendering "€0.00".
     *
     * For the ONE_STORE "save elsewhere" hint (BR-06) we additionally record a
     * meaningfully-cheaper alternative *vs the recommended store* so the amber
     * "saves €X at Y" strip can still surface a useful next stop.
     */
    void priceItems(List<ShoppingItem> items, List<Store> allStores, Store recommendedStore, StoreMode storeMode) {
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);

            // ── Canonical pricing: cheapest across all stores that carry it ──
            String cheapestStoreId = null;
            Double cheapestPrice = null;
            for (var store : allStores) {
                var p = findPriceInStore(store, item.ingredientName());
                if (p.isEmpty()) continue;
                if (cheapestPrice == null || p.get() < cheapestPrice) {
                    cheapestPrice = p.get();
                    cheapestStoreId = store.getId();
                }
            }

            BigDecimal price = cheapestPrice == null
                    ? null
                    : BigDecimal.valueOf(cheapestPrice).setScale(2, RoundingMode.HALF_UP);

            // ── ONE_STORE-only save-elsewhere hint: cheaper *vs the recommended
            // store's price*, not vs the global cheapest. ──
            ShoppingItem.CheapestAlternative cheapestAlt = null;
            if (storeMode == StoreMode.ONE_STORE && recommendedStore != null) {
                var recPriceOpt = findPriceInStore(recommendedStore, item.ingredientName());
                if (recPriceOpt.isPresent()) {
                    double recPrice = recPriceOpt.get();
                    String altId = null;
                    double altPrice = Double.MAX_VALUE;
                    for (var store : allStores) {
                        if (store.getId().equals(recommendedStore.getId())) continue;
                        var p = findPriceInStore(store, item.ingredientName());
                        if (p.isPresent() && p.get() < recPrice - CHEAPEST_ALT_THRESHOLD
                                && p.get() < altPrice) {
                            altPrice = p.get();
                            altId = store.getId();
                        }
                    }
                    if (altId != null) {
                        final String fAltId = altId;
                        String altName = allStores.stream()
                                .filter(s -> s.getId().equals(fAltId))
                                .map(Store::getName).findFirst().orElse(fAltId);
                        cheapestAlt = new ShoppingItem.CheapestAlternative(fAltId, altName,
                                BigDecimal.valueOf(altPrice).setScale(2, RoundingMode.HALF_UP));
                    }
                }
            }

            items.set(i, item.withPricing(cheapestStoreId, price, cheapestAlt, storeMode));
        }
    }

    private List<AisleGroup> groupByAisle(List<ShoppingItem> items) {
        // Group by aisle, preserving canonical ordering
        Map<String, List<ShoppingItem>> byAisle = new LinkedHashMap<>();
        for (var item : items) {
            byAisle.computeIfAbsent(item.aisle(), k -> new ArrayList<>()).add(item);
        }

        // Sort aisles by known order
        var sorted = new ArrayList<Map.Entry<String, List<ShoppingItem>>>(byAisle.entrySet());
        sorted.sort(Comparator.comparingInt(e -> {
            int idx = AISLE_ORDER.indexOf(e.getKey());
            return idx == -1 ? Integer.MAX_VALUE : idx;
        }));

        return sorted.stream()
                .map(e -> new AisleGroup(e.getKey(), e.getValue()))
                .toList();
    }

    BigDecimal computeTotalCost(List<ShoppingItem> items, Store recommendedStore, List<Store> allStores, StoreMode storeMode) {
        // Total mirrors what the user sees in the list: sum of each item's
        // displayed price (set by priceItems above — already the cheapest
        // available, or null when no store has it). Skipping nulls is what
        // makes the total non-zero on a partially-priced catalog — the old
        // CHEAPEST_MIX branch took min() across `orElse(0.0)`, so any item
        // missing from any store dragged the cheapest down to €0 and the
        // total to €0.
        BigDecimal total = BigDecimal.ZERO;
        for (var item : items) {
            if (item.recommendedPrice() != null) {
                total = total.add(item.recommendedPrice());
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private Optional<Double> findPriceInStore(Store store, String ingredientName) {
        if (store.getCatalog() == null) return Optional.empty();
        String lower = ingredientName.toLowerCase();
        return store.getCatalog().stream()
                .filter(si -> si.getIngredientName().toLowerCase().contains(lower)
                        || lower.contains(si.getIngredientName().toLowerCase()))
                .findFirst()
                .map(si -> si.getPrice());
    }

    private String normaliseAisle(String rawAisle) {
        if (rawAisle == null || rawAisle.isBlank()) return "Other";
        return switch (rawAisle.toLowerCase().trim()) {
            case "produce"     -> "Produce";
            case "dairy"       -> "Dairy";
            case "dry-goods", "dry goods", "pantry" -> "Pantry";
            case "protein", "meat", "fish", "seafood" -> "Protein";
            case "frozen"      -> "Frozen";
            default            -> capitalise(rawAisle.trim());
        };
    }

    private String capitalise(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    // ── DTOs / value types ────────────────────────────────────────────────────

    /** One ingredient contribution from one recipe. Public for use by ShoppingTools. */
    public record Contribution(String name, double quantity, String unit, String aisle, String recipeRef) {}
}
