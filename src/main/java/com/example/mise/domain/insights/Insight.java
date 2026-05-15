package com.example.mise.domain.insights;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * UC-009: An AI-generated advisory insight grounded in plan/meal history.
 * Insights are never auto-applied; they require explicit user confirmation.
 * Dismissing an insight does not delete it — it is retained for historical context (BR-07).
 */
@Entity
@Table(name = "insight")
public class Insight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    /** The human-readable insight text shown in the banner. */
    @Lob
    @Column(name = "body", nullable = false)
    private String body;

    /**
     * JSON referencing the Plan/Meal IDs that ground this insight (BR-03).
     * Format: {@code {"planIds":[1,2], "mealIds":[5,6,7]}}
     */
    @Lob
    @Column(name = "evidence_refs")
    private String evidenceRefs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "dismissed", nullable = false)
    private boolean dismissed = false;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Insight() {}

    // ── getters / setters ──────────────────────────────────────────────────

    public Long getId() { return id; }

    public Long getHouseholdId() { return householdId; }
    public void setHouseholdId(Long householdId) { this.householdId = householdId; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getEvidenceRefs() { return evidenceRefs; }
    public void setEvidenceRefs(String evidenceRefs) { this.evidenceRefs = evidenceRefs; }

    public Instant getCreatedAt() { return createdAt; }

    public boolean isDismissed() { return dismissed; }
    public void setDismissed(boolean dismissed) { this.dismissed = dismissed; }

    public Instant getDismissedAt() { return dismissedAt; }
    public void setDismissedAt(Instant dismissedAt) { this.dismissedAt = dismissedAt; }
}
