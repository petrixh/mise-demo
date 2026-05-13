package com.example.mise.capabilities.recipes;

/**
 * Nutritional macros per serving (seed-data DTO).
 */
public class RecipeMacros {
    private int kcal;
    private int protein;
    private int carb;
    private int fat;

    public RecipeMacros() {}

    public int getKcal() { return kcal; }
    public void setKcal(int kcal) { this.kcal = kcal; }

    public int getProtein() { return protein; }
    public void setProtein(int protein) { this.protein = protein; }

    public int getCarb() { return carb; }
    public void setCarb(int carb) { this.carb = carb; }

    public int getFat() { return fat; }
    public void setFat(int fat) { this.fat = fat; }
}
