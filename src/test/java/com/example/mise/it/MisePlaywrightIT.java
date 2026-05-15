package com.example.mise.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.example.mise.it.support.TestAiConfig;
import com.example.mise.it.support.TestChatModel;
import com.microsoft.playwright.Page;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.vaadin.addons.dramafinder.AbstractBasePlaywrightIT;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(TestAiConfig.class)
public abstract class MisePlaywrightIT extends AbstractBasePlaywrightIT {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestChatModel chatModel;

    @Override
    public String getUrl() {
        return "http://localhost:" + port;
    }

    @BeforeEach
    void resetChatModelBetweenTests() {
        chatModel.reset();
    }

    // Capture a full-page screenshot to target/playwright-screenshots/ when
    // an IT fails or aborts. The CI workflow uploads this directory as part
    // of the test-reports artifact so the rendered page can be inspected
    // without re-running locally.
    @RegisterExtension
    final TestWatcher screenshotOnFailure = new TestWatcher() {
        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            capture(context, "failed");
        }

        @Override
        public void testAborted(ExtensionContext context, Throwable cause) {
            capture(context, "aborted");
        }

        private void capture(ExtensionContext context, String label) {
            Page page = MisePlaywrightIT.this.page;
            if (page == null) {
                return;
            }
            try {
                Path dir = Paths.get("target", "playwright-screenshots");
                Files.createDirectories(dir);
                String name = context.getRequiredTestClass().getSimpleName()
                        + "_" + context.getRequiredTestMethod().getName()
                        + "_" + label + ".png";
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(dir.resolve(name))
                        .setFullPage(true));
            } catch (Throwable ignored) {
                // Best-effort; never fail the test because of capture failure.
            }
        }
    };
}
