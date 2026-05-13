package com.example.mise.domain.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MealEditRepository extends JpaRepository<MealEdit, Long> {

    /** Returns all edits for a given meal, newest first. Used by UC-004 "why?" queries. */
    List<MealEdit> findByMealIdOrderByChangedAtDesc(Long mealId);
}
