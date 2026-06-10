package com.example.mise.domain.reports;

import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregations over plan history feeding the Reports KPI strip (UC-007).
 *
 * <p>The chart and grid widgets no longer read from this service — they are
 * controller-driven over the reporting schema (UC-012, see
 * {@link ReportSnapshotService}). What remains here: the cost trend and weekly
 * average behind the KPI cards, plus the shared aisle→category mapping the
 * snapshot reuses.
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

    private final PlanService planService;
    private final MealCostCalculator mealCostCalculator;

    public ReportService(PlanService planService, MealCostCalculator mealCostCalculator) {
        this.planService = planService;
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

}
