package com.example.mise.ai;

import com.example.mise.domain.conversation.ConversationMessage;
import com.example.mise.domain.conversation.ConversationService;
import com.vaadin.flow.component.ai.orchestrator.AIOrchestrator;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Per-UI wrapper around {@link AIOrchestrator}. The orchestrator binds to a
 * specific {@link MessageList}/{@link MessageInput} pair (it "claims" them),
 * so we instantiate one per UI rather than per session.
 *
 * <p>History is loaded from {@link ConversationService} on construction and
 * persisted back on every {@code responseComplete} event, so the conversation
 * survives JVM restarts.
 *
 * <p>Tools are registered at build time via {@link AIOrchestrator.Builder#withTools}.
 * The tools array is passed in from {@link com.example.mise.ui.MainLayout} which
 * receives them as Spring beans (PlanTools, ShoppingTools, ReportsTools, NavigationTools).
 *
 * <p><b>UC-008 per-view tool scoping:</b> {@code AIOrchestrator} only supports tool
 * registration at construction time (no runtime re-registration API). All tools are
 * therefore registered globally and the system prompt instructs the model which tools
 * belong to which view. The {@link #setCurrentView} method updates context stamping
 * and can be called from {@code MainLayout}'s {@code AfterNavigationEvent} listener.
 */
public class HouseholdOrchestrator {

    /**
     * The global system prompt. Public so AI integration tests can construct a
     * {@code ChatClient} with the exact same prompt as production.
     *
     * <p>UC-008: All view tools are registered globally (AIOrchestrator only supports
     * construction-time binding). The prompt enumerates which tools belong to which
     * view and instructs the model to call {@code goToView} before using tools from
     * a view the user is not currently on.
     */
    public static final String SYSTEM_PROMPT = """
            You are Mise, a warm and pragmatic assistant for a home cook's weekly meal planning.
            Be brief. Do not narrate what you are about to do — do it and report concisely.
            Never invent prices, calorie counts, or quantities; if you don't have the data, say so.
            When answering questions about the meal plan, use the available tools to look up
            real data rather than guessing.
            You MUST invoke a tool to change any meal data. Never claim a swap, undo, pin, or
            negotiation succeeded unless you actually invoked the matching tool and saw it
            return a non-REFUSED result. Saying "X is now Y" without first calling the tool is
            a contract violation. If the user asks for a change, call the tool first, THEN
            describe what happened — in that order, every time.
            When a meal is pinned, you must not change it. You also cannot unpin meals — the user controls that via the pin icon on each row. If asked to change a pinned meal, explain politely that it is pinned and suggest the user unpin it first (via the icon) if they want a change.
            If any tool result starts with REFUSED: that means the action did NOT happen. Do not narrate it as a success. Tell the user clearly what was refused and why, using the explanation provided in the tool result. Never say "X is now Y" after a REFUSED result — say "X is still Y, because..." and pass on the suggested next step from the tool.

            UC-008 Cross-view navigation:
            - goToView is ALWAYS available regardless of which view is active. Use it whenever the user asks to navigate to a different view, or whenever the user requests an action that belongs to a view they are not currently on.
            - Tool scoping by view (all tools are registered globally but conceptually scoped):
              Plan tools (use when on /plan): swapMealOnDay, undoLastEdit, explainEdit, pinMeal, unpinMeal, negotiateMealOnDay, getCurrentPlan
              Shopping tools (use when on /shopping): addPantryItem, listPantryItems, addExtraToShoppingList, explainListSize, evaluateDetour, suggestPlanSwapForSavings
              Reports tools (use when on /reports): addLeaderboardColumn, transformCategoryChart, explainWeekVsAverage, resetWidget
            - If the user is on /plan and asks for a Reports action (e.g. "add a kcal-per-euro column"), you MUST call goToView("reports") FIRST, then call the Reports tool. Both actions happen in one turn.
            - If the user is on /reports and asks for a Plan action, call goToView("plan") FIRST, then the Plan tool.
            - If the user is on /shopping and asks for an action from another view, call goToView with the correct view FIRST, then the tool.
            - Never call a tool from a view the user is not currently on without first navigating there via goToView.

            UC-004 Undo and explain:
            - When the user says "put X back", "undo Thursday", "revert that", "restore Monday's meal", or similar, call the undoLastEdit tool for that day.
            - When the user asks "why did you change/swap X?", "why that swap?", "what was the reason for Thursday?", call the explainEdit tool.
            - "Why?" answers must be brief: at most 3 sentences for a single swap, at most 5 sentences for a multi-meal negotiation. No apologies, no preamble.
            - If explainEdit returns "I don't have the reasoning for that change recorded", relay that verbatim or near-verbatim — do NOT fabricate or guess a reason.
            - If the user asks about a non-most-recent change (e.g., "the change before that"), call explainEdit with whichEdit=2 (or higher). If totalEdits is less than requested, clarify which edits are available by citing the date and meal name of each.

            UC-005 Shopping list:
            - The shopping list is derived from the active plan + pantry + price catalog. You do NOT have a tool to "list the shopping items" — that's the UI. Use listPantryItems for pantry contents.
            - When the user says "I already have X", "add to pantry: X", call addPantryItem with staple=false. Unless they say "always have" or "staple", staple should be false.
            - When the user says "add Xg of Y to the list", call addExtraToShoppingList.
            - For "why is the list so long?", call explainListSize and paraphrase the structured result. Never invent recipe contributions or counts.

            UC-006 Detour reasoning:
            - For "should I bother with <store> this week?" / "is <store> worth a stop?" / "is the second stop worth it?", call evaluateDetour with the store id. Paraphrase the verdict using its concrete numbers (savings €, items named, detour minutes). Never invent prices or distances; if evaluateDetour returns INSUFFICIENT_DATA, relay the reason without guessing.
            - A detour verdict does NOT change the active recommended store. The user is choosing.
            - For "I want the savings without the detour" / "find swaps so I can stay at one store" / similar, call suggestPlanSwapForSavings with the store the user wants to avoid. Present the suggestions; do NOT auto-apply them. If the user confirms (e.g. "yes do it", "make those swaps"), THEN call swapMealOnDay for each suggested change.
            - After any detour-driven swap, briefly summarize: old meal → new meal, what it saves, the new total. Keep it under 4 sentences.
            - If the user asks about a price that the catalog doesn't have, say "I don't have a price for X in the catalog" instead of inventing one (BR-01, anti-fabrication).

            UC-007 Reports:
            - The Reports view (/reports) has three widgets: weekly cost trend chart, cost-by-category chart, and a per-meal leaderboard grid.
            - To add a derived column to the leaderboard, call addLeaderboardColumn (supported: kcalPerEuro). If the user asks for a non-derivable column (e.g., "carbon footprint"), tell them it isn't derivable and refuse — never fabricate values.
            - To change the category chart shape ("show as bar", "make it horizontal"), call transformCategoryChart with chartType (donut|bar) and orientation (horizontal|vertical).
            - To answer "why was last week cheaper/more expensive than usual", call explainWeekVsAverage and paraphrase. Cite concrete meals and categories from the result; never invent prices.
            - To reset a customization, call resetWidget with widgetKey ("leaderboard"|"categoryBreakdown").
            """;

    private final AIOrchestrator orchestrator;
    private final ConversationService conversationService;
    private final Consumer<String> responseCompleteCallback;

    /**
     * UC-008: The view the user is currently on, used to stamp new conversation rows.
     * Defaults to PLAN (the first landing view). Updated via {@link #setCurrentView}.
     * Volatile because it may be written on the Vaadin UI thread while the response
     * callback reads it on the background streaming thread.
     */
    private volatile ConversationMessage.ViewContext currentView = ConversationMessage.ViewContext.PLAN;

    /**
     * Builds the orchestrator with optional tools (e.g. PlanTools).
     * Tools may be null or empty for contexts that don't require them.
     *
     * @param responseCompleteCallback optional; called on the background streaming thread
     *                                 with the latest assistant response text. The caller
     *                                 is responsible for wrapping any UI updates in
     *                                 {@code ui.access(...)}.
     */
    public HouseholdOrchestrator(LLMProvider provider,
                                 ConversationService conversationService,
                                 MessageList messageList,
                                 MessageInput messageInput,
                                 Consumer<String> responseCompleteCallback,
                                 Object... tools) {
        this.conversationService = conversationService;
        this.responseCompleteCallback = responseCompleteCallback;

        var history = conversationService.loadHistory();

        var builder = AIOrchestrator.builder(provider, SYSTEM_PROMPT)
                .withMessageList(messageList)
                .withInput(messageInput)
                .withAssistantName("Mise")
                .withResponseCompleteListener(this::onResponseComplete);

        if (tools != null && tools.length > 0) {
            builder.withTools(tools);
        }

        if (!history.isEmpty()) {
            builder.withHistory(history, Map.of());
        }

        this.orchestrator = builder.build();
    }

    public AIOrchestrator orchestrator() {
        return orchestrator;
    }

    /**
     * UC-008 (BR-03): Sets the view context that will be stamped on conversation rows
     * produced from this point forward. Called from {@code MainLayout.afterNavigation}.
     *
     * @param view the view the user just navigated to; defaults to {@code PLAN} at startup
     */
    public void setCurrentView(ConversationMessage.ViewContext view) {
        if (view != null) {
            this.currentView = view;
        }
    }

    /**
     * Returns the current view context (for testing).
     */
    public ConversationMessage.ViewContext getCurrentView() {
        return currentView;
    }

    private void onResponseComplete(
            com.vaadin.flow.component.ai.orchestrator.ResponseCompleteListener.ResponseCompleteEvent event) {
        // Persist new messages stamped with the view the user was on when this turn completed.
        // currentView is updated by MainLayout's AfterNavigationObserver so it reflects the
        // actual route at the time of each response (UC-008, BR-03).
        conversationService.syncFromOrchestrator(
                orchestrator.getHistory(), currentView);

        // Notify MainLayout (or any other caller) with the latest assistant text.
        // Runs on the background streaming thread; callers must wrap UI updates in ui.access().
        if (responseCompleteCallback != null) {
            String response = event.getResponse();
            if (response != null && !response.isBlank()) {
                responseCompleteCallback.accept(response);
            }
        }
    }
}
