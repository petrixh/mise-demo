package com.example.mise.domain.plan;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Audit record for every AI- or user-driven meal swap.
 * Satisfies UC-003 BR-01 (edit history) and enables UC-004 "why?" queries.
 */
@Entity
@Table(name = "meal_edit")
public class MealEdit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meal_id", nullable = false)
    private Long mealId;

    @Column(name = "previous_recipe_ref", nullable = false, length = 128)
    private String previousRecipeRef;

    @Column(name = "previous_servings", nullable = false)
    private int previousServings;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 16)
    private Meal.Status previousStatus;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by", nullable = false, length = 8)
    private Meal.Editor changedBy;

    /** Free-text explanation from the AI (or user) for this swap. Populated on AI edits; nullable. */
    @Column(name = "reason", columnDefinition = "CLOB")
    private String reason;

    public MealEdit() {}

    @PrePersist
    void onCreate() {
        if (changedAt == null) changedAt = Instant.now();
    }

    // ── Getters and setters ──────────────────────────────────────────────────

    public Long getId() { return id; }

    public Long getMealId() { return mealId; }
    public void setMealId(Long mealId) { this.mealId = mealId; }

    public String getPreviousRecipeRef() { return previousRecipeRef; }
    public void setPreviousRecipeRef(String previousRecipeRef) { this.previousRecipeRef = previousRecipeRef; }

    public int getPreviousServings() { return previousServings; }
    public void setPreviousServings(int previousServings) { this.previousServings = previousServings; }

    public Meal.Status getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(Meal.Status previousStatus) { this.previousStatus = previousStatus; }

    public Instant getChangedAt() { return changedAt; }
    public void setChangedAt(Instant changedAt) { this.changedAt = changedAt; }

    public Meal.Editor getChangedBy() { return changedBy; }
    public void setChangedBy(Meal.Editor changedBy) { this.changedBy = changedBy; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
