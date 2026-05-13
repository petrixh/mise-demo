package com.example.mise.domain.plan;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.pricing.Store;
import com.example.mise.capabilities.pricing.StoreItem;
import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeIngredient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Live cost calculator: sums ingredient prices from the PriceCatalog
 * instead of reading the static {@code recipe.estimatedCost}.
 *
 * <p><b>Unit handling:</b> the calculator normalises recipe and store quantities
 * to grams/millilitres where the units differ, covering the most common cases
 * in the seed data:</p>
 * <ul>
 *   <li>recipe {@code g} ↔ store {@code kg}  — factor 1000</li>
 *   <li>recipe {@code g} ↔ store {@code 100g} — factor 100</li>
 *   <li>recipe {@code ml} ↔ store {@code liter} — factor 1000</li>
 *   <li>recipe {@code ml} ↔ store {@code 200ml}, {@code 250ml}, {@code 500ml} — parsed from prefix</li>
 *   <li>recipe {@code tsp} / {@code tbsp} → ml (1 tsp = 5 ml, 1 tbsp = 15 ml)</li>
 *   <li>recipe {@code cloves} / {@code clove} ↔ store {@code head} (1 head = 10 cloves)</li>
 *   <li>recipe {@code piece} ↔ store {@code dozen} (1 dozen = 12 pieces)</li>
 *   <li>Identical units (piece, bunch, head, can…) — no conversion, straight multiply</li>
 * </ul>
 * <p>If units are incompatible (e.g. kg vs piece) the ingredient is skipped and
 * a DEBUG log is emitted. Optional ingredients are always skipped.</p>
 */
@Component
public class LiveMealCostCalculator implements MealCostCalculator {

    private static final Logger log = LoggerFactory.getLogger(LiveMealCostCalculator.class);

    /**
     * Approximate grams per one piece for produce sold by piece in recipes but
     * priced by kg in the store.  Keys are lower-case ingredient names (or
     * substrings that must match exactly after lower-casing).
     */
    static final Map<String, Double> GRAMS_PER_PIECE = Map.of(
            "onion",   120.0,   // medium onion ≈ 120 g
            "carrot",   80.0,   // medium carrot ≈ 80 g
            "potato",  150.0,   // medium potato ≈ 150 g
            "parsnip", 100.0,   // medium parsnip ≈ 100 g
            "apple",   150.0    // medium apple ≈ 150 g
    );

    /**
     * Approximate grams for produce sold by head/bunch in the store but used
     * by weight in recipes (e.g. broccoli 300 g vs store "head").
     */
    static final Map<String, Double> GRAMS_PER_HEAD = Map.of(
            "broccoli", 400.0   // a broccoli head ≈ 400 g
    );

    private final RecipeCatalog recipeCatalog;
    private final PriceCatalog priceCatalog;

    public LiveMealCostCalculator(RecipeCatalog recipeCatalog, PriceCatalog priceCatalog) {
        this.recipeCatalog = recipeCatalog;
        this.priceCatalog = priceCatalog;
    }

