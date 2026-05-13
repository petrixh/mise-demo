package com.example.mise.domain.shopping;

/** How the shopping list selects stores for pricing. */
public enum StoreMode {
    /** Pick the single store with the lowest total list cost. */
    ONE_STORE,
    /** Pick the cheapest store per-item. Items may be sourced from different stores. */
    CHEAPEST_MIX
}
