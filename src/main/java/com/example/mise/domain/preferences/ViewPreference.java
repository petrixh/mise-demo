package com.example.mise.domain.preferences;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persisted view-level widget preferences for a household.
 * Each row covers one widget within one view (e.g. SHOPPING / storeMode).
 * Settings are stored as a JSON CLOB and round-tripped via Jackson.
 */
@Entity
@Table(
        name = "view_preference",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_view_pref_household_view_key",
                columnNames = {"household_id", "view", "widget_key"}
        )
)
public class ViewPreference {

    public enum View { PLAN, SHOPPING, REPORTS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    @Enumerated(EnumType.STRING)
    @Column(name = "view", nullable = false, length = 16)
    private View view;

    @Column(name = "widget_key", nullable = false, length = 64)
    private String widgetKey;

    /** Jackson-serialised JSON map of settings for this widget. */
    @Lob
    @Column(name = "settings", nullable = false, columnDefinition = "CLOB")
    private String settings;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public ViewPreference() {}

    public Long getId() { return id; }

    public Long getHouseholdId() { return householdId; }
    public void setHouseholdId(Long householdId) { this.householdId = householdId; }

    public View getView() { return view; }
    public void setView(View view) { this.view = view; }

    public String getWidgetKey() { return widgetKey; }
    public void setWidgetKey(String widgetKey) { this.widgetKey = widgetKey; }

    public String getSettings() { return settings; }
    public void setSettings(String settings) { this.settings = settings; }

    public Instant getUpdatedAt() { return updatedAt; }
}