    @Override
    public BigDecimal costFor(Meal meal) {
        if (meal == null) return BigDecimal.ZERO;

        Optional<Recipe> recipeOpt = recipeCatalog.findById(meal.getRecipeRef());
        if (recipeOpt.isEmpty()) {
            log.debug("costFor: recipe not found for ref={}", meal.getRecipeRef());
            return BigDecimal.ZERO;
        }

        Recipe recipe = recipeOpt.get();
        if (recipe.getIngredients() == null || recipe.getIngredients().isEmpty()) {
            return BigDecimal.ZERO;
        }

        // Serving scale factor: actual servings vs recipe default
        int mealServings = meal.getServings() > 0 ? meal.getServings() : 1;
        int defaultServings = recipe.getDefaultServings() > 0 ? recipe.getDefaultServings() : 1;
        double servingScale = (double) mealServings / defaultServings;

        // Find the default store once (avoids repeated lookup)
        Optional<Store> defaultStoreOpt = priceCatalog.findDefaultStore();

        double total = 0.0;
        for (RecipeIngredient ing : recipe.getIngredients()) {
            if (ing.isOptional()) continue;

            // Find matching store item
            StoreItem item = findStoreItem(defaultStoreOpt, ing.getName());
            if (item == null) {
                log.debug("costFor: no price found for ingredient '{}' in recipe '{}'",
                        ing.getName(), recipe.getId());
                continue;
            }

            double unitCost = computeIngredientCost(ing, item);
            if (unitCost < 0) {
                // incompatible units — skip
                log.debug("costFor: incompatible units recipe='{}' store='{}' for ingredient '{}' in recipe '{}'",
                        ing.getUnit(), item.getUnit(), ing.getName(), recipe.getId());
                continue;
            }

            total += unitCost;
        }

        // Scale to actual servings
        total *= servingScale;

        return BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private StoreItem findStoreItem(Optional<Store> defaultStoreOpt, String ingredientName) {
        return defaultStoreOpt
                .map(store -> {
                    if (store.getCatalog() == null) return null;
                    String lower = ingredientName.toLowerCase();
                    return store.getCatalog().stream()
                            .filter(item -> item.getIngredientName().toLowerCase().contains(lower)
                                    || lower.contains(item.getIngredientName().toLowerCase()))
                            .findFirst()
                            .orElse(null);
                })
                .orElse(null);
    }

    /**
     * Computes the cost contribution of one ingredient line given the matching store item.
     *
     * @return cost in euros, or -1 if units are incompatible.
     */
    double computeIngredientCost(RecipeIngredient ing, StoreItem item) {
        String recipeUnit = normalise(ing.getUnit());
        String storeUnit  = normalise(item.getUnit());
        double recipeQty  = ing.getQuantity();
        double storePrice = item.getPrice();   // price per storeUnit

        // ── Weight (g / kg / 100g) ─────────────────────────────────────────
        double recipeGrams  = toGrams(recipeUnit, recipeQty);
        double storeBasisG  = toGrams(storeUnit, 1.0);

        if (recipeGrams >= 0 && storeBasisG > 0) {
            // Both are weight units
            return storePrice * (recipeGrams / storeBasisG);
        }

        // ── Volume (ml / l / 200ml / 250ml / 500ml / liter / can) ─────────
        double recipeMl   = toMillilitres(recipeUnit, recipeQty);
        double storeBasisMl = toMillilitres(storeUnit, 1.0);

        if (recipeMl >= 0 && storeBasisMl > 0) {
            return storePrice * (recipeMl / storeBasisMl);
        }

        // ── Produce piece ↔ weight (kg/100g/…): approximate typical weights ─
        // When recipe says "2 piece onion" and store prices by kg, convert via
        // a known-weight table.  Missing entries fall through to the -1 return.
        if (recipeUnit.equals("piece") && storeBasisG > 0) {
            Double gramsPerPiece = GRAMS_PER_PIECE.get(ing.getName().toLowerCase());
            if (gramsPerPiece != null) {
                double recipeGrams2 = recipeQty * gramsPerPiece;
                return storePrice * (recipeGrams2 / storeBasisG);
            }
        }

        // ── Weight in grams ↔ store "head" for specific produce ─────────────
        // e.g. broccoli 300g recipe vs broccoli sold by head in store
        if (recipeGrams >= 0 && storeUnit.equals("head")) {
            Double gramsPerHead = GRAMS_PER_HEAD.get(ing.getName().toLowerCase());
            if (gramsPerHead != null && gramsPerHead > 0) {
                return storePrice * (recipeGrams / gramsPerHead);
            }
        }

        // ── Garlic: recipe "cloves"/"clove" ↔ store "head" (1 head = 10 cloves) ──
        if ((recipeUnit.equals("cloves") || recipeUnit.equals("clove")) && storeUnit.equals("head")) {
            return storePrice * (recipeQty / 10.0);
        }
        if (recipeUnit.equals("head") && (storeUnit.equals("cloves") || storeUnit.equals("clove"))) {
            return storePrice * (recipeQty * 10.0);
        }

        // ── Eggs: recipe "piece" ↔ store "dozen" ──────────────────────────
        if (recipeUnit.equals("piece") && storeUnit.equals("dozen")) {
            return storePrice * (recipeQty / 12.0);
        }
        if (recipeUnit.equals("dozen") && storeUnit.equals("piece")) {
            return storePrice * (recipeQty * 12.0);
        }

        // ── Same discrete unit (piece, head, bunch, can…) ─────────────────
        if (recipeUnit.equals(storeUnit)) {
            return storePrice * recipeQty;
        }

        return -1;
    }

    /** Returns grams for a weight unit × qty, or -1 if not a weight unit. */
    private static double toGrams(String unit, double qty) {
        return switch (unit) {
            case "g"    -> qty;
            case "kg"   -> qty * 1000.0;
            case "100g" -> qty * 100.0;
            case "250g" -> qty * 250.0;
            default     -> {
                // parse patterns like "200g", "500g"
                if (unit.endsWith("g") && unit.length() > 1) {
                    try {
                        double factor = Double.parseDouble(unit.substring(0, unit.length() - 1));
                        yield qty * factor;
                    } catch (NumberFormatException ignored) {}
                }
                yield -1;
            }
        };
    }

    /** Returns millilitres for a volume unit × qty, or -1 if not a volume unit. */
    private static double toMillilitres(String unit, double qty) {
        return switch (unit) {
            case "ml"    -> qty;
            case "l", "liter", "litre" -> qty * 1000.0;
            case "can"   -> qty * 400.0;  // typical can = 400 ml
            case "tsp"   -> qty * 5.0;    // 1 teaspoon = 5 ml
            case "tbsp"  -> qty * 15.0;   // 1 tablespoon = 15 ml
            default      -> {
                // parse patterns like "200ml", "500ml"
                if (unit.endsWith("ml") && unit.length() > 2) {
                    try {
                        double factor = Double.parseDouble(unit.substring(0, unit.length() - 2));
                        yield qty * factor;
                    } catch (NumberFormatException ignored) {}
                }
                yield -1;
            }
        };
    }

    /** Lower-case and trim a unit string; treat null as empty string. */
    private static String normalise(String unit) {
        if (unit == null) return "";
        return unit.trim().toLowerCase();
    }
}
