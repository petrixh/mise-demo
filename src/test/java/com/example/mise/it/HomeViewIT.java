package com.example.mise.it;

import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Pilot Playwright IT — validates the IT toolchain (Spring Boot + Playwright + DramaFinder)
 * against the simplest view in the app. Intentionally does not interact with the chat panel
 * or any AI surface: those are covered by separate ITs that stub the {@code ChatModel}.
 */
class HomeViewIT extends MisePlaywrightIT {

    @Override
    public String getView() {
        return "/debug";
    }

    @Test
    void hasPageTitle() {
        assertThat(page).hasTitle("Mise — Debug");
    }

    @Test
    void rendersStageHeader() {
        assertThat(page.getByRole(com.microsoft.playwright.options.AriaRole.HEADING)
                .filter(new com.microsoft.playwright.Locator.FilterOptions()
                        .setHasText("Mise — prep build (stage 2)")))
                .isVisible();
    }

    @Test
    void showsStoredMessageCount() {
        assertThat(page.getByText("Stored messages:")).isVisible();
    }
}
