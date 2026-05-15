package com.example.mise.capabilities.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.yaml.YAMLMapper;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Loads store catalogs from {@code <seed-dir>/stores/*.yaml} at startup.
 * Thread-safe after {@link PostConstruct}.
 */
@Component
public class StubbedPriceCatalog implements PriceCatalog {

    private static final Logger log = LoggerFactory.getLogger(StubbedPriceCatalog.class);

    private final String seedDirectory;
    private List<Store> stores = Collections.emptyList();

    public StubbedPriceCatalog(
            @Value("${mise.seed.directory:demo/data}") String seedDirectory) {
        this.seedDirectory = seedDirectory;
    }

    @PostConstruct
    void load() {
        var storesDir = new File(seedDirectory, "stores");
        if (!storesDir.exists() || !storesDir.isDirectory()) {
            log.warn("Stores directory not found: {}; price catalog will be empty", storesDir.getAbsolutePath());
            return;
        }
        var mapper = YAMLMapper.builder().build();
        var loaded = new ArrayList<Store>();
        File[] yamlFiles = storesDir.listFiles(f -> f.getName().endsWith(".yaml") || f.getName().endsWith(".yml"));
        if (yamlFiles == null) return;
        for (var file : yamlFiles) {
            try {
                var store = mapper.readValue(file, Store.class);
                if (store.getId() == null || store.getId().isBlank()) {
                    store.setId(file.getName().replaceFirst("\\.[^.]+$", ""));
                }
                loaded.add(store);
                log.debug("Loaded store: {} ({})", store.getName(), store.getId());
            } catch (Exception e) {
                log.error("Failed to load store from {}: {}", file.getName(), e.getMessage());
            }
        }
        this.stores = Collections.unmodifiableList(loaded);
        log.info("Loaded {} stores from {}", stores.size(), storesDir.getAbsolutePath());
    }

    @Override
    public List<Store> findAllStores() {
        return stores;
    }

    @Override
    public Optional<Store> findDefaultStore() {
        return stores.stream().filter(Store::isDefaultStore).findFirst();
    }

    @Override
    public Optional<Double> findPrice(String ingredientName) {
        return findDefaultStore()
                .flatMap(store -> findPriceInStore(store, ingredientName));
    }

    private Optional<Double> findPriceInStore(Store store, String ingredientName) {
        if (store.getCatalog() == null) return Optional.empty();
        String lower = ingredientName.toLowerCase();
        return store.getCatalog().stream()
                .filter(item -> item.getIngredientName().toLowerCase().contains(lower)
                        || lower.contains(item.getIngredientName().toLowerCase()))
                .findFirst()
                .map(StoreItem::getPrice);
    }
}
