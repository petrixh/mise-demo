package com.example.mise.ai.tools;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.MealSwapRequest;
import com.example.mise.domain.plan.PinnedMealException;
import com.example.mise.domain.plan.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    private final MealCostCalculator mealCostCalculator;

    public PlanTools(HouseholdService householdService,
                     PlanService planService,
                     RecipeCatalog recipeCatalog,
                     PriceCatalog priceCatalog,
                     MealCostCalculator mealCostCalculator) {
        this.householdService = householdService;
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.priceCatalog = priceCatalog;
        this.mealCostCalculator = mealCostCalculator;
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
        // Cost from ingredient-level PriceCatalog (same source as the KPI strip)
        BigDecimal liveCost = mealCostCalculator.costFor(meal);
        return String.format(
                "Date: %s | Recipe: %s | Prep: %d min | Kcal: %.0f | Est. Cost: €%.2f | Tags: %s | Notes: %s",
                target.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                recipe.getName(),
                recipe.getPrepMinutes(),
                kcal * meal.getServings() / Math.max(1, recipe.getDefaultServings()),
                liveCost.doubleValue(),
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

        BigDecimal totalCostBd = BigDecimal.ZERO;
        int totalPrep = 0;
        long totalKcal = 0;
        int mealCount = 0;
        var tagCounts = new java.util.TreeMap<String, Integer>();

        for (var meal : meals) {
            var recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
            if (recipe == null) continue;
            mealCount++;
            // Cost from ingredient-level PriceCatalog (same source as the KPI strip)
            totalCostBd = totalCostBd.add(mealCostCalculator.costFor(meal));
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
                totalCostBd.doubleValue(), totalPrep, avgKcal, mealCount, tagCounts
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

    // ─────────────────────── UC-003 tools ────────────────────────────────────

    /**
     * Returns the household's allergy list, hated foods, weekly budget, and household size.
     * The LLM should call this before proposing any recipe to avoid allergy violations.
     */
    @Tool(description = "Return the household's hard allergy list and soft hate list, weekly budget, and household size. Call before suggesting any recipe.")
    public String getHouseholdConstraints() {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";
            return String.format(
                    "Household size: %d | Weekly budget: %s %s | Allergies (hard): %s | Hated foods (soft): %s",
                    hh.getSize(),
                    hh.getWeeklyBudget() != null ? hh.getWeeklyBudget().toPlainString() : "unset",
                    hh.getCurrency() != null ? hh.getCurrency() : "EUR",
                    hh.getAllergies() != null && !hh.getAllergies().isEmpty()
                            ? String.join(", ", hh.getAllergies()) : "none",
                    hh.getHatedFoods() != null && !hh.getHatedFoods().isEmpty()
                            ? String.join(", ", hh.getHatedFoods()) : "none"
            );
        } catch (Exception e) {
            log.warn("getHouseholdConstraints error: {}", e.getMessage());
            return "Could not load household constraints: " + e.getMessage();
        }
    }

    /**
     * Finds candidate recipes from the catalog matching the given criteria.
     * Allergy-safe results only — use this BEFORE calling swapMealOnDay.
     */
    @Tool(description = "Find candidate recipes from the catalog matching criteria. Use BEFORE swapping to pick an appropriate replacement. Returns up to 10 matches as 'id | name | tags | prepMinutes | cost'.")
    public String findCandidateRecipes(
            @ToolParam(description = "Comma-separated tags to require (e.g. 'vegetarian,kid-friendly'). Empty for any.") String requiredTags,
            @ToolParam(description = "Maximum prep minutes, or 0 for no limit") int maxPrepMinutes,
            @ToolParam(description = "Maximum estimated cost in EUR, or 0 for no limit") double maxCost) {
        try {
            var hh = householdService.findHousehold().orElse(null);
            var allergies = hh != null && hh.getAllergies() != null ? hh.getAllergies() : List.<String>of();

            List<String> required = (requiredTags != null && !requiredTags.isBlank())
                    ? List.of(requiredTags.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList()
                    : List.of();

            var candidates = recipeCatalog.findAll().stream()
                    // Hard filter: allergies
                    .filter(r -> allergies.stream().noneMatch(r::containsAllergen))
                    // Tag filter
                    .filter(r -> required.isEmpty() || (r.getCategoryTags() != null
                            && r.getCategoryTags().containsAll(required)))
                    // Prep filter
                    .filter(r -> maxPrepMinutes <= 0 || r.getPrepMinutes() <= maxPrepMinutes)
                    // Cost filter
                    .filter(r -> maxCost <= 0 || (r.getEstimatedCost() != null && r.getEstimatedCost() <= maxCost))
                    .limit(10)
                    .toList();

            if (candidates.isEmpty()) return "No recipes found matching those criteria (allergy-safe).";

            return candidates.stream()
                    .map(r -> String.format("%s | %s | %s | %d min | €%.2f",
                            r.getId(),
                            r.getName(),
                            r.getCategoryTags() != null ? String.join(", ", r.getCategoryTags()) : "—",
                            r.getPrepMinutes(),
                            r.getEstimatedCost() != null ? r.getEstimatedCost() : 0.0))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("findCandidateRecipes error: {}", e.getMessage());
            return "Error finding candidates: " + e.getMessage();
        }
    }

    /**
     * Replaces the meal on the given day with a different recipe.
     * Validates allergy constraints at both the catalog and data-layer level (belt-and-braces).
     */
    @Tool(description = "Replace the meal on a given day with another recipe from the catalog. Call findCandidateRecipes first if unsure which recipe to use. Respects pinned meals and allergy hard constraints.")
    public String swapMealOnDay(
            @ToolParam(description = "Day reference (Monday/Mon/today/tomorrow/ISO date)") String dayOrDate,
            @ToolParam(description = "Replacement recipe id from the catalog. Use findCandidateRecipes first if unsure.") String newRecipeRef,
            @ToolParam(description = "Short justification for the swap — will be stored on the MealEdit row") String reason,
            @ToolParam(description = "Optional note from the user to attach to the meal (e.g. 'guest preference'). Leave blank if none.") String userNote) {
        var plan = getActivePlan();
        if (plan == null) return "No active plan found.";

        LocalDate target = resolveDate(dayOrDate);
        if (target == null) return "Could not understand the date '" + dayOrDate + "'. Please use a day name or ISO date.";

        var mealOpt = planService.findMealByDate(plan.getId(), target);
        if (mealOpt.isEmpty()) {
            return "No meal on " + target.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) + ".";
        }

        // Validate newRecipeRef exists
        var recipeOpt = recipeCatalog.findById(newRecipeRef);
        if (recipeOpt.isEmpty()) {
            return "Recipe '" + newRecipeRef + "' not found in catalog. Use findCandidateRecipes to list valid ids.";
        }

        // Belt-and-braces: validate allergy filter at tool layer
        var recipe = recipeOpt.get();
        var hh = householdService.findHousehold().orElse(null);
        if (hh != null && hh.getAllergies() != null) {
            for (var allergen : hh.getAllergies()) {
                if (recipe.containsAllergen(allergen)) {
                    return "Cannot use '" + recipe.getName() + "' — it contains '" + allergen
                            + "', which is a hard allergy for this household.";
                }
            }
        }

        try {
            var meal = mealOpt.get();
            // BR-05: store optional user note
            if (userNote != null && !userNote.isBlank()) {
                meal.setNote(userNote.trim());
            }

            planService.swapMeal(meal.getId(), newRecipeRef, reason);

            String dateLabel = target.format(DateTimeFormatter.ofPattern("EEEE d MMMM"));
            return String.format("Done. %s's meal is now %s. Reason stored: %s",
                    dateLabel, recipe.getName(), reason != null ? reason : "—");
        } catch (PinnedMealException e) {
            String dateLabel = target.format(DateTimeFormatter.ofPattern("EEEE"));
            var existing = mealOpt.get();
            var existingRecipe = recipeCatalog.findById(existing.getRecipeRef()).orElse(null);
            String existingName = existingRecipe != null ? existingRecipe.getName() : existing.getRecipeRef();
            return "REFUSED: " + dateLabel + " is pinned (" + existingName
                    + "). The meal was NOT changed. Tell the user " + dateLabel
                    + " is pinned and they need to click the pin icon on the row to unpin it before any swap can happen.";
        } catch (Exception e) {
            log.warn("swapMealOnDay error: {}", e.getMessage());
            return "Could not swap meal: " + e.getMessage();
        }
    }

    /**
     * Atomically applies multiple swaps. Used for multi-meal constraint negotiation.
     */
    @Tool(description = "Atomically apply multiple meal swaps. Use for multi-meal constraint negotiation (e.g., 'get the week under €80'). Format: semicolon-separated 'day=recipeId' pairs. Rolls back entirely if any target meal is pinned.")
    public String negotiateWeekChanges(
            @ToolParam(description = "Semicolon-separated swap directives: 'day=newRecipeRef'. Example: 'Tuesday=lentil-soup;Saturday=baked-cod'") String swaps,
            @ToolParam(description = "Short justification for the negotiation — stored on every MealEdit row") String reason) {
        var plan = getActivePlan();
        if (plan == null) return "No active plan found.";

        if (swaps == null || swaps.isBlank()) return "No swaps specified.";

        var hh = householdService.findHousehold().orElse(null);
        var allergies = hh != null && hh.getAllergies() != null ? hh.getAllergies() : List.<String>of();

        // Parse directives and validate upfront before any DB write.
        // Belt-and-braces: check pin state here so we never start a partial write.
        var requests = new ArrayList<MealSwapRequest>();
        var summaryLines = new ArrayList<String>();

        for (var directive : swaps.split(";")) {
            directive = directive.trim();
            if (directive.isEmpty()) continue;

            var parts = directive.split("=", 2);
            if (parts.length != 2) {
                return "Could not parse directive '" + directive + "'. Expected format: 'day=recipeId'.";
            }

            LocalDate date = resolveDate(parts[0].trim());
            if (date == null) return "Could not understand day '" + parts[0].trim() + "'.";

            String newRef = parts[1].trim();
            var recipeOpt = recipeCatalog.findById(newRef);
            if (recipeOpt.isEmpty()) return "Recipe '" + newRef + "' not found in catalog.";

            var recipe = recipeOpt.get();
            for (var allergen : allergies) {
                if (recipe.containsAllergen(allergen)) {
                    return "Cannot use '" + recipe.getName() + "' — contains allergen '" + allergen + "'.";
                }
            }

            var mealOpt = planService.findMealByDate(plan.getId(), date);
            if (mealOpt.isEmpty()) return "No meal on " + date + ".";

            var meal = mealOpt.get();
            // Pre-flight pin check — prevents any partial DB write
            if (meal.isPinned()) {
                String pinnedDay = date.format(DateTimeFormatter.ofPattern("EEEE"));
                var pinnedRecipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
                String pinnedName = pinnedRecipe != null ? pinnedRecipe.getName() : meal.getRecipeRef();
                return "REFUSED: " + pinnedDay + " is pinned (" + pinnedName
                        + "). The meal was NOT changed. Tell the user " + pinnedDay
                        + " is pinned and they need to click the pin icon on the row to unpin it before any swap can happen.";
            }

            requests.add(new MealSwapRequest(meal.getId(), newRef));
            summaryLines.add(date.format(DateTimeFormatter.ofPattern("EEEE")) + " → " + recipe.getName());
        }

        if (requests.isEmpty()) return "No valid swap directives found.";

        try {
            planService.negotiateWeek(plan.getId(), requests, reason);
            return "Done. Changes applied atomically:\n" + String.join("\n", summaryLines);
        } catch (PinnedMealException e) {
            return "REFUSED: a pinned meal was encountered during negotiation and no changes were applied. "
                    + "The meals were NOT changed. Tell the user they need to click the pin icon on the relevant row "
                    + "to unpin it before any swap can happen.";
        } catch (Exception e) {
            log.warn("negotiateWeekChanges error: {}", e.getMessage());
            return "Could not apply changes (rolled back): " + e.getMessage();
        }
    }

    /**
     * Pins the meal on a given day (AI-driven). Unpinning is intentionally not allowed via chat —
     * the pin is a user-controlled lock that only the user can release via the pin icon in the UI.
     */
    @Tool(description = "Pin the meal on a given day to protect it from AI-driven changes. Pinned meals are excluded from all tool-driven edits. The assistant can only PIN meals via chat — unpinning requires the user to click the pin icon on the meal row.")
    public String setMealPin(
            @ToolParam(description = "Day reference (Monday/Mon/today/tomorrow/ISO date)") String dayOrDate,
            @ToolParam(description = "true to pin the meal; false is not accepted via chat — unpinning requires the user to click the pin icon") boolean pinned) {
        var plan = getActivePlan();
        if (plan == null) return "No active plan found.";

        LocalDate target = resolveDate(dayOrDate);
        if (target == null) return "Could not understand the date '" + dayOrDate + "'.";

        var mealOpt = planService.findMealByDate(plan.getId(), target);
        if (mealOpt.isEmpty()) {
            return "No meal on " + target.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) + ".";
        }

        // BR-03: unpinning via chat is not allowed — the pin is a user-controlled lock.
        if (!pinned) {
            return "REFUSED: unpinning a meal via chat is not allowed. The meal was NOT changed. "
                    + "Tell the user they need to click the pin icon on the meal row to remove the pin — "
                    + "pins are user-controlled to protect meals from unintended AI edits.";
        }

        var meal = mealOpt.get();
        String dateLabel = target.format(DateTimeFormatter.ofPattern("EEEE"));

        // Noop if already pinned
        if (meal.isPinned()) {
            return dateLabel + "'s meal is already pinned — I won't touch it during replanning.";
        }

        try {
            planService.setPinned(meal.getId(), true, Meal.Editor.AI);
            return dateLabel + "'s meal is now pinned — I won't touch it during replanning.";
        } catch (Exception e) {
            log.warn("setMealPin error: {}", e.getMessage());
            return "Could not update pin state: " + e.getMessage();
        }
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
