package com.example.mise.ai.tools;

import com.vaadin.flow.component.UI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * UC-008 (BR-04, BR-05): Tool that navigates the user's UI to a different view.
 *
 * <p>The tool needs access to a Vaadin {@link UI} instance so it can call
 * {@code ui.access(() -> ui.navigate(...))}. Since the tool runs on the orchestrator's
 * streaming background thread, we cannot use {@code UI.getCurrent()}. Instead, the UI
 * is provided at construction time via a supplier (typically a method reference to
 * {@code UI.getCurrent()} captured on the Vaadin UI thread in {@code MainLayout}).
 *
 * <p>This is a {@code @Component} bean; the {@code uiSupplier} is injected after
 * construction by {@code MainLayout} via {@link #setUiSupplier(Supplier)}.
 *
 * <p><b>Tool scoping (BR-02):</b> {@code goToView} is always available regardless of the
 * active view. Plan/Shopping/Reports tools are registered globally on the orchestrator
 * and the system prompt directs the model to call {@code goToView} before using tools
 * that conceptually belong to a different view.
 *
 * <p><b>Testability:</b> The actual navigation is delegated to a {@link NavigationExecutor}
 * which can be replaced in tests without needing a real Vaadin UI instance.
 */
@Component
public class NavigationTools {

    private static final Logger log = LoggerFactory.getLogger(NavigationTools.class);

    private static final Map<String, String> VIEW_ROUTES = Map.of(
            "plan",     "plan",
            "shopping", "shopping",
            "reports",  "reports"
    );

    /**
     * Functional interface that performs the actual navigation given a route string.
     * The default implementation calls {@code ui.access(() -> ui.navigate(route))};
     * tests can substitute a simpler recording implementation.
     */
    @FunctionalInterface
    public interface NavigationExecutor {
        void navigate(String route);
    }

    /**
     * Supplier of the current {@link UI}. Set by {@code MainLayout} once the component
     * is attached. Stored in an AtomicReference because {@code MainLayout} writes from
     * the Vaadin UI thread and the tool may read from a background thread.
     */
    private final AtomicReference<Supplier<UI>> uiSupplierRef = new AtomicReference<>();

    /**
     * Optional override executor — used in tests to avoid the real Vaadin UI.
     * When set, the UI supplier is ignored.
     */
    private final AtomicReference<NavigationExecutor> executorOverride = new AtomicReference<>();

    /**
     * Called by {@code MainLayout} after construction to wire in the Vaadin UI reference.
     *
     * @param uiSupplier a supplier that returns the current {@link UI}
     */
    public void setUiSupplier(Supplier<UI> uiSupplier) {
        this.uiSupplierRef.set(uiSupplier);
    }

    /**
     * For testing only: replaces the navigation executor so tests do not need a real UI.
     *
     * @param executor receives the route string when {@code goToView} is called
     */
    public void setNavigationExecutorForTesting(NavigationExecutor executor) {
        this.executorOverride.set(executor);
    }

    /**
     * Navigate the user's UI to a different view.
     * Valid views: {@code plan} (the weekly meal plan), {@code shopping} (the shopping list),
     * {@code reports} (the trend/leaderboard reports).
     * Use this when the user asks for something that lives on a different view
     * (e.g., "go to reports and add a column").
     *
     * @param view one of: plan, shopping, reports
     * @return a short status string confirming the navigation (the model paraphrases this)
     */
    @Tool(description = "Navigate the user's UI to a different view. Valid views: 'plan' (the weekly meal plan), 'shopping' (the shopping list), 'reports' (the trend/leaderboard reports). Use this when the user asks for something that lives on a different view (e.g., 'go to reports and add a column').")
    public String goToView(
            @ToolParam(description = "One of: plan, shopping, reports") String view) {
        if (view == null || view.isBlank()) {
            return "REFUSED: view name must not be blank. Valid values: plan, shopping, reports.";
        }
        String route = VIEW_ROUTES.get(view.trim().toLowerCase());
        if (route == null) {
            return "REFUSED: '" + view + "' is not a valid view. Valid values: plan, shopping, reports.";
        }

        NavigationExecutor executor = executorOverride.get();
        if (executor == null) {
            // Production path: use the real UI supplier
            var supplierRef = uiSupplierRef.get();
            if (supplierRef == null) {
                log.warn("NavigationTools.goToView called but no UI supplier registered");
                return "REFUSED: navigation is not available in this context.";
            }
            UI ui = supplierRef.get();
            if (ui == null) {
                log.warn("NavigationTools.goToView: UI supplier returned null");
                return "REFUSED: no active browser session found.";
            }
            ui.access(() -> ui.navigate(route));
        } else {
            // Test/override path
            executor.navigate(route);
        }

        log.debug("NavigationTools.goToView: navigated to /{}", route);
        return "Navigated to /" + route + ".";
    }
}
