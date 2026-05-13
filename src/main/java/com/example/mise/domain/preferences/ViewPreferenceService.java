package com.example.mise.domain.preferences;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes per-household widget preferences stored as JSON in {@link ViewPreference}.
 */
@Service
public class ViewPreferenceService {

    private final ViewPreferenceRepository repository;
    private final ObjectMapper objectMapper;

    public ViewPreferenceService(ViewPreferenceRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the decoded settings map for the given household/view/widgetKey,
     * or empty if no preference row exists yet.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getSettings(Long householdId, ViewPreference.View view, String widgetKey) {
        return repository.findByHouseholdIdAndViewAndWidgetKey(householdId, view, widgetKey)
                .map(pref -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = objectMapper.readValue(pref.getSettings(), Map.class);
                        return map;
                    } catch (Exception e) {
                        return Map.<String, Object>of();
                    }
                });
    }

    /**
     * Upserts (insert or update) the settings map for the given household/view/widgetKey.
     */
    @Transactional
    public void saveSettings(Long householdId, ViewPreference.View view, String widgetKey, Map<String, Object> settings) {
        var existing = repository.findByHouseholdIdAndViewAndWidgetKey(householdId, view, widgetKey);
        ViewPreference pref = existing.orElseGet(ViewPreference::new);
        pref.setHouseholdId(householdId);
        pref.setView(view);
        pref.setWidgetKey(widgetKey);
        try {
            pref.setSettings(objectMapper.writeValueAsString(settings));
        } catch (Exception e) {
            pref.setSettings("{}");
        }
        repository.save(pref);
    }
}
