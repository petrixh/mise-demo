package com.example.mise.domain.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findByHouseholdIdOrderByWeekStartDateDesc(Long householdId);

    Optional<Plan> findByHouseholdIdAndStatus(Long householdId, Plan.Status status);

    Optional<Plan> findByHouseholdIdAndWeekStartDate(Long householdId, LocalDate weekStartDate);

    /** UC-011 (BR-06): all plans of a given status for a household, oldest first. */
    List<Plan> findByHouseholdIdAndStatusOrderByWeekStartDateAsc(Long householdId, Plan.Status status);

    /** UC-011: all plans whose status is in the given set (e.g. count of ACTIVE plans for invariant checks). */
    List<Plan> findByHouseholdIdAndStatusIn(Long householdId, java.util.Collection<Plan.Status> statuses);
}
