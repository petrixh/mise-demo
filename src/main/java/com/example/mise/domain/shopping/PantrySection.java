package com.example.mise.domain.shopping;

import java.util.List;

/**
 * The "You already have" section: pantry items that were subtracted from the active list.
 */
public record PantrySection(List<PantryItem> items) {}
