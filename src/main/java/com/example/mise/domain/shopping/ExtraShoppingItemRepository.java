package com.example.mise.domain.shopping;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExtraShoppingItemRepository extends JpaRepository<ExtraShoppingItem, Long> {

    List<ExtraShoppingItem> findByHouseholdId(Long householdId);

    void deleteByHouseholdId(Long householdId);
}
