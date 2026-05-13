package com.example.mise.domain.household;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * CRUD operations for the (single-household) household entity.
 */
@Service
public class HouseholdService {

    private final HouseholdRepository repository;

    public HouseholdService(HouseholdRepository repository) {
        this.repository = repository;
    }

    /** Returns the household if it exists (at most one in the demo). */
    @Transactional(readOnly = true)
    public Optional<Household> findHousehold() {
        return repository.findAll().stream().findFirst();
    }

    /** Returns true if a household row exists. */
    @Transactional(readOnly = true)
    public boolean exists() {
        return repository.count() > 0;
    }

    /** Persists or updates the household. */
    @Transactional
    public Household save(Household household) {
        return repository.save(household);
    }
}
