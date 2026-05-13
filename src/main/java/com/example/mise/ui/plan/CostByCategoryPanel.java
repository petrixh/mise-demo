package com.example.mise.ui.plan;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cost-by-category panel: horizontal bars with category colors.
 * Categories derived from {@code RecipeIngredient.aisle}.
 *
 * Aisle → category mapping:
 *   meat / fish / seafood / poultry   → Protein  (#7F77DD)
 *   produce / vegetables / fruit      → Produce  (#1D9E75)
 *   dry-goods / pantry / canned / oil → Pantry   (#D85A30)
 *   dairy / eggs / cheese             → Dairy    (#D4537E)
 *   anything else                     → Other    (#B4B2A9)
 */
public class CostByCategoryPanel extends Div {

    private record CategoryStats(double cost) {}

    // Category display order and colors (CSS custom properties)
    private static final List<String> CATEGORY_ORDER = List.of(
            "Protein", "Produce", "Pantry", "Dairy", "Other");

    private static final Map<String, String> CATEGORY_FILL = Map.of(
            "Protein", "var(--mise-category-protein)",
            "Produce", "var(--mise-category-produce)",
            "Pantry",  "var(--mise-category-pantry)",
            "Dairy",   "var(--mise-category-dairy)",
            "Other",   "var(--mise-category-other)"
    );

    public CostByCategoryPanel(Plan plan,
                                PlanService planService,
                                RecipeCatalog recipeCatalog,
                                PriceCatalog priceCatalog) {
        addClassName("mise-category-panel");

        var title = new Paragraph("COST BY CATEGORY");
        title.addClassName("mise-category-panel-title");
        add(title);

        // Accumulate costs per category
        Map<String, Double> costs = new LinkedHashMap<>();
        for (String cat : CATEGORY_ORDER) costs.put(cat, 0.0);

        List<Meal> meals = planService.findMeals(plan.getId());
        for (var meal : meals) {
            var recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
            if (recipe == null || recipe.getIngredients() == null) continue;
            for (var ing : recipe.getIngredients()) {
                if (ing.isOptional()) continue;
                String category = aisleToCategory(ing.getAisle());
                double price = priceCatalog.findPrice(ing.getName()).orElse(0.0);
                costs.merge(category, price, Double::sum);
            }
        }

        double maxCost = costs.values().stream().mapToDouble(d -> d).max().orElse(1.0);
        if (maxCost <= 0) maxCost = 1.0;

        for (String cat : CATEGORY_ORDER) {
            double cost = costs.get(cat);
            if (cost <= 0) continue;  // skip empty categories
            add(buildRow(cat, cost, maxCost));
        }

        // If everything is zero (no price data), show a note
        if (costs.values().stream().allMatch(v -> v <= 0)) {
            var note = new Paragraph("No price data available.");
            note.addClassName("mise-plan-no-data-note");
            add(note);
        }
    }

    private Div buildRow(String category, double cost, double maxCost) {
        var row = new Div();
        row.addClassName("mise-category-row");

        var label = new Span(category);
        label.addClassName("mise-category-label");
        row.add(label);

        var barTrack = new Div();
        barTrack.addClassName("mise-category-bar-track");

        var barFill = new Div();
        barFill.addClassName("mise-category-bar-fill");
        double pct = Math.min(100.0, (cost / maxCost) * 100.0);
        // Inline styles below are runtime-computed per instance:
        //   width % comes from cost / maxCost; background picked from CATEGORY_FILL by category name.
        barFill.getStyle()
                .set("width", String.format("%.1f%%", pct))
                .set("background", CATEGORY_FILL.getOrDefault(category, "var(--mise-category-other)"));
        barTrack.add(barFill);
        row.add(barTrack);

        var amount = new Span(String.format("€%.2f", cost));
        amount.addClassName("mise-category-amount");
        row.add(amount);

        return row;
    }

    /**
     * Maps a recipe ingredient aisle value to one of the five canonical categories.
     */
    static String aisleToCategory(String aisle) {
        if (aisle == null) return "Other";
        String lower = aisle.toLowerCase();
        return switch (lower) {
            case "meat", "fish", "seafood", "poultry", "protein" -> "Protein";
            case "produce", "vegetables", "fruit", "veg" -> "Produce";
            case "dry-goods", "pantry", "canned", "oil", "condiments", "spices",
                 "dry goods", "grains", "pasta", "bakery" -> "Pantry";
            case "dairy", "eggs", "cheese", "dairy & eggs" -> "Dairy";
            default -> "Other";
        };
    }
}
