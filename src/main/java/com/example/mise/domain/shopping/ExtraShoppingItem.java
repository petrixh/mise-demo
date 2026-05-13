package com.example.mise.domain.shopping;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * An ad-hoc shopping item added by the user via chat ("Add 200g extra cheese").
 * These items do not derive from the active meal plan; they are appended to the
 * derived list in the appropriate aisle (or "Extras" if the aisle is unknown).
 * BR-01: the derived shopping list includes these rows alongside plan-derived rows.
 */
@Entity
@Table(name = "extra_shopping_item")
public class ExtraShoppingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    @Column(name = "ingredient_name", nullable = false, length = 128)
    private String ingredientName;

    @Column(name = "quantity")
    private double quantity;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @PrePersist
    void onCreate() {
        if (addedAt == null) addedAt = Instant.now();
    }

    public ExtraShoppingItem() {}

    public Long getId() { return id; }

    public Long getHouseholdId() { return householdId; }
    public void setHouseholdId(Long householdId) { this.householdId = householdId; }

    public String getIngredientName() { return ingredientName; }
    public void setIngredientName(String ingredientName) { this.ingredientName = ingredientName; }

    public double getQuantity() { return quantity; }
    public void setQuantity(double quantity) { this.quantity = quantity; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public Instant getAddedAt() { return addedAt; }
}
