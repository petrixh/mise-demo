package com.example.mise.ui.reports;

import com.vaadin.flow.component.ai.orchestrator.AIController;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * UC-012: composite {@link AIController} for the three Reports widgets.
 *
 * <p>{@code AIOrchestrator} accepts exactly <b>one</b> controller (a later
 * {@code withController} replaces the earlier one), so the three real
 * controllers are wrapped into this single one. Tool assembly:
 *
 * <ul>
 *   <li>the leaderboard {@code GridAIController}'s tools pass through as-is
 *       (only one grid — no collisions);</li>
 *   <li>the two {@code ChartAIController}s would collide on every tool name,
 *       so their chart tools are namespaced {@code trend_*} / {@code category_*}
 *       (the pattern {@code ToolSpec}'s javadoc recommends) with the widget
 *       named in the description; any {@code chartId} argument is stripped
 *       before delegating, since each inner controller manages a single chart;</li>
 *   <li>duplicated helper tools ({@code get_database_schema},
 *       {@code get_plot_options_schema}, the per-controller instruction tools)
 *       are kept once.</li>
 * </ul>
 *
 * <p>{@code onRequest}/{@code onResponse} fan out to all three wrapped
 * controllers — that is what applies their deferred renders at the end of a
 * successful LLM turn.
 */
class ReportsAIController implements AIController {

    /** Tool names that may appear in several wrapped controllers; first one wins. */
    private static final Set<String> SHARED_TOOLS =
            Set.of("get_database_schema", "get_plot_options_schema");

    /** Per-controller workflow/instruction tools, replaced by one combined text. */
    private static final Set<String> INSTRUCTION_TOOLS =
            Set.of("get_chart_instructions", "get_grid_instructions");

    private static final String INSTRUCTIONS = """
            Reports widgets workflow. Three widgets exist, each with its own tools:
              - trendChart (chart): trend_get_chart_state, trend_update_chart_data_source, trend_update_chart_configuration
              - categoryChart (chart): category_get_chart_state, category_update_chart_data_source, category_update_chart_configuration
              - leaderboard (data grid): get_grid_state, update_grid_data

            For every widget request, complete it in a SINGLE response:
            1. Call get_database_schema() to learn the exact tables and columns
            2. Call the matching get_*_state() to see what's configured
            3. Call the matching update tool(s) with SQL SELECT queries using only schema columns

            Charts: data comes ONLY from *_update_chart_data_source queries; visual appearance
            (type, axes, styling) ONLY from *_update_chart_configuration. Never put series data
            in a configuration. get_plot_options_schema(chartType) lists styling properties.
            Grid: update_grid_data needs explicit columns with human-readable AS aliases; no
            SELECT *, no LIMIT/OFFSET.
            """;

    private final List<AIController> delegates;
    private final List<NamedDelegate> toolSources;
    /** Runs after every turn's delegate fan-out, on the UI thread — see onResponse. */
    private final Runnable afterResponse;

    /** A wrapped controller plus the namespace prefix for its colliding tools ("" = pass through). */
    private record NamedDelegate(AIController controller, String prefix, String widgetLabel) {}

    ReportsAIController(AIController trendChart, AIController categoryChart, AIController leaderboard,
                        Runnable afterResponse) {
        this.afterResponse = afterResponse;
        this.delegates = List.of(trendChart, categoryChart, leaderboard);
        this.toolSources = List.of(
                new NamedDelegate(trendChart, "trend", "trendChart (weekly cost trend)"),
                new NamedDelegate(categoryChart, "category", "categoryChart (cost by category)"),
                new NamedDelegate(leaderboard, "", "leaderboard (per-meal grid)"));
    }

    @Override
    public List<LLMProvider.ToolSpec> getTools() {
        var tools = new ArrayList<LLMProvider.ToolSpec>();
        tools.add(instructionsTool());
        var seenShared = new java.util.HashSet<String>();
        for (NamedDelegate source : toolSources) {
            for (LLMProvider.ToolSpec spec : source.controller().getTools()) {
                String name = spec.getName();
                if (INSTRUCTION_TOOLS.contains(name)) {
                    continue; // replaced by the combined instructions above
                }
                if (SHARED_TOOLS.contains(name)) {
                    if (seenShared.add(name)) {
                        tools.add(spec);
                    }
                    continue;
                }
                tools.add(source.prefix().isEmpty() ? spec : namespaced(spec, source));
            }
        }
        return tools;
    }

    private LLMProvider.ToolSpec namespaced(LLMProvider.ToolSpec spec, NamedDelegate source) {
        return new LLMProvider.ToolSpec() {
            @Override
            public String getName() {
                return source.prefix() + "_" + spec.getName();
            }

            @Override
            public String getDescription() {
                return "Targets the " + source.widgetLabel() + " widget ONLY. "
                        + spec.getDescription();
            }

            @Override
            public String getParametersSchema() {
                return spec.getParametersSchema();
            }

            @Override
            public String execute(JsonNode arguments) {
                // The inner controller manages exactly one chart; a chartId the
                // model might still pass would not match its internal id.
                if (arguments instanceof ObjectNode obj) {
                    obj.remove("chartId");
                }
                return spec.execute(arguments);
            }
        };
    }

    private LLMProvider.ToolSpec instructionsTool() {
        return new LLMProvider.ToolSpec() {
            @Override
            public String getName() {
                return "get_reports_widget_instructions";
            }

            @Override
            public String getDescription() {
                return "Read this before using any Reports widget or database tool. "
                        + "Calling this tool returns these same instructions.\n\n" + INSTRUCTIONS;
            }

            @Override
            public String getParametersSchema() {
                return null;
            }

            @Override
            public String execute(JsonNode arguments) {
                return INSTRUCTIONS;
            }
        };
    }

    @Override
    public void onRequest() {
        delegates.forEach(AIController::onRequest);
    }

    @Override
    public void onResponse(Throwable error) {
        // Fan out so every wrapped controller applies (or discards) its pending
        // state; a failure in one widget's render must not block the others.
        // The first failure is rethrown so the orchestrator surfaces it.
        RuntimeException first = null;
        for (AIController delegate : delegates) {
            try {
                delegate.onResponse(error);
            } catch (RuntimeException e) {
                if (first == null) first = e;
            }
        }
        // Data refresh AFTER the deferred renders — this is the only ordering
        // that is safe: anything scheduled via UI.access from the response
        // listener can run before the orchestrator even enqueues this method
        // (access commands from an unlocked thread may execute inline), and a
        // restoreState() there would clear the delegates' pending queries
        // before they were applied (observed live 2026-06-10).
        if (afterResponse != null) {
            try {
                afterResponse.run();
            } catch (RuntimeException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }
}
