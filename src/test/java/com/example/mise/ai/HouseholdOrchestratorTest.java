package com.example.mise.ai;

import com.example.mise.domain.conversation.ConversationMessage;
import com.example.mise.domain.conversation.ConversationService;
import com.vaadin.flow.component.ai.common.ChatMessage;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UC-008 view-context stamping in {@link HouseholdOrchestrator}.
 *
 * <p>These tests focus on the mutable {@code currentView} field and confirm that
 * {@link HouseholdOrchestrator#setCurrentView} updates the value that would be
 * passed to {@link ConversationService#syncFromOrchestrator} when a response
 * completes.
 *
 * <p>Full orchestrator construction requires a Vaadin UI context (MessageList,
 * MessageInput live in a Component tree). The {@code getCurrentView} accessor
 * lets us verify the field without spinning up the full Vaadin runtime.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdOrchestratorTest {

    @Mock
    private LLMProvider llmProvider;

    @Mock
    private ConversationService conversationService;

    // ── currentView tracking ────────────────────────────────────────────────

    @Test
    void setCurrentView_plan_updatesCurrent() {
        when(conversationService.loadHistory()).thenReturn(List.of());

        var orchestrator = buildOrchestrator();
        orchestrator.setCurrentView(ConversationMessage.ViewContext.PLAN);

        assertThat(orchestrator.getCurrentView()).isEqualTo(ConversationMessage.ViewContext.PLAN);
    }

    @Test
    void setCurrentView_shopping_updatesCurrent() {
        when(conversationService.loadHistory()).thenReturn(List.of());

        var orchestrator = buildOrchestrator();
        orchestrator.setCurrentView(ConversationMessage.ViewContext.SHOPPING);

        assertThat(orchestrator.getCurrentView()).isEqualTo(ConversationMessage.ViewContext.SHOPPING);
    }

    @Test
    void setCurrentView_reports_updatesCurrent() {
        when(conversationService.loadHistory()).thenReturn(List.of());

        var orchestrator = buildOrchestrator();
        orchestrator.setCurrentView(ConversationMessage.ViewContext.REPORTS);

        assertThat(orchestrator.getCurrentView()).isEqualTo(ConversationMessage.ViewContext.REPORTS);
    }

    @Test
    void defaultCurrentView_isPlan() {
        when(conversationService.loadHistory()).thenReturn(List.of());

        var orchestrator = buildOrchestrator();

        assertThat(orchestrator.getCurrentView()).isEqualTo(ConversationMessage.ViewContext.PLAN);
    }

    @Test
    void setCurrentView_null_doesNotChangeCurrentView() {
        when(conversationService.loadHistory()).thenReturn(List.of());

        var orchestrator = buildOrchestrator();
        orchestrator.setCurrentView(ConversationMessage.ViewContext.REPORTS);
        // Passing null must not change the already-set context
        orchestrator.setCurrentView(null);

        assertThat(orchestrator.getCurrentView()).isEqualTo(ConversationMessage.ViewContext.REPORTS);
    }

    @Test
    void setCurrentView_canBeChanged_multipleTimesInSequence() {
        when(conversationService.loadHistory()).thenReturn(List.of());

        var orchestrator = buildOrchestrator();
        orchestrator.setCurrentView(ConversationMessage.ViewContext.SHOPPING);
        orchestrator.setCurrentView(ConversationMessage.ViewContext.REPORTS);
        orchestrator.setCurrentView(ConversationMessage.ViewContext.PLAN);

        assertThat(orchestrator.getCurrentView()).isEqualTo(ConversationMessage.ViewContext.PLAN);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a {@link HouseholdOrchestrator} with minimal stubs.
     *
     * <p>MessageList and MessageInput are Vaadin components — they do not require
     * an attached UI just to be instantiated (they are lightweight Java objects).
     * The AIOrchestrator builder attaches them at build time; since we are not
     * testing the full streaming behaviour here, the orchestrator can be constructed
     * without a running Vaadin session.
     */
    private HouseholdOrchestrator buildOrchestrator() {
        var messageList = new MessageList();
        var messageInput = new MessageInput();
        return new HouseholdOrchestrator(
                llmProvider,
                conversationService,
                messageList,
                messageInput,
                null /* no response callback needed */
                /* no tools */
        );
    }
}
