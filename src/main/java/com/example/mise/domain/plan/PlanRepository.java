package com.example.mise.domain.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findByHouseholdIdOrderByWeekStartDateDesc(Long householdId);

    Optional<Plan> findByHouseholdIdAndStatus(Long householdId, Plan.Status status);
}
