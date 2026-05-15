package com.example.mise.capabilities.personas;

import java.util.List;

/**
 * Seed persona DTO. Loaded from {@code demo/data/personas/<id>.json}.
 * Not persisted to H2 — used as a starting point for onboarding.
 */
public class Persona {

    private String id;
    private String name;
    private int size;
    private List<String> dietaryConstraints;
    private List<String> allergies;
    private List<String> hatedFoods;
    private List<String> lovedFoods;
    private double weeklyBudget;
    private String currency;
    private String hostingPattern;
    private List<String> defaultPantry;
    private List<String> cuisinePrefs;
    private int seedWeeks;

    public Persona() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public List<String> getDietaryConstraints() { return dietaryConstraints; }
    public void setDietaryConstraints(List<String> dietaryConstraints) { this.dietaryConstraints = dietaryConstraints; }

    public List<String> getAllergies() { return allergies; }
    public void setAllergies(List<String> allergies) { this.allergies = allergies; }

    public List<String> getHatedFoods() { return hatedFoods; }
    public void setHatedFoods(List<String> hatedFoods) { this.hatedFoods = hatedFoods; }

    public List<String> getLovedFoods() { return lovedFoods; }
    public void setLovedFoods(List<String> lovedFoods) { this.lovedFoods = lovedFoods; }

    public double getWeeklyBudget() { return weeklyBudget; }
    public void setWeeklyBudget(double weeklyBudget) { this.weeklyBudget = weeklyBudget; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getHostingPattern() { return hostingPattern; }
    public void setHostingPattern(String hostingPattern) { this.hostingPattern = hostingPattern; }

    public List<String> getDefaultPantry() { return defaultPantry; }
    public void setDefaultPantry(List<String> defaultPantry) { this.defaultPantry = defaultPantry; }

    public List<String> getCuisinePrefs() { return cuisinePrefs; }
    public void setCuisinePrefs(List<String> cuisinePrefs) { this.cuisinePrefs = cuisinePrefs; }

    public int getSeedWeeks() { return seedWeeks; }
    public void setSeedWeeks(int seedWeeks) { this.seedWeeks = seedWeeks; }
}
