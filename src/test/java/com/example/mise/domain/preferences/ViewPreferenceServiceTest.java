package com.example.mise.domain.preferences;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ViewPreferenceService: round-trip JSON and upsert behaviour.
 */
@SpringBootTest
@Transactional
class ViewPreferenceServiceTest {

    @Autowired
    private ViewPreferenceService viewPreferenceService;

    @Autowired
    private ViewPreferenceRepository viewPreferenceRepository;

    private static final Long HOUSEHOLD_ID = 42L;

    @BeforeEach
    void cleanUp() {
        viewPreferenceRepository.deleteAll();
    }

    // ── round-trip ────────────────────────────────────────────────────────────

    @Test
    void getSettings_noRowExists_returnsEmpty() {
        Optional<Map<String, Object>> result = viewPreferenceService.getSettings(
                HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode");
        assertThat(result).isEmpty();
    }

    @Test
    void saveAndGet_roundTrip_preservesValues() {
        Map<String, Object> settings = Map.of("mode", "ONE_STORE", "lastUpdated", "2026-05-13");

        viewPreferenceService.saveSettings(HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode", settings);

        Optional<Map<String, Object>> loaded = viewPreferenceService.getSettings(
                HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().get("mode")).isEqualTo("ONE_STORE");
        assertThat(loaded.get().get("lastUpdated")).isEqualTo("2026-05-13");
    }

    @Test
    void saveAndGet_complexSettings_roundTrip() {
        Map<String, Object> settings = Map.of("enabled", true, "count", 7, "label", "hello");

        viewPreferenceService.saveSettings(HOUSEHOLD_ID, ViewPreference.View.PLAN, "widgetA", settings);

        var loaded = viewPreferenceService.getSettings(HOUSEHOLD_ID, ViewPreference.View.PLAN, "widgetA");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().get("enabled")).isEqualTo(true);
        assertThat(loaded.get().get("label")).isEqualTo("hello");
    }

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test
    void save_thenSaveAgain_updatesExistingRow() {
        viewPreferenceService.saveSettings(HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode",
                Map.of("mode", "ONE_STORE"));

        // Should be exactly one row
        assertThat(viewPreferenceRepository.findAll()).hasSize(1);

        // Update to CHEAPEST_MIX
        viewPreferenceService.saveSettings(HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode",
                Map.of("mode", "CHEAPEST_MIX"));

        // Still exactly one row — upserted, not duplicated
        assertThat(viewPreferenceRepository.findAll()).hasSize(1);

        // Value should be updated
        var loaded = viewPreferenceService.getSettings(
                HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().get("mode")).isEqualTo("CHEAPEST_MIX");
    }

    @Test
    void save_differentWidgetKeys_createsSeparateRows() {
        viewPreferenceService.saveSettings(HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode",
                Map.of("mode", "ONE_STORE"));
        viewPreferenceService.saveSettings(HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "sortOrder",
                Map.of("order", "aisle"));

        assertThat(viewPreferenceRepository.findAll()).hasSize(2);
    }

    @Test
    void save_differentViews_createsSeparateRows() {
        viewPreferenceService.saveSettings(HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode",
                Map.of("mode", "ONE_STORE"));
        viewPreferenceService.saveSettings(HOUSEHOLD_ID, ViewPreference.View.PLAN, "storeMode",
                Map.of("mode", "ONE_STORE"));

        assertThat(viewPreferenceRepository.findAll()).hasSize(2);
    }

    @Test
    void save_differentHouseholds_createsSeparateRows() {
        viewPreferenceService.saveSettings(HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode",
                Map.of("mode", "ONE_STORE"));
        viewPreferenceService.saveSettings(99L, ViewPreference.View.SHOPPING, "storeMode",
                Map.of("mode", "CHEAPEST_MIX"));

        assertThat(viewPreferenceRepository.findAll()).hasSize(2);

        // Each household gets its own value
        var hh42 = viewPreferenceService.getSettings(HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode");
        var hh99 = viewPreferenceService.getSettings(99L, ViewPreference.View.SHOPPING, "storeMode");
        assertThat(hh42.get().get("mode")).isEqualTo("ONE_STORE");
        assertThat(hh99.get().get("mode")).isEqualTo("CHEAPEST_MIX");
    }

    // ── unique constraint ─────────────────────────────────────────────────────

    @Test
    void findByHouseholdIdAndViewAndWidgetKey_findsCorrectRow() {
        viewPreferenceService.saveSettings(HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode",
                Map.of("mode", "ONE_STORE"));

        var found = viewPreferenceRepository.findByHouseholdIdAndViewAndWidgetKey(
                HOUSEHOLD_ID, ViewPreference.View.SHOPPING, "storeMode");

        assertThat(found).isPresent();
        assertThat(found.get().getHouseholdId()).isEqualTo(HOUSEHOLD_ID);
        assertThat(found.get().getView()).isEqualTo(ViewPreference.View.SHOPPING);
        assertThat(found.get().getWidgetKey()).isEqualTo("storeMode");
    }
}
