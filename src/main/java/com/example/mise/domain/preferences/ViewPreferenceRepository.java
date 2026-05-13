package com.example.mise.domain.preferences;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ViewPreferenceRepository extends JpaRepository<ViewPreference, Long> {

    Optional<ViewPreference> findByHouseholdIdAndViewAndWidgetKey(
            Long householdId, ViewPreference.View view, String widgetKey);
}
