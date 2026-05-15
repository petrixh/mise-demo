package com.example.mise.domain.plan;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One meal within a Plan.
 */
@Entity
@Table(name = "meal")
public class Meal {

    public enum Slot { DINNER }

    public enum Status { PLANNED, EDITED, COOKED, SKIPPED }

    public enum Editor { USER, AI }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot", nullable = false, length = 16)
    private Slot slot = Slot.DINNER;

    /** Reference to a Recipe id from the RecipeCatalog (not an FK; seed data is in-memory). */
    @Column(name = "recipe_ref", nullable = false, length = 128)
    private String recipeRef;

    @Column(name = "servings", nullable = false)
    private int servings;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PLANNED;

    @Column(name = "pinned", nullable = false)
    private boolean pinned = false;

    @Column(name = "note", columnDefinition = "CLOB")
    private String note;

    @Column(name = "last_edited_at")
    private Instant lastEditedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_edited_by", length = 8)
    private Editor lastEditedBy;

    @PrePersist
    void onCreate() {
        if (lastEditedAt == null) lastEditedAt = Instant.now();
    }

    public Meal() {}

    public Long getId() { return id; }

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public Slot getSlot() { return slot; }
    public void setSlot(Slot slot) { this.slot = slot; }

    public String getRecipeRef() { return recipeRef; }
    public void setRecipeRef(String recipeRef) { this.recipeRef = recipeRef; }

    public int getServings() { return servings; }
    public void setServings(int servings) { this.servings = servings; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Instant getLastEditedAt() { return lastEditedAt; }
    public void setLastEditedAt(Instant lastEditedAt) { this.lastEditedAt = lastEditedAt; }

    public Editor getLastEditedBy() { return lastEditedBy; }
    public void setLastEditedBy(Editor lastEditedBy) { this.lastEditedBy = lastEditedBy; }
}
