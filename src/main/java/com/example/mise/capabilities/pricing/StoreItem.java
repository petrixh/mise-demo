package com.example.mise.capabilities.pricing;

/**
 * One line in a store's price catalog (seed-data DTO).
 */
public class StoreItem {

    private String ingredientName;
    private double price;
    private String unit;
    private int packSize;
    private boolean onSale;
    private String saleUntil;

    public StoreItem() {}

    public String getIngredientName() { return ingredientName; }
    public void setIngredientName(String ingredientName) { this.ingredientName = ingredientName; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public int getPackSize() { return packSize; }
    public void setPackSize(int packSize) { this.packSize = packSize; }

    public boolean isOnSale() { return onSale; }
    public void setOnSale(boolean onSale) { this.onSale = onSale; }

    public String getSaleUntil() { return saleUntil; }
    public void setSaleUntil(String saleUntil) { this.saleUntil = saleUntil; }
}
