package com.example.mise.ui.onboarding;

import com.example.mise.ai.tools.OnboardingTools;
import com.example.mise.domain.conversation.ConversationMessage;
import com.example.mise.domain.conversation.ConversationService;
import com.example.mise.domain.household.HouseholdService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.ai.common.ChatMessage;
import com.vaadin.flow.component.ai.orchestrator.AIOrchestrator;
import com.vaadin.flow.component.ai.orchestrator.ResponseCompleteListener;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * First-run onboarding view. Shown only when no Household exists.
 * Standalone layout (no MainLayout drawer/nav). Full-height chat panel only.
 *
 * <p>On completion the orchestrator calls {@link OnboardingTools#recordHousehold}
 * which persists the Household, then the response-complete listener navigates to /plan.
 */
@Route("welcome")
@PageTitle("Welcome to Mise")
public class OnboardingView extends VerticalLayout implements BeforeEnterObserver {

    private static final String OPENING_GREETING =
            "Hi — I'll set up your dinners. Quick: how many of you eat at home, " +
            "anything you can't or won't eat, and a rough weekly budget?";

    private static final String SYSTEM_PROMPT = """
            You are Mise, a warm and pragmatic meal planning assistant helping with first-time setup.
            Your goal is to collect three key pieces of information from the user:
            1. Household size (how many people eat at home)
            2. Weekly grocery budget (in EUR)
            3. Food restrictions (allergies that must be blocked, or hated foods to avoid — at least one of these, even if "none")

            Rules:
            - Ask at most 2 clarifying follow-ups if any of these three are missing.
            - Do NOT proceed to call recordHousehold until you have: size, weeklyBudget, AND (allergies OR hatedFoods — empty lists count as "none specified").
            - Total turns before calling the tool: at most 3.
            - Allergies are hard constraints; hated foods are soft (avoid if possible).
            - When you have all required data, call recordHousehold exactly once with what you know. Use empty lists for optional fields you don't know.
            - After the tool confirms success, say exactly one brief confirmation line (e.g. "Done — your week is ready!") and nothing more.
            - Be brief, warm, and practical. No bullet lists in chat, no form fields. Natural conversation only.
            """;

    private final HouseholdService householdService;
    private final ConversationService conversationService;
    private final LLMProvider llmProvider;
    private final OnboardingTools onboardingTools;

    private AIOrchestrator orchestrator;
    private boolean planReady = false;
    private UI ui;

    public OnboardingView(HouseholdService householdService,
                          ConversationService conversationService,
                          LLMProvider llmProvider,
                          OnboardingTools onboardingTools) {
        this.householdService = householdService;
        this.conversationService = conversationService;
        this.llmProvider = llmProvider;
        this.onboardingTools = onboardingTools;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        // Tertiary surface: page background using Aura token (not cross-theme --lumo-base-color)
        addClassName("mise-onboarding-page");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        // BR-01: If Household already exists, skip to /plan
        if (householdService.exists()) {
            event.forwardTo("plan");
            return;
        }
        buildUI();
    }

    private void buildUI() {
        var messageList = new MessageList();
        messageList.setMarkdown(true);
        messageList.setSizeFull();

        var messageInput = new MessageInput();
        messageInput.setWidthFull();

        // Provide the greeting as assistant history so the orchestrator renders it
        // via withHistory (the MessageList surfaces it automatically — no manual addItem).
        var greetingMsg = new ChatMessage(
                ChatMessage.Role.ASSISTANT,
                OPENING_GREETING,
                null,
                Instant.now());
        List<ChatMessage> initialHistory = new ArrayList<>();
        initialHistory.add(greetingMsg);

        // Build the orchestrator with the onboarding system prompt and tools
        var builder = AIOrchestrator.builder(llmProvider, SYSTEM_PROMPT)
                .withMessageList(messageList)
                .withInput(messageInput)
                .withAssistantName("Mise")
                .withTools(onboardingTools)
                .withHistory(initialHistory, Map.of())
                .withResponseCompleteListener(this::onResponseComplete);

        this.orchestrator = builder.build();

        // Chat container: secondary surface panel wrapping the message list + input.
        // The outer view (mise-onboarding-page) sits on the tertiary surface;
        // this container sits on the secondary surface; message bubbles are primary (default).
        // Border-radius ~12px per design-system §"Outer container, panel groupings".
        var chatContainer = new VerticalLayout(messageList, messageInput);
        chatContainer.setHeightFull();
        chatContainer.setWidth("100%");
        chatContainer.setPadding(false); // padding handled by .mise-chat-container CSS
        chatContainer.setSpacing(true);
        chatContainer.expand(messageList);
        chatContainer.addClassName("mise-chat-container");

        // Outer page padding wrapper — full height, centers the chat container horizontally
        setPadding(true);
        setSpacing(false);
        setAlignItems(Alignment.CENTER);
        expand(chatContainer);

        add(chatContainer);

        // Capture UI reference here (on the UI thread) for use in the
        // response-complete listener which runs on a background streaming thread.
        this.ui = UI.getCurrent();

        // Autofocus the input
        messageInput.getElement().callJsFunction("focus");
    }

    private void onResponseComplete(ResponseCompleteListener.ResponseCompleteEvent event) {
        // Persist all new messages with ONBOARDING context
        conversationService.syncFromOrchestrator(
                orchestrator.getHistory(),
                ConversationMessage.ViewContext.ONBOARDING);

        // If recordHousehold was called (household now exists), navigate to /plan
        if (!planReady && householdService.exists()) {
            planReady = true;
            if (ui != null && !ui.isClosing()) {
                ui.access(() -> ui.navigate("plan"));
            }
        }
    }
}
