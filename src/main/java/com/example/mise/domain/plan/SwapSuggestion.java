package com.example.mise.domain.plan;

import java.math.BigDecimal;

/**
 * A suggestion to swap a meal's recipe to avoid shopping at a specific store (UC-006).
 *
 * @param mealId             the meal to replace
 * @param currentRecipeRef   the recipe currently assigned to this meal
 * @param suggestedRecipeRef the replacement recipe id from the catalog
 * @param estimatedSavings   approximate savings from avoiding the target store
 * @param reason             human-readable explanation of why this swap helps
 */
public record SwapSuggestion(
        Long mealId,
        String currentRecipeRef,
        String suggestedRecipeRef,
        BigDecimal estimatedSavings,
        String reason
) {}
