package com.example.mise.domain.shopping;

import java.util.List;

/**
 * One aisle section in the derived shopping list.
 */
public record AisleGroup(String aisle, List<ShoppingItem> items) {}
