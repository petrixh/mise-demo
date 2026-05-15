package com.example.mise.domain.reports;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One row in the per-meal leaderboard: ranked by how often a recipe appears across all plans.
 * The {@code extras} map holds derived columns (e.g. "kcalPerEuro") when requested.
 */
public record LeaderboardEntry(
        int rank,
        String recipeName,
        String recipeRef,
        int frequency,
        BigDecimal averageCost,
        double averageKcal,
        Map<String, Object> extras
) {}
