package com.example.mise.domain.reports;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One data point in the weekly cost trend: the Monday of a given plan week and the
 * total ingredient cost (summed across all 7 meals) for that week.
 */
public record WeeklyCostPoint(LocalDate weekStartDate, BigDecimal totalCost) {}
