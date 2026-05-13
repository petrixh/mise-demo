package com.example.mise.domain.reports;

import java.time.LocalDate;
import java.util.List;

/**
 * Category-level cost breakdown for a single plan week.
 */
public record CategoryBreakdown(LocalDate weekStartDate, List<CategoryCostEntry> entries) {}
