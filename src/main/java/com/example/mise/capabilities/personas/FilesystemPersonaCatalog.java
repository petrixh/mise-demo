package com.example.mise.capabilities.personas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads persona definitions from {@code <seed-dir>/personas/<id>.json}.
 * Active persona is read from {@code <active-persona-file>} (single line: persona id).
 * Thread-safe after {@link PostConstruct}.
 */
@Component
public class FilesystemPersonaCatalog implements PersonaCatalog {

    private static final Logger log = LoggerFactory.getLogger(FilesystemPersonaCatalog.class);

    private final String seedDirectory;
    private final String activePersonaFile;
    private Map<String, Persona> personas = Map.of();
    private String activePersonaId;

    public FilesystemPersonaCatalog(
            @Value("${mise.seed.directory:demo/data}") String seedDirectory,
            @Value("${mise.seed.active-persona-file:demo/data/active_persona.txt}") String activePersonaFile) {
        this.seedDirectory = seedDirectory;
        this.activePersonaFile = activePersonaFile;
    }

    @PostConstruct
    void load() {
        // Read active persona id
        var personaFile = new File(activePersonaFile);
        if (personaFile.exists()) {
            try {
                activePersonaId = Files.readString(personaFile.toPath()).strip();
                log.info("Active persona id: {}", activePersonaId);
            } catch (IOException e) {
                log.warn("Could not read active_persona.txt: {}", e.getMessage());
            }
        } else {
            log.warn("active_persona.txt not found at {}", personaFile.getAbsolutePath());
        }

        // Load all persona JSON files
        var personasDir = new File(seedDirectory, "personas");
        if (!personasDir.exists() || !personasDir.isDirectory()) {
            log.warn("Personas directory not found: {}; no personas loaded", personasDir.getAbsolutePath());
            return;
        }
        var mapper = new ObjectMapper();
        var loaded = new HashMap<String, Persona>();
        File[] jsonFiles = personasDir.listFiles(f -> f.getName().endsWith(".json"));
        if (jsonFiles == null) return;
        for (var file : jsonFiles) {
            try {
                var persona = mapper.readValue(file, Persona.class);
                if (persona.getId() == null || persona.getId().isBlank()) {
                    persona.setId(file.getName().replaceFirst("\\.[^.]+$", ""));
                }
                loaded.put(persona.getId(), persona);
                log.debug("Loaded persona: {} ({})", persona.getName(), persona.getId());
            } catch (Exception e) {
                log.error("Failed to load persona from {}: {}", file.getName(), e.getMessage());
            }
        }
        this.personas = Map.copyOf(loaded);
        log.info("Loaded {} personas from {}", personas.size(), personasDir.getAbsolutePath());
    }

    @Override
    public Optional<Persona> findActivePersona() {
        if (activePersonaId == null) return Optional.empty();
        return findById(activePersonaId);
    }

    @Override
    public Optional<Persona> findById(String id) {
        return Optional.ofNullable(personas.get(id));
    }
}
