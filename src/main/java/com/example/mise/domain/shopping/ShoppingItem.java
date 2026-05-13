package com.example.mise.domain.shopping;

import java.math.BigDecimal;
import java.util.List;

/**
 * One line in the derived shopping list.
 * Immutable record; use {@link #withPricing} to attach price data.
 */
public record ShoppingItem(
        String ingredientName,
        double quantity,
        String unit,
        String aisle,
        List<String> usedInRecipes,
        /** Store id for the recommended (or cheapest-mix-per-item) store. */
        String recommendedStoreId,
        BigDecimal recommendedPrice,
        /** Non-null in ONE_STORE mode when a meaningful saving exists at another store. */
        CheapestAlternative cheapestAlternative,
        StoreMode storeMode
) {

    /**
     * Constructor without price data (before pricing step).
     */
    public ShoppingItem(String ingredientName, double quantity, String unit,
                        String aisle, List<String> usedInRecipes) {
        this(ingredientName, quantity, unit, aisle, usedInRecipes,
                null, null, null, StoreMode.ONE_STORE);
    }

    /** Returns a copy with price fields populated. */
    public ShoppingItem withPricing(String storeId, BigDecimal price,
                                    CheapestAlternative cheapestAlternative,
                                    StoreMode mode) {
        return new ShoppingItem(ingredientName, quantity, unit, aisle, usedInRecipes,
                storeId, price, cheapestAlternative, mode);
    }

    /** Optional cheapest-alternative hint shown in ONE_STORE mode (BR-06). */
    public record CheapestAlternative(String storeId, String storeName, BigDecimal price) {}
}
