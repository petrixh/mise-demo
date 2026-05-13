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
 * receives them as Spring beans (PlanTools, future ShoppingTools, etc.).
 */
public class HouseholdOrchestrator {

    /**
     * The plan-view system prompt. Public so AI integration tests can construct a
     * {@code ChatClient} with the exact same prompt as production.
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
            """;

    private final AIOrchestrator orchestrator;
    private final ConversationService conversationService;
    private final Consumer<String> responseCompleteCallback;

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

    private void onResponseComplete(
            com.vaadin.flow.component.ai.orchestrator.ResponseCompleteListener.ResponseCompleteEvent event) {
        // After each assistant turn finishes, persist any new messages stamped with PLAN.
        // TODO (UC-008): replace PLAN with a route-aware lookup so rows are stamped with
        //   the actual view the user was on when the turn completed. For now, every turn
        //   from MainLayout's orchestrator is tagged PLAN (the only view using it today).
        conversationService.syncFromOrchestrator(
                orchestrator.getHistory(), ConversationMessage.ViewContext.PLAN);

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
