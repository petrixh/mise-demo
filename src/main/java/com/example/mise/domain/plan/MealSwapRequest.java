package com.example.mise.domain.plan;

/**
 * Simple value object representing a single meal swap within a multi-meal negotiation.
 * Used by {@link PlanService#negotiateWeek}.
 */
public record MealSwapRequest(Long mealId, String newRecipeRef) {}
