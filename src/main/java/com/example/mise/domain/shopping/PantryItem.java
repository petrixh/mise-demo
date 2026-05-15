package com.example.mise.domain.shopping;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A pantry item belonging to a household.
 */
@Entity
@Table(name = "pantry_item")
public class PantryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    @Column(name = "ingredient_name", nullable = false, length = 128)
    private String ingredientName;

    @Column(name = "quantity", precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "is_staple", nullable = false)
    private boolean staple;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public PantryItem() {}

    public Long getId() { return id; }

    public Long getHouseholdId() { return householdId; }
    public void setHouseholdId(Long householdId) { this.householdId = householdId; }

    public String getIngredientName() { return ingredientName; }
    public void setIngredientName(String ingredientName) { this.ingredientName = ingredientName; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public boolean isStaple() { return staple; }
    public void setStaple(boolean staple) { this.staple = staple; }

    public Instant getUpdatedAt() { return updatedAt; }
}
