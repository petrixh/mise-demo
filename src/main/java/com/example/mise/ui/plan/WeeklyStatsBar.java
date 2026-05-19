package com.example.mise.ui.plan;

import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.ui.shared.KpiCard;
import com.vaadin.flow.component.html.Div;

import java.math.BigDecimal;
import java.util.List;

/**
 * KPI strip: four cards showing Weekly cost, Avg/meal, Total prep, Avg kcal.
 * UC-002 BR-03: stats computed from currently-shown meals + their recipes.
 *
 * <p>Weekly Cost and Avg/meal use the live {@link MealCostCalculator} (backed by
 * PriceCatalog) so that editing a {@code stores/*.yaml} price and restarting the
 * app immediately changes the KPI values.</p>
 */
public class WeeklyStatsBar extends Div {

    public WeeklyStatsBar(Plan plan,
                          PlanService planService,
                          RecipeCatalog recipeCatalog,
                          MealCostCalculator costCalculator) {
        addClassName("mise-kpi-strip");
        getElement().setAttribute("data-testid", "kpi-strip");

        List<Meal> meals = planService.findMeals(plan.getId());

        double totalCost = 0;
        int totalPrep = 0;
        long totalKcal = 0;
        int mealCount = 0;

        for (var meal : meals) {
            var recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
            if (recipe == null) continue;
            mealCount++;

            // Live cost from PriceCatalog; fall back to YAML estimatedCost only when 0
            BigDecimal liveCost = costCalculator.costFor(meal);
            if (liveCost.compareTo(BigDecimal.ZERO) > 0) {
                totalCost += liveCost.doubleValue();
            } else if (recipe.getEstimatedCost() != null) {
                totalCost += recipe.getEstimatedCost();
            }

            totalPrep += recipe.getPrepMinutes();
            if (recipe.getMacros() != null) {
                int servings = meal.getServings() > 0 ? meal.getServings() : 1;
                int defaultServings = recipe.getDefaultServings() > 0 ? recipe.getDefaultServings() : 1;
                totalKcal += (long) recipe.getMacros().getKcal() * servings / defaultServings;
            }
        }

        double avgMeal = mealCount > 0 ? totalCost / mealCount : 0;
        double avgKcal = mealCount > 0 ? (double) totalKcal / mealCount : 0;
        int prepHours = totalPrep / 60;
        int prepMins = totalPrep % 60;
        String prepStr = prepHours > 0
                ? prepHours + "h " + (prepMins > 0 ? prepMins + "m" : "")
                : prepMins + "m";

        add(new KpiCard("WEEKLY COST", String.format("€%.2f", totalCost), false, "kpi-card-weekly-cost"));
        add(new KpiCard("AVG / MEAL",  String.format("€%.2f", avgMeal),   false, "kpi-card-avg-meal"));
        add(new KpiCard("TOTAL PREP",  prepStr.trim(),                     false, "kpi-card-total-prep"));
        add(new KpiCard("AVG KCAL",    String.format("%.0f", avgKcal),     false, "kpi-card-avg-kcal"));
    }
}
