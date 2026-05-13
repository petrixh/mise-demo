package com.example.mise.ai.tools;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI tools exposed while the Plan view is active.
 * Registered on the {@link com.example.mise.ai.HouseholdOrchestrator} from {@link com.example.mise.ui.plan.PlanView}.
 */
@Component
public class PlanTools {

    private static final Logger log = LoggerFactory.getLogger(PlanTools.class);

    private final HouseholdService householdService;
    private final PlanService planService;
    private final RecipeCatalog recipeCatalog;
    private final PriceCatalog priceCatalog;

    public PlanTools(HouseholdService householdService,
                     PlanService planService,
                     RecipeCatalog recipeCatalog,
                     PriceCatalog priceCatalog) {
        this.householdService = householdService;
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.priceCatalog = priceCatalog;
    }

    /**
     * Find the meal for a given day. Accepts "Monday"/"Mon"/"today"/"tomorrow"/ISO date.
     */
    @Tool(description = "Find the meal planned for a given day of the week. Accepts day names (Monday, Mon, Tuesday, etc.), 'today', 'tomorrow', or ISO date strings like 2026-05-13. Returns meal details or a not-found message.")
    public String findMealOnDay(
            @ToolParam(description = "Day reference: day name (Monday), abbreviation (Mon), 'today', 'tomorrow', or ISO date (2026-05-13)") String dayOrDate) {
        var plan = getActivePlan();
        if (plan == null) return "No active plan found.";

        LocalDate target = resolveDate(dayOrDate);
        if (target == null) return "Could not understand the date '" + dayOrDate + "'. Please use a day name or ISO date.";

        var mealOpt = planService.findMealByDate(plan.getId(), target);
        if (mealOpt.isEmpty()) {
            return "No meal planned for " + target.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) + ".";
        }

        var meal = mealOpt.get();
        var recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
        if (recipe == null) {
            return "Meal slot on " + target + " references unknown recipe '" + meal.getRecipeRef() + "'.";
        }

