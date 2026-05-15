package com.example.mise.capabilities.recipes;

import java.util.List;

/**
 * Recipe seed-data DTO. Loaded from YAML at startup; not persisted to H2.
 * The {@code id} is the filename stem (e.g. {@code salmon-pasta}).
 */
public class Recipe {

    private String id;
    private String name;
    private String cuisine;
    private List<String> categoryTags;
    private int prepMinutes;
    private int defaultServings;
    private List<RecipeIngredient> ingredients;
    private RecipeMacros macros;
    private Double estimatedCost;
    private String notes;

    public Recipe() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCuisine() { return cuisine; }
    public void setCuisine(String cuisine) { this.cuisine = cuisine; }

    public List<String> getCategoryTags() { return categoryTags; }
    public void setCategoryTags(List<String> categoryTags) { this.categoryTags = categoryTags; }

    public int getPrepMinutes() { return prepMinutes; }
    public void setPrepMinutes(int prepMinutes) { this.prepMinutes = prepMinutes; }

    public int getDefaultServings() { return defaultServings; }
    public void setDefaultServings(int defaultServings) { this.defaultServings = defaultServings; }

    public List<RecipeIngredient> getIngredients() { return ingredients; }
    public void setIngredients(List<RecipeIngredient> ingredients) { this.ingredients = ingredients; }

    public RecipeMacros getMacros() { return macros; }
    public void setMacros(RecipeMacros macros) { this.macros = macros; }

    public Double getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(Double estimatedCost) { this.estimatedCost = estimatedCost; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    /**
     * Returns true if any non-optional ingredient name contains a substring
     * matching the given allergen (case-insensitive).
     */
    public boolean containsAllergen(String allergen) {
        if (ingredients == null) return false;
        String lower = allergen.toLowerCase();
        return ingredients.stream()
                .filter(i -> !i.isOptional())
                .anyMatch(i -> i.getName().toLowerCase().contains(lower));
    }

    /**
     * Returns true if any ingredient name contains a substring matching
     * any of the given terms (case-insensitive).
     */
    public boolean containsAnyOf(List<String> terms) {
        if (ingredients == null || terms == null) return false;
        return terms.stream().anyMatch(this::containsAllergen);
    }
}
