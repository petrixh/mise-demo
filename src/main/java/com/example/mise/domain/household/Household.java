package com.example.mise.domain.household;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A household is the central entity in Mise.
 * The demo runs single-household; there is always at most one row.
 */
@Entity
@Table(name = "household")
public class Household {

    public enum InsightFrequency { DAILY, WEEKLY, NEVER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 128)
    private String name;

    @Column(name = "size", nullable = false)
    private int size;

    @Column(name = "currency", length = 8)
    private String currency = "EUR";

    @Column(name = "weekly_budget", precision = 10, scale = 2)
    private BigDecimal weeklyBudget;

    @Convert(converter = StringListConverter.class)
    @Column(name = "dietary_constraints", columnDefinition = "CLOB")
    private List<String> dietaryConstraints = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "allergies", columnDefinition = "CLOB")
    private List<String> allergies = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "hated_foods", columnDefinition = "CLOB")
    private List<String> hatedFoods = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "loved_foods", columnDefinition = "CLOB")
    private List<String> lovedFoods = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "cuisine_prefs", columnDefinition = "CLOB")
    private List<String> cuisinePrefs = new ArrayList<>();

    @Column(name = "hosting_pattern", length = 256)
    private String hostingPattern;

    @Column(name = "insights_muted", nullable = false)
    private boolean insightsMuted = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "insight_frequency", length = 16)
    private InsightFrequency insightFrequency = InsightFrequency.WEEKLY;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Household() {}

    // Getters and setters
    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getWeeklyBudget() { return weeklyBudget; }
    public void setWeeklyBudget(BigDecimal weeklyBudget) { this.weeklyBudget = weeklyBudget; }

    public List<String> getDietaryConstraints() { return dietaryConstraints; }
    public void setDietaryConstraints(List<String> dietaryConstraints) { this.dietaryConstraints = dietaryConstraints; }

    public List<String> getAllergies() { return allergies; }
    public void setAllergies(List<String> allergies) { this.allergies = allergies; }

    public List<String> getHatedFoods() { return hatedFoods; }
    public void setHatedFoods(List<String> hatedFoods) { this.hatedFoods = hatedFoods; }

    public List<String> getLovedFoods() { return lovedFoods; }
    public void setLovedFoods(List<String> lovedFoods) { this.lovedFoods = lovedFoods; }

    public List<String> getCuisinePrefs() { return cuisinePrefs; }
    public void setCuisinePrefs(List<String> cuisinePrefs) { this.cuisinePrefs = cuisinePrefs; }

    public String getHostingPattern() { return hostingPattern; }
    public void setHostingPattern(String hostingPattern) { this.hostingPattern = hostingPattern; }

    public boolean isInsightsMuted() { return insightsMuted; }
    public void setInsightsMuted(boolean insightsMuted) { this.insightsMuted = insightsMuted; }

    public InsightFrequency getInsightFrequency() { return insightFrequency; }
    public void setInsightFrequency(InsightFrequency insightFrequency) { this.insightFrequency = insightFrequency; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
