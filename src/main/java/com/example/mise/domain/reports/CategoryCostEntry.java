package com.example.mise.domain.reports;

import java.math.BigDecimal;

/**
 * Cost contribution from a single recipe category in a given week.
 */
public record CategoryCostEntry(String category, BigDecimal totalCost) {}
