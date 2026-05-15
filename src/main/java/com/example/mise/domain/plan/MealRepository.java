package com.example.mise.domain.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MealRepository extends JpaRepository<Meal, Long> {

    List<Meal> findByPlanIdOrderByDateAsc(Long planId);

    List<Meal> findByPlanId(Long planId);
}
