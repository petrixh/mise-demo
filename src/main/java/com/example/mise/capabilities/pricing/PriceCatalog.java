package com.example.mise.capabilities.pricing;

import java.util.List;
import java.util.Optional;

/**
 * Read-only price catalog backed by store YAML files.
 * Implementations must be thread-safe; all methods return unmodifiable views.
 */
public interface PriceCatalog {

    /** All stores in the catalog. */
    List<Store> findAllStores();

    /** The default store (the one marked {@code defaultStore: true}). */
    Optional<Store> findDefaultStore();

    /**
     * Look up the price for an ingredient by name in the default store.
     * Returns empty if not found.
     */
    Optional<Double> findPrice(String ingredientName);
}
