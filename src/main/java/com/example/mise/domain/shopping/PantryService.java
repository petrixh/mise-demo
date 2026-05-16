package com.example.mise.domain.shopping;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages pantry items for a household.
 */
@Service
public class PantryService {

    private final PantryRepository repository;

    public PantryService(PantryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<PantryItem> findByHousehold(Long householdId) {
        return repository.findByHouseholdId(householdId);
    }

    @Transactional
    public PantryItem save(PantryItem item) {
        return repository.save(item);
    }

    /**
     * Removes a pantry item by its id.
     * No-op if the id is null or not found.
     */
    @Transactional
    public void remove(Long id) {
        if (id == null) return;
        repository.deleteById(id);
    }

    /**
     * Seeds pantry staples from a list of ingredient names.
     * Idempotent: does not duplicate if the household already has items.
     */
    @Transactional
    public void seedStaples(Long householdId, List<String> stapleNames) {
        var existing = repository.findByHouseholdId(householdId);
        if (!existing.isEmpty()) return; // already seeded
        for (var name : stapleNames) {
            var item = new PantryItem();
            item.setHouseholdId(householdId);
            item.setIngredientName(name);
            item.setStaple(true);
            repository.save(item);
        }
    }
}
