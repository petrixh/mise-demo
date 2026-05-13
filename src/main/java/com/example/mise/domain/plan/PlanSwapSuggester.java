package com.example.mise.domain.plan;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.pricing.Store;
import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.household.Household;
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
 * Suggests plan-level meal swaps to avoid a specific store (UC-006 BR-04).
 *
 * <p>For each meal whose recipe contains ingredients that are uniquely cheaper at
 * {@code storeToAvoid}, finds 1-3 alternative recipes from the catalog that:
 * <ul>
 *   <li>pass the household's allergy filter (hard constraint)</li>
 *   <li>do NOT contain the storeToAvoid-favorable ingredients (or contain fewer of them)</li>
 *   <li>share at least one category tag with the current recipe (best-effort)</li>
 * </ul>
 *
 * <p>The model narrates these suggestions; if the user confirms, it calls
 * {@link com.example.mise.ai.tools.PlanTools#swapMealOnDay} — the suggester does NOT auto-apply.
 */
@Service
public class PlanSwapSuggester {

    private static final Logger log = LoggerFactory.getLogger(PlanSwapSuggester.class);

    private final HouseholdService householdService;
    private final PlanService planService;
    private final RecipeCatalog recipeCatalog;
    private final PriceCatalog priceCatalog;

    public PlanSwapSuggester(HouseholdService householdService,
                              PlanService planService,
                              RecipeCatalog recipeCatalog,
                              PriceCatalog priceCatalog) {
        this.householdService = householdService;
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.priceCatalog = priceCatalog;
    }

    /**
     * Returns swap suggestions that would eliminate or reduce the need to visit
     * {@code storeToAvoid}.
     *
     * @param householdId  the household to evaluate
     * @param storeToAvoid the store id the user wants to avoid (e.g. "lidl")
     * @return up to 3 swap suggestions; may be empty if no beneficial swaps exist
     */
    @Transactional(readOnly = true)
    public List<SwapSuggestion> suggestSwapsToAvoidStore(Long householdId, String storeToAvoid) {
        var household = householdService.findHousehold().orElse(null);
        if (household == null) return List.of();

        var planOpt = planService.findActivePlan(householdId);
        if (planOpt.isEmpty()) return List.of();

        var plan = planOpt.get();
        var meals = planService.findMeals(plan.getId());
        if (meals.isEmpty()) return List.of();

        // ── Resolve the store to avoid ────────────────────────────────────────
        Store avoidStore = priceCatalog.findAllStores().stream()
                .filter(s -> s.getId().equalsIgnoreCase(storeToAvoid))
                .findFirst()
                .orElse(null);

        if (avoidStore == null || avoidStore.getCatalog() == null) {
            log.debug("PlanSwapSuggester: store '{}' not found or has no catalog", storeToAvoid);
            return List.of();
        }

        // Build set of ingredient names that are uniquely cheaper at storeToAvoid
        var avoidOnlyIngredients = ingredientsCheaperOnlyAt(avoidStore);

        var suggestions = new ArrayList<SwapSuggestion>();

        for (var meal : meals) {
            if (meal.isPinned()) continue; // respect pins

            var currentRecipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
            if (currentRecipe == null || currentRecipe.getIngredients() == null) continue;

            // Does this meal contain storeToAvoid-only ingredients?
            long avoidIngredientCount = currentRecipe.getIngredients().stream()
                    .filter(i -> !i.isOptional())
                    .filter(i -> avoidOnlyIngredients.contains(i.getName().toLowerCase()))
                    .count();

            if (avoidIngredientCount == 0) continue;

            // Compute savings if we could remove the storeToAvoid-only ingredients
            BigDecimal mealSavings = estimateSavings(currentRecipe, avoidOnlyIngredients, avoidStore);

            // Find a suitable replacement
            Optional<Recipe> candidate = findBestSwap(
                    household, currentRecipe, avoidOnlyIngredients, avoidStore, meal.getRecipeRef());

            if (candidate.isPresent()) {
                String reason = buildReason(currentRecipe, candidate.get(), avoidStore.getName(),
                        avoidIngredientCount, mealSavings);
                suggestions.add(new SwapSuggestion(
                        meal.getId(),
                        meal.getRecipeRef(),
                        candidate.get().getId(),
                        mealSavings,
                        reason));

                if (suggestions.size() >= 3) break; // cap at 3 suggestions
            }
        }

        return List.copyOf(suggestions);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the set of ingredient names (lowercase) that are found in {@code avoidStore}
     * but NOT cheaper at any other store in the catalog.
     * "Uniquely cheaper" = cheaper at avoidStore than at the default store.
     */
    private java.util.Set<String> ingredientsCheaperOnlyAt(Store avoidStore) {
        var defaultStore = priceCatalog.findDefaultStore().orElse(null);
        if (defaultStore == null || defaultStore.getCatalog() == null) return java.util.Set.of();

        var result = new java.util.HashSet<String>();
        for (var item : avoidStore.getCatalog()) {
            String nameLower = item.getIngredientName().toLowerCase();
            // Is this ingredient cheaper at avoidStore than at the default store?
            double avoidPrice = item.getPrice();
            double defaultPrice = findPriceInStore(defaultStore, nameLower).orElse(Double.MAX_VALUE);
            if (avoidPrice < defaultPrice) {
                result.add(nameLower);
            }
        }
        return java.util.Collections.unmodifiableSet(result);
    }

    private Optional<Double> findPriceInStore(Store store, String ingredientName) {
        if (store.getCatalog() == null) return Optional.empty();
        String lower = ingredientName.toLowerCase();
        return store.getCatalog().stream()
                .filter(si -> si.getIngredientName().toLowerCase().contains(lower)
                        || lower.contains(si.getIngredientName().toLowerCase()))
                .findFirst()
                .map(com.example.mise.capabilities.pricing.StoreItem::getPrice);
    }

    private BigDecimal estimateSavings(Recipe currentRecipe,
                                        java.util.Set<String> avoidIngredients,
                                        Store avoidStore) {
        var defaultStore = priceCatalog.findDefaultStore().orElse(null);
        if (defaultStore == null) return BigDecimal.ZERO;

        BigDecimal total = BigDecimal.ZERO;
        for (var ing : currentRecipe.getIngredients()) {
            if (ing.isOptional()) continue;
            String lower = ing.getName().toLowerCase();
            if (!avoidIngredients.contains(lower)) continue;
            double avoidPrice = findPriceInStore(avoidStore, lower).orElse(0.0);
            double defaultPrice = findPriceInStore(defaultStore, lower).orElse(0.0);
            double saving = defaultPrice - avoidPrice;
            if (saving > 0) {
                total = total.add(BigDecimal.valueOf(saving).setScale(2, RoundingMode.HALF_UP));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Finds the best allergy-safe swap candidate from the catalog that:
     * 1. Passes the allergy filter.
     * 2. Does NOT use the avoidStore-only ingredients (or uses fewer than the current recipe).
     * 3. Shares at least one category tag with the current recipe (best-effort).
     */
    private Optional<Recipe> findBestSwap(Household household,
                                           Recipe currentRecipe,
                                           java.util.Set<String> avoidIngredients,
                                           Store avoidStore,
                                           String currentRecipeId) {
        var allergies = household.getAllergies() != null ? household.getAllergies() : List.<String>of();
        var currentTags = currentRecipe.getCategoryTags() != null
                ? currentRecipe.getCategoryTags() : List.<String>of();

        long currentCount = countAvoidIngredients(currentRecipe, avoidIngredients);

        // Tier 1: no avoid ingredients + tag overlap
        // Tier 2: fewer avoid ingredients + tag overlap
        // Tier 3: no avoid ingredients (no tag requirement)
        Recipe bestTier1 = null;
        Recipe bestTier2 = null;
        Recipe bestTier3 = null;

        for (var recipe : recipeCatalog.findAll()) {
            if (recipe.getId().equals(currentRecipeId)) continue;
            // Allergy filter
            if (allergies.stream().anyMatch(recipe::containsAllergen)) continue;

            long avoidCount = countAvoidIngredients(recipe, avoidIngredients);
            boolean hasTagOverlap = currentTags.stream()
                    .anyMatch(t -> recipe.getCategoryTags() != null && recipe.getCategoryTags().contains(t));

            if (avoidCount == 0 && hasTagOverlap && bestTier1 == null) {
                bestTier1 = recipe;
            } else if (avoidCount < currentCount && hasTagOverlap && bestTier2 == null) {
                bestTier2 = recipe;
            } else if (avoidCount == 0 && bestTier3 == null) {
                bestTier3 = recipe;
            }

            // Stop once we have a tier-1 result
            if (bestTier1 != null) break;
        }

        if (bestTier1 != null) return Optional.of(bestTier1);
        if (bestTier2 != null) return Optional.of(bestTier2);
        if (bestTier3 != null) return Optional.of(bestTier3);
        return Optional.empty();
    }

    private long countAvoidIngredients(Recipe recipe, java.util.Set<String> avoidIngredients) {
        if (recipe.getIngredients() == null) return 0;
        return recipe.getIngredients().stream()
                .filter(i -> !i.isOptional())
                .filter(i -> avoidIngredients.contains(i.getName().toLowerCase()))
                .count();
    }

    private String buildReason(Recipe current, Recipe suggested, String avoidStoreName,
                                 long avoidIngredientCount, BigDecimal savings) {
        return String.format(
                "Swap %s → %s: removes %d %s-only ingredient(s), saving approx. €%.2f.",
                current.getName(), suggested.getName(),
                avoidIngredientCount, avoidStoreName, savings.doubleValue());
    }
}
