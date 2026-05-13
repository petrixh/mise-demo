package com.example.mise.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * UC-009 persistence for {@link Insight} rows.
 */
public interface InsightRepository extends JpaRepository<Insight, Long> {

    /**
     * Returns the oldest undismissed insight for a household (FIFO queue — BR-02).
     * Used to surface the next actionable banner.
     */
    Optional<Insight> findFirstByHouseholdIdAndDismissedFalseOrderByCreatedAtAsc(Long householdId);

    /**
     * Returns all insights for a household, newest first.
     * Used for "show me insights I missed" (BR-05 — independent of muted state).
     */
    List<Insight> findByHouseholdIdOrderByCreatedAtDesc(Long householdId);

    /**
     * Returns the most recently created insight for a household.
     * Used by the startup-trigger window check (BR-04 a).
     */
    Optional<Insight> findFirstByHouseholdIdOrderByCreatedAtDesc(Long householdId);
}
