package com.example.mise.domain.plan;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-011: outcome of a {@link PlanService#generatePlannedWeeks} call.
 *
 * <p>Carries everything the {@code planFutureWeeks} tool needs to write a faithful,
 * non-fabricated summary (BR-08): the plans actually created, the target Mondays that
 * were skipped because a plan already existed (BR-03), whether the 8-week cap was hit
 * (BR-05), the earliest Monday that was allowed (BR-02), and whether there was no
 * ACTIVE plan to anchor against.
 *
 * @param created         plans newly created by this call, oldest first
 * @param skippedExisting target Mondays left untouched because a plan already existed
 * @param capHit          true if generation stopped at the 8-week cap with weeks still requested
 * @param earliestAllowed the earliest Monday planning was permitted (active week + 1)
 * @param noActivePlan    true if the household has no ACTIVE plan to plan forward from
 */
public record PlannedWeeksResult(
        List<Plan> created,
        List<LocalDate> skippedExisting,
        boolean capHit,
        LocalDate earliestAllowed,
        boolean noActivePlan) {
}
