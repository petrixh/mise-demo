package com.example.mise.domain.shopping;

import com.example.mise.capabilities.pricing.Store;

import java.math.BigDecimal;
import java.util.List;

/**
 * Derived shopping list result. Fully computed from plan + pantry + price catalog.
 * Not persisted (BR-01).
 */
public record ShoppingList(
        List<AisleGroup> aisleGroups,
        PantrySection pantrySection,
        BigDecimal totalCost,
        Store recommendedStore,
        StoreMode storeMode
) {}
