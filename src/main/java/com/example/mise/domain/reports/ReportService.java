package com.example.mise.domain.reports;

import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
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

    private final PlanService planService;
    private final RecipeCatalog recipeCatalog;
    private final MealCostCalculator mealCostCalculator;

    public ReportService(PlanService planService,
                         RecipeCatalog recipeCatalog,
                         MealCostCalculator mealCostCalculator) {
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.mealCostCalculator = mealCostCalculator;
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
     * Returns per-category cost breakdown for a single week.
     * If {@code weekStartDate} is null, defaults to the most recent ACTIVE plan.
     * Category = first tag from {@link Recipe#getCategoryTags()}, falling back to "Other".
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
        Map<String, BigDecimal> byCat = new LinkedHashMap<>();

        for (Meal meal : meals) {
            BigDecimal cost = mealCostCalculator.costFor(meal);
            String category = primaryCategory(meal.getRecipeRef());
            byCat.merge(category, cost, BigDecimal::add);
        }

        List<CategoryCostEntry> entries = byCat.entrySet().stream()
                .map(e -> new CategoryCostEntry(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP)))
                .sorted(Comparator.comparing(CategoryCostEntry::totalCost).reversed())
                .toList();

        return new CategoryBreakdown(targetPlan.getWeekStartDate(), entries);
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
