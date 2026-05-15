package com.example.mise.capabilities.recipes;

import java.util.List;
import java.util.Optional;

/**
 * Read-only catalog of seed recipes loaded from YAML at startup.
 * Implementations must be thread-safe; all methods return unmodifiable views.
 */
public interface RecipeCatalog {

    /** Returns all recipes in the catalog. */
    List<Recipe> findAll();

    /** Finds a recipe by its id (filename stem). */
    Optional<Recipe> findById(String id);
}