        double kcal = recipe.getMacros() != null ? recipe.getMacros().getKcal() : 0;
        return String.format(
                "Date: %s | Recipe: %s | Prep: %d min | Kcal: %.0f | Est. Cost: €%.2f | Tags: %s | Notes: %s",
                target.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                recipe.getName(),
                recipe.getPrepMinutes(),
                kcal * meal.getServings() / Math.max(1, recipe.getDefaultServings()),
                recipe.getEstimatedCost() != null ? recipe.getEstimatedCost() : 0.0,
                recipe.getCategoryTags() != null ? String.join(", ", recipe.getCategoryTags()) : "none",
                recipe.getNotes() != null ? recipe.getNotes() : "—"
        );
    }

    /**
     * Get weekly stats for the active plan.
     */
    @Tool(description = "Get weekly statistics for the active meal plan: total estimated cost, total prep time in minutes, average kcal per meal, and dietary tag breakdown.")
    public String getWeeklyStats() {
        var plan = getActivePlan();
        if (plan == null) return "No active plan found.";

        var meals = planService.findMeals(plan.getId());
        if (meals.isEmpty()) return "Active plan has no meals.";

        double totalCost = 0;
        int totalPrep = 0;
        long totalKcal = 0;
        int mealCount = 0;
        var tagCounts = new java.util.TreeMap<String, Integer>();

        for (var meal : meals) {
            var recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
            if (recipe == null) continue;
            mealCount++;
            if (recipe.getEstimatedCost() != null) totalCost += recipe.getEstimatedCost();
            totalPrep += recipe.getPrepMinutes();
            if (recipe.getMacros() != null) {
                totalKcal += (long) recipe.getMacros().getKcal() * meal.getServings()
                        / Math.max(1, recipe.getDefaultServings());
            }
            if (recipe.getCategoryTags() != null) {
                for (var tag : recipe.getCategoryTags()) {
                    tagCounts.merge(tag, 1, Integer::sum);
                }
            }
        }

        double avgKcal = mealCount > 0 ? (double) totalKcal / mealCount : 0;
        return String.format(
                "Weekly stats: Total cost €%.2f | Total prep %d min | Avg kcal/meal %.0f | Meals: %d/7 | Tags: %s",
                totalCost, totalPrep, avgKcal, mealCount, tagCounts
        );
    }

    /**
     * Explain why a meal on a given day costs what it does.
     */
    @Tool(description = "Explain the cost breakdown for a meal on a given day. Returns each ingredient, quantity, unit, and price from the PriceCatalog so the assistant can answer questions like 'Why is Thursday's curry so expensive?'")
    public String explainMealCost(
            @ToolParam(description = "Day reference: day name (Monday), abbreviation (Mon), 'today', 'tomorrow', or ISO date (2026-05-13)") String dayOrDate) {
        var plan = getActivePlan();
        if (plan == null) return "No active plan found.";

        LocalDate target = resolveDate(dayOrDate);
        if (target == null) return "Could not understand the date '" + dayOrDate + "'.";

        var mealOpt = planService.findMealByDate(plan.getId(), target);
        if (mealOpt.isEmpty()) {
            return "No meal planned for " + target + ".";
        }

        var meal = mealOpt.get();
        var recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
        if (recipe == null) return "Recipe '" + meal.getRecipeRef() + "' not found in catalog.";

        var lines = new ArrayList<String>();
        lines.add("Cost breakdown for " + recipe.getName() + " on " + target.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) + ":");

        double runningTotal = 0;
        if (recipe.getIngredients() != null) {
            for (var ing : recipe.getIngredients()) {
                var priceOpt = priceCatalog.findPrice(ing.getName());
                String priceStr = priceOpt.map(p -> String.format("€%.2f", p)).orElse("not priced");
                if (priceOpt.isPresent()) runningTotal += priceOpt.get();
                lines.add(String.format("  %s: %.1f %s — %s%s",
                        ing.getName(), ing.getQuantity(), ing.getUnit(),
                        priceStr, ing.isOptional() ? " (optional)" : ""));
            }
        }
        lines.add(String.format("Estimated total: €%.2f (recipe's listed cost: €%.2f)",
                runningTotal,
                recipe.getEstimatedCost() != null ? recipe.getEstimatedCost() : 0.0));

        return String.join("\n", lines);
    }

    // ─────────────────────── helpers ────────────────────────────────────────

    private com.example.mise.domain.plan.Plan getActivePlan() {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return null;
            return planService.findActivePlan(hh.getId()).orElse(null);
        } catch (Exception e) {
            log.warn("Error finding active plan: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolves a day-name / "today" / "tomorrow" / ISO-date string to a LocalDate
     * within the current week's Mon–Sun window.
     */
    LocalDate resolveDate(String input) {
        if (input == null || input.isBlank()) return null;
        String lower = input.trim().toLowerCase();

        LocalDate today = LocalDate.now();

        if ("today".equals(lower)) return today;
        if ("tomorrow".equals(lower)) return today.plusDays(1);
        if ("yesterday".equals(lower)) return today.minusDays(1);

        // ISO date
        try {
            return LocalDate.parse(input.trim());
        } catch (Exception ignored) {}

        // Day name (full or abbreviated, English)
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var dayOffsets = new java.util.HashMap<String, Integer>();
        dayOffsets.put("monday", 0);   dayOffsets.put("mon", 0);
        dayOffsets.put("tuesday", 1);  dayOffsets.put("tue", 1);   dayOffsets.put("tues", 1);
        dayOffsets.put("wednesday", 2); dayOffsets.put("wed", 2);
        dayOffsets.put("thursday", 3); dayOffsets.put("thu", 3);   dayOffsets.put("thur", 3); dayOffsets.put("thurs", 3);
        dayOffsets.put("friday", 4);   dayOffsets.put("fri", 4);
        dayOffsets.put("saturday", 5); dayOffsets.put("sat", 5);
        dayOffsets.put("sunday", 6);   dayOffsets.put("sun", 6);

        Integer offset = dayOffsets.get(lower);
        if (offset != null) return monday.plusDays(offset);

        return null;
    }
}
