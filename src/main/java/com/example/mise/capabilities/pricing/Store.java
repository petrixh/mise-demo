package com.example.mise.capabilities.pricing;

import java.util.List;

/**
 * A store with its price catalog (seed-data DTO).
 * Loaded from YAML; not persisted to H2.
 */
public class Store {

    private String id;
    private String name;
    private int detourMinutesFromRoute;
    private boolean defaultStore;
    private List<StoreItem> catalog;

    public Store() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getDetourMinutesFromRoute() { return detourMinutesFromRoute; }
    public void setDetourMinutesFromRoute(int detourMinutesFromRoute) {
        this.detourMinutesFromRoute = detourMinutesFromRoute;
    }

    public boolean isDefaultStore() { return defaultStore; }
    public void setDefaultStore(boolean defaultStore) { this.defaultStore = defaultStore; }

    public List<StoreItem> getCatalog() { return catalog; }
    public void setCatalog(List<StoreItem> catalog) { this.catalog = catalog; }
}
