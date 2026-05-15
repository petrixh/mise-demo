package com.example.mise.domain.shopping;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PantryRepository extends JpaRepository<PantryItem, Long> {

    List<PantryItem> findByHouseholdId(Long householdId);

    void deleteByHouseholdId(Long householdId);
}
