package com.example.mise.ai.tools;

import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.domain.plan.PlannedWeeksResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * UC-011: future-week generation, exposed as a single tool.
 *
 * <p>Unlike {@link PlanTools} (which the system prompt scopes to the {@code /plan} view), this tool
 * is available from <b>any</b> view (BR-09) — the user can ask "plan next week" while on Shopping or
 * Reports. The model resolves the natural-language range ("the rest of May", "June", "the next four
 * weeks") into ISO Monday dates using today's date (grounded in the system prompt) and calls
 * {@link #planFutureWeeks}. All rule enforcement (forward-only, idempotence, 8-week cap) lives in
 * {@link PlanService#generatePlannedWeeks}; this tool validates the dates, invokes it, and renders a
 * concise, non-fabricated factual summary for the model to paraphrase (BR-08).
 */
@Component
public class PlanningTools {

    private static final Logger log = LoggerFactory.getLogger(PlanningTools.class);
    private static final DateTimeFormatter WEEK_OF = DateTimeFormatter.ofPattern("MMMM d");

    private final HouseholdService householdService;
    private final PlanService planService;
    private final RecipeCatalog recipeCatalog;
    private final MealCostCalculator mealCostCalculator;

    public PlanningTools(HouseholdService householdService,
                         PlanService planService,
                         RecipeCatalog recipeCatalog,
                         MealCostCalculator mealCostCalculator) {
        this.householdService = householdService;
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.mealCostCalculator = mealCostCalculator;
    }

    @Tool(description = "Generate one or more future weekly meal plans, starting from a given Monday. "
            + "Refuses past or current weeks; skips weeks that already exist (idempotent). "
            + "Honors household allergies (hard) and hated foods (soft). Generates at most 8 weeks per call. "
            + "Available from any view — do NOT navigate first. Resolve relative ranges like 'next week', "
            + "'the rest of May', or 'June' into ISO Monday dates using today's date before calling.")
    public String planFutureWeeks(
            @ToolParam(description = "First Monday to plan (ISO date, e.g. 2026-05-25). Must be after the current ACTIVE plan's Monday.") String fromMonday,
            @ToolParam(description = "Inclusive last Monday to plan (ISO date). For a single-week request, equal to fromMonday.") String throughMonday) {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found — onboarding has not been completed yet.";

            LocalDate from = parseIso(fromMonday);
            if (from == null) {
                return "Could not understand the start date '" + fromMonday + "'. Use an ISO date like 2026-05-25.";
            }
            LocalDate through = parseIso(throughMonday);
            if (through == null) through = from; // single-week request

            PlannedWeeksResult result =
                    planService.generatePlannedWeeks(hh, from, through, recipeCatalog);

            if (result.noActivePlan()) {
                return "There is no active plan yet, so there is no current week to plan forward from. "
                        + "Generate the current week first via onboarding.";
            }

            // BR-02 refusal: nothing created AND nothing skipped means the whole request resolved to
            // the past / current week (clamped away). Name the earliest plannable week.
            if (result.created().isEmpty() && result.skippedExisting().isEmpty()) {
                String earliest = result.earliestAllowed() != null
                        ? result.earliestAllowed().format(WEEK_OF) : "next Monday";
                return "REFUSED: that range is in the past or the current week — I can only plan weeks from "
                        + "the week of " + earliest + " onward. No plans were created.";
            }

            String summary = buildSummary(result);
            log.info("planFutureWeeks: created={}, skipped={}, capHit={}",
                    result.created().size(), result.skippedExisting().size(), result.capHit());
            return summary;
        } catch (Exception e) {
            log.warn("planFutureWeeks error: {}", e.getMessage());
            return "Could not generate the requested weeks: " + e.getMessage();
        }
    }

    /** Builds a factual, non-fabricated summary from the generated rows (BR-08). */
    private String buildSummary(PlannedWeeksResult result) {
        var created = result.created();
        var sb = new StringBuilder();

        if (created.isEmpty()) {
            // Everything requested already existed (BR-03 idempotence).
            int n = result.skippedExisting().size();
            sb.append("All ").append(n).append(n == 1 ? " requested week was" : " requested weeks were")
              .append(" already on the calendar — nothing new to plan.");
            return sb.toString();
        }

        // Cost envelope: per-plan total summed from live meal costs (no fabrication).
        BigDecimal min = null, max = null;
        for (Plan plan : created) {
            BigDecimal weekCost = BigDecimal.ZERO;
            for (var meal : planService.findMeals(plan.getId())) {
                weekCost = weekCost.add(mealCostCalculator.costFor(meal));
            }
            if (min == null || weekCost.compareTo(min) < 0) min = weekCost;
            if (max == null || weekCost.compareTo(max) > 0) max = weekCost;
        }

        LocalDate firstWeek = created.get(0).getWeekStartDate();
        LocalDate lastWeek = created.get(created.size() - 1).getWeekStartDate();

        if (created.size() == 1) {
            sb.append("Planned the week of ").append(firstWeek.format(WEEK_OF)).append(" — 7 dinners");
            if (min != null) sb.append(", est. €").append(round(min));
            sb.append(".");
        } else {
            sb.append("Planned ").append(created.size()).append(" weeks (")
              .append(firstWeek.format(WEEK_OF)).append(" through ").append(lastWeek.format(WEEK_OF))
              .append("), 7 dinners each");
            if (min != null && max != null) {
                if (min.compareTo(max) == 0) {
                    sb.append(", est. €").append(round(min)).append(" per week");
                } else {
                    sb.append(", est. €").append(round(min)).append("–€").append(round(max)).append(" per week");
                }
            }
            sb.append(".");
        }

        // Filter outcomes actually applied (factual — from the household profile, not invented).
        var hh = householdService.findHousehold().orElse(null);
        if (hh != null && hh.getAllergies() != null && !hh.getAllergies().isEmpty()) {
            sb.append(" Same hard allergy filter as your active week: no ")
              .append(String.join(", ", hh.getAllergies())).append(".");
        }

        if (!result.skippedExisting().isEmpty()) {
            int n = result.skippedExisting().size();
            sb.append(" ").append(n).append(n == 1
                    ? " week was already planned and left unchanged."
                    : " weeks were already planned and left unchanged.");
        }
        if (result.capHit()) {
            // Per-turn cap (BR-05). Phrased so the model reports the stop WITHOUT auto-continuing:
            // the "do not re-call this turn" rule lives in the system prompt, not in this user-facing text.
            sb.append(" Stopped at the ").append(PlanService.MAX_WEEKS_PER_CALL)
              .append("-week limit for this request; the remaining weeks were not planned.");
        }
        return sb.toString();
    }

    private static String round(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static LocalDate parseIso(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
