package com.example.mise.domain.insights;

import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.domain.reports.ReportService;
import com.example.mise.domain.reports.WeeklyCostPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * UC-009: Business logic for insight generation, dismissal, and muting.
 *
 * <p>Insights are advisory (BR-01) and grounded in concrete plan/meal history (BR-03).
 * At most one undismissed insight is visible at a time (BR-02).
 * Muted households still accumulate insights for browsing later (BR-05).
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    private final InsightRepository insightRepository;
    private final HouseholdService householdService;
    private final PlanService planService;
    private final ReportService reportService;
    private final RecipeCatalog recipeCatalog;

    public InsightService(InsightRepository insightRepository,
                          HouseholdService householdService,
                          PlanService planService,
                          ReportService reportService,
                          RecipeCatalog recipeCatalog) {
        this.insightRepository = insightRepository;
        this.householdService = householdService;
        this.planService = planService;
        this.reportService = reportService;
        this.recipeCatalog = recipeCatalog;
    }

    /**
     * Returns the next undismissed insight for the household, unless insights are muted (BR-05).
     * Returns empty when muted or no undismissed insights exist.
     */
    @Transactional(readOnly = true)
    public Optional<Insight> currentInsight(Long householdId) {
        Household hh = householdService.findHousehold().orElse(null);
        if (hh == null || hh.isInsightsMuted()) {
            return Optional.empty();
        }
        return insightRepository.findFirstByHouseholdIdAndDismissedFalseOrderByCreatedAtAsc(householdId);
    }

    /**
     * Returns all insights for the household, newest first.
     * Independent of muted state — used for "show me insights I missed" (BR-05).
     */
    @Transactional(readOnly = true)
    public List<Insight> allInsights(Long householdId) {
        return insightRepository.findByHouseholdIdOrderByCreatedAtDesc(householdId);
    }

    /**
     * Marks an insight as dismissed. Does not delete the row (BR-07).
     */
    @Transactional
    public Insight dismiss(Long insightId) {
        Insight insight = insightRepository.findById(insightId)
                .orElseThrow(() -> new IllegalArgumentException("Insight not found: " + insightId));
        insight.setDismissed(true);
        insight.setDismissedAt(Instant.now());
        return insightRepository.save(insight);
    }

    /**
     * Updates {@code Household.insightsMuted}.
     */
    @Transactional
    public void mute(Long householdId, boolean muted) {
        Household hh = householdService.findHousehold().orElse(null);
        if (hh == null) return;
        hh.setInsightsMuted(muted);
        householdService.save(hh);
    }

    /**
     * Updates {@code Household.insightFrequency}.
     */
    @Transactional
    public void setFrequency(Long householdId, Household.InsightFrequency frequency) {
        Household hh = householdService.findHousehold().orElse(null);
        if (hh == null) return;
        hh.setInsightFrequency(frequency);
        householdService.save(hh);
    }

    /**
     * Generates an insight grounded in plan/meal history and persists it (BR-03).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Compute average weekly cost via {@link ReportService#computeCostTrend}.
     *   <li>If any historical week's cost is ≥15% below average, produce a vegetarian insight.
     *   <li>Otherwise fall back to "most-cooked dish" insight.
     *   <li>If history is empty, return empty (no evidence = no insight).
     * </ol>
     *
     * @return the persisted {@link Insight}, or empty when history is too sparse
     */
    @Transactional
    public Optional<Insight> generate(Long householdId) {
        List<Plan> allPlans = planService.findAllPlans(householdId).stream()
                .filter(p -> p.getStatus() == Plan.Status.ACTIVE
                          || p.getStatus() == Plan.Status.HISTORICAL)
                .toList();

        if (allPlans.isEmpty()) {
            log.debug("generate: no plans for household {} — skipping (BR-03)", householdId);
            return Optional.empty();
        }

        // ── Compute average weekly cost ────────────────────────────────────────
        var trend = reportService.computeCostTrend(householdId);
        var points = trend.points();

        if (points.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal total = points.stream()
                .map(WeeklyCostPoint::totalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = total.divide(BigDecimal.valueOf(points.size()), 2, RoundingMode.HALF_UP);

        if (avg.compareTo(BigDecimal.ZERO) == 0) {
            // No pricing data — fall back to most-cooked
            return generateMostCookedInsight(householdId, allPlans);
        }

        // ── Look for a week that is ≥15% cheaper than average ─────────────────
        BigDecimal threshold = avg.multiply(BigDecimal.valueOf(0.85));
        Optional<WeeklyCostPoint> cheapestOpt = points.stream()
                .filter(p -> p.totalCost().compareTo(threshold) < 0)
                .min(Comparator.comparing(WeeklyCostPoint::totalCost));

        if (cheapestOpt.isPresent()) {
            WeeklyCostPoint cheapWeek = cheapestOpt.get();
            // Find the plan for this week
            Plan cheapPlan = allPlans.stream()
                    .filter(p -> p.getWeekStartDate().equals(cheapWeek.weekStartDate()))
                    .findFirst()
                    .orElse(null);
            if (cheapPlan != null) {
                return generateVegetarianInsight(householdId, cheapPlan);
            }
        }

        // ── Fall back to most-cooked dish insight ──────────────────────────────
        return generateMostCookedInsight(householdId, allPlans);
    }

    /**
     * Returns true when the household should receive a new startup insight.
     *
     * <p>Triggers when any of the following is true (BR-04 a):
     * <ol>
     *   <li>No insight has ever been generated.
     *   <li>The last generated insight is older than 7 days.
     *   <li>All existing insights are dismissed — the user has cleared the queue and
     *       would see nothing on the next app start without a new one (per-insight
     *       dismissal: dismissing one insight should not mute banners for 7 days).
     * </ol>
     */
    @Transactional(readOnly = true)
    public boolean shouldTriggerStartup(Long householdId) {
        Optional<Insight> last = insightRepository.findFirstByHouseholdIdOrderByCreatedAtDesc(householdId);
        if (last.isEmpty()) return true;
        // If the most-recent insight is older than 7 days, always generate a fresh one
        if (last.get().getCreatedAt().isBefore(Instant.now().minus(7, ChronoUnit.DAYS))) return true;
        // If all insights are dismissed (queue is empty), generate a new one so the
        // next app start shows a banner — dismissal is per-insight, not a 7-day snooze.
        Optional<Insight> undismissed =
                insightRepository.findFirstByHouseholdIdAndDismissedFalseOrderByCreatedAtAsc(householdId);
        return undismissed.isEmpty();
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private Optional<Insight> generateVegetarianInsight(Long householdId, Plan cheapPlan) {
        List<Meal> meals = planService.findMeals(cheapPlan.getId());
        List<Meal> vegMeals = meals.stream()
                .filter(m -> isVegetarian(m.getRecipeRef()))
                .toList();

        int vegCount = vegMeals.size();
        if (vegCount == 0) {
            // No vegetarian meals found — fall through to most-cooked
            return generateMostCookedInsight(householdId,
                    planService.findAllPlans(householdId).stream()
                            .filter(p -> p.getStatus() == Plan.Status.ACTIVE
                                      || p.getStatus() == Plan.Status.HISTORICAL)
                            .toList());
        }

        String body = "Your cheaper weeks tend to have " + vegCount
                + " vegetarian dinner" + (vegCount == 1 ? "" : "s")
                + " — worth locking that in?";

        List<Long> vegMealIds = vegMeals.stream().map(Meal::getId).toList();
        String evidenceRefs = buildEvidenceRefs(List.of(cheapPlan.getId()), vegMealIds);

        Insight insight = new Insight();
        insight.setHouseholdId(householdId);
        insight.setBody(body);
        insight.setEvidenceRefs(evidenceRefs);
        return Optional.of(insightRepository.save(insight));
    }

    private Optional<Insight> generateMostCookedInsight(Long householdId, List<Plan> plans) {
        // Count recipe appearances
        Map<String, Long> recipeCounts = new LinkedHashMap<>();
        Map<String, List<Long>> recipeMealIds = new LinkedHashMap<>();

        for (Plan plan : plans) {
            List<Meal> meals = planService.findMeals(plan.getId());
            for (Meal meal : meals) {
                String ref = meal.getRecipeRef();
                recipeCounts.merge(ref, 1L, Long::sum);
                recipeMealIds.computeIfAbsent(ref, k -> new ArrayList<>()).add(meal.getId());
            }
        }

        if (recipeCounts.isEmpty()) return Optional.empty();

        // Find most-cooked
        String topRef = recipeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (topRef == null) return Optional.empty();

        long times = recipeCounts.get(topRef);
        String recipeName = recipeCatalog.findById(topRef)
                .map(Recipe::getName)
                .orElse(topRef);

        String body = "Most-cooked dish: " + recipeName + " (" + times
                + " time" + (times == 1 ? "" : "s") + "). Want to swap something else in?";

        List<Long> mealIds = recipeMealIds.getOrDefault(topRef, List.of());
        // Collect distinct plan IDs that contain this recipe
        List<Long> planIds = plans.stream()
                .filter(p -> planService.findMeals(p.getId()).stream()
                        .anyMatch(m -> topRef.equals(m.getRecipeRef())))
                .map(Plan::getId)
                .toList();

        String evidenceRefs = buildEvidenceRefs(planIds, mealIds);

        Insight insight = new Insight();
        insight.setHouseholdId(householdId);
        insight.setBody(body);
        insight.setEvidenceRefs(evidenceRefs);
        return Optional.of(insightRepository.save(insight));
    }

    private boolean isVegetarian(String recipeRef) {
        return recipeCatalog.findById(recipeRef)
                .map(r -> {
                    List<String> tags = r.getCategoryTags();
                    if (tags == null) return false;
                    return tags.stream().anyMatch(t ->
                            t.equalsIgnoreCase("vegetarian") || t.equalsIgnoreCase("vegan"));
                })
                .orElse(false);
    }

    /**
     * Builds the JSON evidenceRefs string without requiring a full Jackson dependency
     * on the service layer (keep it simple as spec says).
     */
    static String buildEvidenceRefs(List<Long> planIds, List<Long> mealIds) {
        String planArr = planIds.stream().map(Object::toString).collect(Collectors.joining(","));
        String mealArr = mealIds.stream().map(Object::toString).collect(Collectors.joining(","));
        return "{\"planIds\":[" + planArr + "],\"mealIds\":[" + mealArr + "]}";
    }
}
