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

    private static final String SYSTEM_PROMPT = """
            You are Mise, a warm and pragmatic assistant for a home cook's weekly meal planning.
            Be brief. Do not narrate what you are about to do — do it and report concisely.
            Never invent prices, calorie counts, or quantities; if you don't have the data, say so.
            When answering questions about the meal plan, use the available tools to look up
            real data rather than guessing.
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
