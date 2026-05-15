package com.example.mise.capabilities.personas;

import java.util.Optional;

/**
 * Reads persona definitions and the active persona selection from seed data.
 */
public interface PersonaCatalog {

    /** Returns the currently active persona (from {@code active_persona.txt}). */
    Optional<Persona> findActivePersona();

    /** Finds a persona by id. */
    Optional<Persona> findById(String id);
}
