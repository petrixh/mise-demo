package com.example.mise.ui.shared;

import java.util.List;
import java.util.Map;

/**
 * Canonical food-category colors and aisle mapping shared across views.
 *
 * <p>Hex values mirror the {@code --mise-category-*} CSS custom properties in
 * {@code styles.css}.  Use these constants wherever a Java-side color is
 * needed (Highcharts {@code SolidColor} overrides, inline bar fills).
 */
public final class CategoryColors {

    private CategoryColors() {}

    /** Canonical display order for the five food categories. */
    public static final List<String> ORDER = List.of(
            "Protein", "Produce", "Pantry", "Dairy", "Other");

    /**
     * Hex fill color per category.  Values match {@code --mise-category-*}
     * in {@code styles.css}.
     */
    public static final Map<String, String> HEX = Map.of(
            "Protein", "#7F77DD",
            "Produce", "#1D9E75",
            "Pantry",  "#D85A30",
            "Dairy",   "#D4537E",
            "Other",   "#B4B2A9"
    );

    /**
     * Maps a recipe ingredient aisle value to one of the five canonical
     * categories.
     */
    public static String aisleToCategory(String aisle) {
        if (aisle == null) return "Other";
        return switch (aisle.toLowerCase()) {
            case "meat", "fish", "seafood", "poultry", "protein" -> "Protein";
            case "produce", "vegetables", "fruit", "veg"         -> "Produce";
            case "dry-goods", "pantry", "canned", "oil",
                 "condiments", "spices", "dry goods",
                 "grains", "pasta", "bakery"                     -> "Pantry";
            case "dairy", "eggs", "cheese", "dairy & eggs"       -> "Dairy";
            default                                              -> "Other";
        };
    }
}
