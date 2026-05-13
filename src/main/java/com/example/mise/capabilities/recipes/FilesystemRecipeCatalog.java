package com.example.mise.capabilities.recipes;

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
 * Loads recipe definitions from {@code <seed-dir>/recipes/*.yaml} at startup.
 * Thread-safe: the list is populated once at {@link PostConstruct} and is read-only thereafter.
 */
@Component
public class FilesystemRecipeCatalog implements RecipeCatalog {

    private static final Logger log = LoggerFactory.getLogger(FilesystemRecipeCatalog.class);

    private final String seedDirectory;
    private List<Recipe> recipes = Collections.emptyList();

    public FilesystemRecipeCatalog(
            @Value("${mise.seed.directory:demo/data}") String seedDirectory) {
        this.seedDirectory = seedDirectory;
    }

    @PostConstruct
    void load() {
        var recipesDir = new File(seedDirectory, "recipes");
        if (!recipesDir.exists() || !recipesDir.isDirectory()) {
            log.warn("Recipes directory not found: {}; catalog will be empty", recipesDir.getAbsolutePath());
            return;
        }
        var mapper = YAMLMapper.builder().build();
        var loaded = new ArrayList<Recipe>();
        File[] yamlFiles = recipesDir.listFiles(f -> f.getName().endsWith(".yaml") || f.getName().endsWith(".yml"));
        if (yamlFiles == null) return;
        for (var file : yamlFiles) {
            try {
                var recipe = mapper.readValue(file, Recipe.class);
                // Ensure id is set even if YAML omits it (use filename stem)
                if (recipe.getId() == null || recipe.getId().isBlank()) {
                    var stem = file.getName().replaceFirst("\\.[^.]+$", "");
                    recipe.setId(stem);
                }
                loaded.add(recipe);
                log.debug("Loaded recipe: {} ({})", recipe.getName(), recipe.getId());
            } catch (Exception e) {
                log.error("Failed to load recipe from {}: {}", file.getName(), e.getMessage());
            }
        }
        this.recipes = Collections.unmodifiableList(loaded);
        log.info("Loaded {} recipes from {}", recipes.size(), recipesDir.getAbsolutePath());
    }

    @Override
    public List<Recipe> findAll() {
        return recipes;
    }

    @Override
    public Optional<Recipe> findById(String id) {
        return recipes.stream().filter(r -> id.equals(r.getId())).findFirst();
    }
}
