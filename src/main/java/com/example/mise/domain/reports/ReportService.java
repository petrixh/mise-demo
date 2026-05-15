package com.example.mise.domain.reports;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeIngredient;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-only aggregations over plan history for the Reports view (UC-007).
 *
 * <p>BR-01: queries only {@link Plan.Status#ACTIVE} and {@link Plan.Status#HISTORICAL} plans.</p>
 */
@Service
public class ReportService {

    /**
     * Maps raw ingredient aisle values (from seed YAML files) to the design-system
     * canonical five-category labels: Protein, Produce, Pantry, Dairy, Other.
     *
     * <p>Seed YAML values observed: meat, fish, produce, dairy, dry-goods, beverages, frozen.</p>
     */
    static final Map<String, String> AISLE_TO_CATEGORY = Map.of(
            "meat",      "Protein",
            "fish",      "Protein",
            "seafood",   "Protein",
            "poultry",   "Protein",
            "produce",   "Produce",
            "dairy",     "Dairy",
            "dry-goods", "Pantry",
            "pantry",    "Pantry",
            "beverages", "Pantry",
            "frozen",    "Pantry"
    );

    /** Canonical display order for the five aisle categories. */
    static final List<String> CANONICAL_ORDER = List.of("Protein", "Produce", "Pantry", "Dairy", "Other");

    private final PlanService planService;
    private final RecipeCatalog recipeCatalog;
    private final MealCostCalculator mealCostCalculator;
    private final PriceCatalog priceCatalog;

    public ReportService(PlanService planService,
                         RecipeCatalog recipeCatalog,
                         MealCostCalculator mealCostCalculator,
                         PriceCatalog priceCatalog) {
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.mealCostCalculator = mealCostCalculator;
        this.priceCatalog = priceCatalog;
    }

    /**
     * Returns the weekly cost trend for the household.
     * Points are ordered oldest-first (ascending weekStartDate).
     * Only ACTIVE and HISTORICAL plans are included (BR-01).
     */
    @Transactional(readOnly = true)
    public WeeklyCostTrend computeCostTrend(Long householdId) {
        List<Plan> plans = planService.findAllPlans(householdId);

        // Filter to ACTIVE|HISTORICAL only, then sort oldest-first
        List<WeeklyCostPoint> points = plans.stream()
                .filter(p -> p.getStatus() == Plan.Status.ACTIVE
                          || p.getStatus() == Plan.Status.HISTORICAL)
                .sorted(Comparator.comparing(Plan::getWeekStartDate))
                .map(plan -> {
                    List<Meal> meals = planService.findMeals(plan.getId());
                    BigDecimal totalCost = meals.stream()
                            .map(mealCostCalculator::costFor)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new WeeklyCostPoint(plan.getWeekStartDate(), totalCost);
                })
                .toList();

        return new WeeklyCostTrend(points);
    }

    /**
     * Returns per-category cost breakdown for a single week, aggregated by ingredient
     * aisle (design-system canonical: Protein / Produce / Pantry / Dairy / Other).
     *
     * <p>Each ingredient's cost is computed as
     * {@code quantity × priceCatalog.findPrice(name).orElse(0.0)}, then bucketed
     * into the ingredient's aisle.  Aisle labels are normalised via
     * {@link #AISLE_TO_CATEGORY}; unknown aisles fall to "Other".
     * Results are ordered by {@link #CANONICAL_ORDER} and only non-zero categories
     * are included.</p>
     *
     * <p>If {@code weekStartDate} is null, defaults to the most recent ACTIVE or
     * HISTORICAL plan.</p>
     */
    @Transactional(readOnly = true)
    public CategoryBreakdown computeCategoryBreakdown(Long householdId, LocalDate weekStartDate) {
        List<Plan> plans = planService.findAllPlans(householdId);

        // Select target plan
        Plan targetPlan;
        if (weekStartDate == null) {
            // Default: most recent active plan, then most recent historical
            targetPlan = plans.stream()
                    .filter(p -> p.getStatus() == Plan.Status.ACTIVE
                              || p.getStatus() == Plan.Status.HISTORICAL)
                    .min(Comparator.comparing(Plan::getWeekStartDate).reversed())
                    .orElse(null);
        } else {
            targetPlan = plans.stream()
                    .filter(p -> weekStartDate.equals(p.getWeekStartDate()))
                    .filter(p -> p.getStatus() == Plan.Status.ACTIVE
                              || p.getStatus() == Plan.Status.HISTORICAL)
                    .findFirst()
                    .orElse(null);
        }

        if (targetPlan == null) {
            return new CategoryBreakdown(weekStartDate != null ? weekStartDate : LocalDate.now(), List.of());
        }

        List<Meal> meals = planService.findMeals(targetPlan.getId());

        // Aggregate costs by design-system aisle category (ingredient-aisle, not recipe tag)
        Map<String, BigDecimal> byCat = new LinkedHashMap<>();
        for (String canonical : CANONICAL_ORDER) {
            byCat.put(canonical, BigDecimal.ZERO);
        }

        for (Meal meal : meals) {
            Recipe recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
            if (recipe == null || recipe.getIngredients() == null) continue;

            // Serving scale factor (same logic as LiveMealCostCalculator)
            int mealServings     = meal.getServings() > 0 ? meal.getServings() : 1;
            int defaultServings  = recipe.getDefaultServings() > 0 ? recipe.getDefaultServings() : 1;
            double servingScale  = (double) mealServings / defaultServings;

            for (RecipeIngredient ing : recipe.getIngredients()) {
                if (ing.isOptional()) continue;

                double price = priceCatalog.findPrice(ing.getName()).orElse(0.0);
                // price is per default store unit; quantity is the recipe quantity.
                // We use raw quantity × price as a proportional cost signal — same
                // granularity as LiveMealCostCalculator's per-ingredient computation.
                double ingredientCost = ing.getQuantity() * price * servingScale;

                String canonical = normaliseAisle(ing.getAisle());
                byCat.merge(canonical, BigDecimal.valueOf(ingredientCost), BigDecimal::add);
            }
        }

        // Build ordered entries, dropping zero-cost categories so the chart isn't cluttered
        List<CategoryCostEntry> entries = CANONICAL_ORDER.stream()
                .map(cat -> new CategoryCostEntry(cat, byCat.getOrDefault(cat, BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP)))
                .filter(e -> e.totalCost().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        return new CategoryBreakdown(targetPlan.getWeekStartDate(), entries);
    }

    /**
     * Returns the average weekly total cost across the most recent {@code weeks} completed plans
     * (ACTIVE and HISTORICAL), excluding the current/most-recent plan.
     * Returns {@link BigDecimal#ZERO} when there is insufficient history.
     */
    @Transactional(readOnly = true)
    public BigDecimal weeklyAverage(Long householdId, int weeks) {
        List<Plan> allPlans = planService.findAllPlans(householdId).stream()
                .filter(p -> p.getStatus() == Plan.Status.ACTIVE
                          || p.getStatus() == Plan.Status.HISTORICAL)
                .sorted(Comparator.comparing(Plan::getWeekStartDate).reversed())
                .toList();

        // Skip the most-recent plan (current week) — average is of past weeks only
        List<Plan> pastPlans = allPlans.size() > 1
                ? allPlans.subList(1, Math.min(1 + weeks, allPlans.size()))
                : List.of();

        if (pastPlans.isEmpty()) return BigDecimal.ZERO;

        BigDecimal total = BigDecimal.ZERO;
        for (Plan plan : pastPlans) {
            List<Meal> meals = planService.findMeals(plan.getId());
            BigDecimal weekCost = meals.stream()
                    .map(mealCostCalculator::costFor)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            total = total.add(weekCost);
        }
        return total.divide(BigDecimal.valueOf(pastPlans.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the leaderboard: recipes ranked by frequency of appearance across all
     * ACTIVE and HISTORICAL plans.  When {@code includeKcalPerEuro} is true, each
     * entry's extras map contains {@code "kcalPerEuro"}.
     */
    @Transactional(readOnly = true)
    public List<LeaderboardEntry> computeLeaderboard(Long householdId, boolean includeKcalPerEuro) {
        List<Plan> plans = planService.findAllPlans(householdId).stream()
                .filter(p -> p.getStatus() == Plan.Status.ACTIVE
                          || p.getStatus() == Plan.Status.HISTORICAL)
                .toList();

        // Accumulate per-recipe: frequency, running cost total, running kcal total
        Map<String, int[]> freqMap      = new LinkedHashMap<>(); // [0]=count
        Map<String, BigDecimal> costMap  = new LinkedHashMap<>();
        Map<String, Double> kcalMap      = new LinkedHashMap<>();

        for (Plan plan : plans) {
            List<Meal> meals = planService.findMeals(plan.getId());
            for (Meal meal : meals) {
                String ref = meal.getRecipeRef();
                freqMap.computeIfAbsent(ref, k -> new int[]{0})[0]++;
                BigDecimal cost = mealCostCalculator.costFor(meal);
                costMap.merge(ref, cost, BigDecimal::add);
                double kcal = recipeKcal(meal);
                kcalMap.merge(ref, kcal, Double::sum);
            }
        }

        // Sort by frequency desc, then alphabetically by recipe name
        List<String> sorted = freqMap.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> e.getValue()[0])
                        .reversed()
                        .thenComparing(e -> recipeName(e.getKey())))
                .map(Map.Entry::getKey)
                .toList();

        List<LeaderboardEntry> entries = new ArrayList<>();
        int rank = 1;
        for (String ref : sorted) {
            int freq = freqMap.get(ref)[0];
            BigDecimal totalCost = costMap.getOrDefault(ref, BigDecimal.ZERO);
            BigDecimal avgCost = freq > 0
                    ? totalCost.divide(BigDecimal.valueOf(freq), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            double totalKcal = kcalMap.getOrDefault(ref, 0.0);
            double avgKcal = freq > 0 ? totalKcal / freq : 0.0;

            Map<String, Object> extras = new LinkedHashMap<>();
            if (includeKcalPerEuro) {
                double kcalPerEuro = avgCost.compareTo(BigDecimal.ZERO) > 0
                        ? avgKcal / avgCost.doubleValue()
                        : 0.0;
                extras.put("kcalPerEuro", Math.round(kcalPerEuro * 10.0) / 10.0);
            }

            entries.add(new LeaderboardEntry(
                    rank++,
                    recipeName(ref),
                    ref,
                    freq,
                    avgCost,
                    Math.round(avgKcal * 10.0) / 10.0,
                    extras));
        }
        return entries;
    }

    /**
     * Returns per-week cost details for a specific week, useful for "why cheaper?" analysis.
     * Includes cheapest meals, absent categories vs prior week average, and individual meal costs.
     */
    @Transactional(readOnly = true)
    public String buildWeekVsAverageAnalysis(Long householdId, LocalDate weekStartDate) {
        List<Plan> plans = planService.findAllPlans(householdId).stream()
                .filter(p -> p.getStatus() == Plan.Status.ACTIVE
                          || p.getStatus() == Plan.Status.HISTORICAL)
                .sorted(Comparator.comparing(Plan::getWeekStartDate))
                .toList();

        if (plans.isEmpty()) return "No plan history found.";

        // Find target week's plan
        Plan targetPlan = plans.stream()
                .filter(p -> weekStartDate.equals(p.getWeekStartDate()))
                .findFirst()
                .orElse(null);

        if (targetPlan == null) {
            // Default to the second-most-recent plan (i.e., "last week" relative to now)
            if (plans.size() >= 2) {
                targetPlan = plans.get(plans.size() - 2);
            } else {
                targetPlan = plans.get(plans.size() - 1);
            }
        }

        List<Meal> targetMeals = planService.findMeals(targetPlan.getId());
        BigDecimal targetTotal = targetMeals.stream()
                .map(mealCostCalculator::costFor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Compute overall average cost across all plans (for comparison)
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Plan p : plans) {
            for (Meal m : planService.findMeals(p.getId())) {
                grandTotal = grandTotal.add(mealCostCalculator.costFor(m));
            }
        }
        BigDecimal avgWeekCost = plans.size() > 0
                ? grandTotal.divide(BigDecimal.valueOf(plans.size()), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Categorise target week
        Map<String, BigDecimal> catCosts = new LinkedHashMap<>();
        List<String> mealLines = new ArrayList<>();
        for (Meal meal : targetMeals) {
            BigDecimal cost = mealCostCalculator.costFor(meal);
            String name = recipeName(meal.getRecipeRef());
            String cat  = primaryCategory(meal.getRecipeRef());
            catCosts.merge(cat, cost, BigDecimal::add);
            mealLines.add(String.format("  %s (%s): €%.2f", name, cat, cost.doubleValue()));
        }

        // Find cheapest meals (below 75% of target week's per-meal average)
        BigDecimal perMealAvg = targetMeals.size() > 0
                ? targetTotal.divide(BigDecimal.valueOf(targetMeals.size()), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal cheapThreshold = perMealAvg.multiply(BigDecimal.valueOf(0.75));
        List<String> cheapMeals = new ArrayList<>();
        for (Meal meal : targetMeals) {
            BigDecimal cost = mealCostCalculator.costFor(meal);
            if (cost.compareTo(cheapThreshold) <= 0) {
                cheapMeals.add(recipeName(meal.getRecipeRef()) + " (€" + cost.setScale(2, RoundingMode.HALF_UP) + ")");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Week of ").append(targetPlan.getWeekStartDate()).append(":\n");
        sb.append("  Total cost: €").append(targetTotal.setScale(2, RoundingMode.HALF_UP))
          .append(" (avg across all weeks: €").append(avgWeekCost).append(")\n");
        sb.append("  Meals:\n");
        mealLines.forEach(l -> sb.append(l).append("\n"));
        sb.append("  Cheapest meals (below 75% of week avg €").append(perMealAvg.setScale(2, RoundingMode.HALF_UP)).append("): ");
        sb.append(cheapMeals.isEmpty() ? "none" : String.join(", ", cheapMeals)).append("\n");
        sb.append("  Categories this week: ").append(catCosts.keySet()).append("\n");
        sb.append("  Note: prices from current catalog only (no historical price snapshots).\n");
        return sb.toString();
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * Normalises a raw ingredient aisle string (e.g. "meat", "dry-goods") to one
     * of the five design-system canonical labels: Protein / Produce / Pantry / Dairy / Other.
     * Null/blank aisle values fall to "Other".
     */
    static String normaliseAisle(String aisle) {
        if (aisle == null || aisle.isBlank()) return "Other";
        String lower = aisle.trim().toLowerCase();
        return AISLE_TO_CATEGORY.getOrDefault(lower, "Other");
    }

    /**
     * @deprecated Category breakdown now aggregates by ingredient aisle via
     *   {@link #normaliseAisle(String)}.  This method is kept only for
     *   {@link #buildWeekVsAverageAnalysis} which still uses recipe-tag grouping
     *   for its human-readable narrative text (not part of the chart).
     */
    @Deprecated
    private String primaryCategory(String recipeRef) {
        return recipeCatalog.findById(recipeRef)
                .map(r -> {
                    var tags = r.getCategoryTags();
                    return (tags != null && !tags.isEmpty()) ? tags.get(0) : "Other";
                })
                .orElse("Other");
    }

    private String recipeName(String recipeRef) {
        return recipeCatalog.findById(recipeRef)
                .map(Recipe::getName)
                .orElse(recipeRef);
    }

    private double recipeKcal(Meal meal) {
        return recipeCatalog.findById(meal.getRecipeRef())
                .map(r -> {
                    if (r.getMacros() == null) return 0.0;
                    int defaultServings = r.getDefaultServings() > 0 ? r.getDefaultServings() : 1;
                    int mealServings   = meal.getServings() > 0 ? meal.getServings() : 1;
                    return (double) r.getMacros().getKcal() * mealServings / defaultServings;
                })
                .orElse(0.0);
    }
}
