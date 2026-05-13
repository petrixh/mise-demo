package com.example.mise.ai.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for UC-008 NavigationTools.goToView (BR-04, BR-05).
 *
 * Uses {@link NavigationTools#setNavigationExecutorForTesting} to avoid the
 * real Vaadin UI; all navigation calls are captured in a simple list.
 */
class NavigationToolsTest {

    private NavigationTools navigationTools;
    private final List<String> navigatedRoutes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        navigationTools = new NavigationTools();
        navigatedRoutes.clear();
        navigationTools.setNavigationExecutorForTesting(navigatedRoutes::add);
    }

    // ── Happy paths ────────────────────────────────────────────────────────────

    @Test
    void goToView_plan_triggersNavigationToPlanAndReturnsConfirmation() {
        String result = navigationTools.goToView("plan");

        assertThat(result).isEqualTo("Navigated to /plan.");
        assertThat(navigatedRoutes).containsExactly("plan");
    }

    @Test
    void goToView_reports_triggersNavigationToReportsAndReturnsConfirmation() {
        String result = navigationTools.goToView("reports");

        assertThat(result).isEqualTo("Navigated to /reports.");
        assertThat(navigatedRoutes).containsExactly("reports");
    }

    @Test
    void goToView_shopping_triggersNavigationToShoppingAndReturnsConfirmation() {
        String result = navigationTools.goToView("shopping");

        assertThat(result).isEqualTo("Navigated to /shopping.");
        assertThat(navigatedRoutes).containsExactly("shopping");
    }

    @Test
    void goToView_caseInsensitive_normalises() {
        String result = navigationTools.goToView("REPORTS");

        assertThat(result).isEqualTo("Navigated to /reports.");
        assertThat(navigatedRoutes).containsExactly("reports");
    }

    // ── REFUSED paths (no navigation must fire) ────────────────────────────────

    @Test
    void goToView_invalidView_returnsRefusedWithoutNavigating() {
        String result = navigationTools.goToView("dashboard");

        assertThat(result).startsWith("REFUSED:");
        assertThat(result).contains("dashboard");
        assertThat(result).containsIgnoringCase("valid values");
        assertThat(navigatedRoutes).isEmpty();
    }

    @Test
    void goToView_blank_returnsRefusedWithoutNavigating() {
        String result = navigationTools.goToView("   ");

        assertThat(result).startsWith("REFUSED:");
        assertThat(navigatedRoutes).isEmpty();
    }

    @Test
    void goToView_null_returnsRefusedWithoutNavigating() {
        String result = navigationTools.goToView(null);

        assertThat(result).startsWith("REFUSED:");
        assertThat(navigatedRoutes).isEmpty();
    }

    @Test
    void goToView_noUiSupplierAndNoExecutor_returnsRefused() {
        var toolsWithoutSetup = new NavigationTools();
        // Neither setUiSupplier nor setNavigationExecutorForTesting called

        String result = toolsWithoutSetup.goToView("plan");

        assertThat(result).startsWith("REFUSED:");
    }
}
