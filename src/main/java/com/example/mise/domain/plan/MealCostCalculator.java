package com.example.mise.domain.plan;

import java.math.BigDecimal;

/**
 * Computes the live cost of a meal using the current PriceCatalog,
 * rather than the static {@code recipe.estimatedCost} field.
 *
 * <p>Implementations must be thread-safe (Spring singleton).</p>
 */
public interface MealCostCalculator {

    /**
     * Returns the live cost for {@code meal} (scaled to the meal's serving count).
     * Returns {@link BigDecimal#ZERO} if the recipe is not found or no ingredients matched.
     */
    BigDecimal costFor(Meal meal);
}
