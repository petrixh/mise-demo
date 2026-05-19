package com.example.mise.ui;

import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

/**
 * UC-010: Helpers for resolving and navigating the "viewed week" concept.
 *
 * <p>The viewed week is always a real {@link Plan} row owned by the household (BR-01).
 * If the requested date does not correspond to any plan, the fallback is the ACTIVE plan.
 */
@Service
public class ViewedWeekService {

    private final PlanService planService;

    public ViewedWeekService(PlanService planService) {
        this.planService = planService;
    }

    /**
     * Resolves the plan the UI should display.
     *
     * <ul>
     *   <li>If {@code weekParam} is a parseable ISO date, snap to that week's Monday and look up
     *       the plan. If not found fall back to the active plan.</li>
     *   <li>If {@code weekParam} is blank / null, return the active plan.</li>
     * </ul>
     */
    public Optional<Plan> resolveViewedPlan(Long householdId, String weekParam) {
        if (weekParam != null && !weekParam.isBlank()) {
            try {
                LocalDate any = LocalDate.parse(weekParam.trim());
                LocalDate monday = snapToMonday(any);
                var planOpt = planService.findByWeekStartDate(householdId, monday);
                if (planOpt.isPresent()) return planOpt;
            } catch (Exception ignored) {
                // fall through to active plan
            }
        }
        return planService.findActivePlan(householdId);
    }

    /**
     * Returns all plans for the household ordered oldest-first (ascending weekStartDate).
     * Convenience for boundary checks (prev/next disable logic in MainLayout).
     */
    public List<Plan> allPlansOrderedAsc(Long householdId) {
        return planService.findAllPlansOrderedAsc(householdId);
    }

    /**
     * Returns the plan immediately before the given Monday, if any.
     */
    public Optional<Plan> previousPlan(Long householdId, LocalDate viewedMonday) {
        var allAsc = allPlansOrderedAsc(householdId);
        Plan previous = null;
        for (Plan p : allAsc) {
            if (p.getWeekStartDate().isBefore(viewedMonday)) {
                previous = p;
            } else {
                break;
            }
        }
        return Optional.ofNullable(previous);
    }

    /**
     * Returns the plan immediately after the given Monday, if any.
     */
    public Optional<Plan> nextPlan(Long householdId, LocalDate viewedMonday) {
        var allAsc = allPlansOrderedAsc(householdId);
        for (Plan p : allAsc) {
            if (p.getWeekStartDate().isAfter(viewedMonday)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * Snaps any calendar date to that week's Monday (BR-07).
     */
    public LocalDate snapToMonday(LocalDate any) {
        if (any == null) return null;
        return any.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
