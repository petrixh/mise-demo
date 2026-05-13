package com.example.mise.domain.reports;

import java.util.List;

/**
 * Ordered list of weekly cost points for trend display.
 * Points are ordered oldest-first (ascending by weekStartDate) so chart axes are natural.
 */
public record WeeklyCostTrend(List<WeeklyCostPoint> points) {}
