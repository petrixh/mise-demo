package com.example.mise.domain.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findByHouseholdIdOrderByWeekStartDateDesc(Long householdId);

    Optional<Plan> findByHouseholdIdAndStatus(Long householdId, Plan.Status status);

    Optional<Plan> findByHouseholdIdAndWeekStartDate(Long householdId, LocalDate weekStartDate);
}
