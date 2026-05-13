package com.example.mise.domain.conversation;

import com.vaadin.flow.component.ai.common.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ConversationService, including UC-008 rolling-window history (BR-06).
 */
@SpringBootTest
@Transactional
class ConversationServiceTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationMessageRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    // ── loadHistory() (default overload) ─────────────────────────────────────

    @Test
    void loadHistory_empty_returnsEmptyList() {
        List<ChatMessage> history = conversationService.loadHistory();
        assertThat(history).isEmpty();
    }

    @Test
    void loadHistory_fewMessages_returnsAllChronologically() {
        seedMessages(5);

        List<ChatMessage> history = conversationService.loadHistory();

        assertThat(history).hasSize(5);
        // Content was seeded as "msg-0", "msg-1", …
        assertThat(history.get(0).content()).isEqualTo("msg-0");
        assertThat(history.get(4).content()).isEqualTo("msg-4");
    }

    // ── loadHistory(int rollingWindow) ────────────────────────────────────────

    @Test
    void loadHistory_withWindow_fewerMessagesThanWindow_returnsAll() {
        seedMessages(10);

        List<ChatMessage> history = conversationService.loadHistory(50);

        // No breadcrumb; just the 10 messages
        assertThat(history).hasSize(10);
        assertThat(history.get(0).content()).isEqualTo("msg-0");
        assertThat(history.get(9).content()).isEqualTo("msg-9");
    }

    @Test
    void loadHistory_withWindow_exactlyWindowMessages_returnsAllNoBreadcrumb() {
        seedMessages(50);

        List<ChatMessage> history = conversationService.loadHistory(50);

        assertThat(history).hasSize(50);
        // First message is real, not a breadcrumb
        assertThat(history.get(0).content()).isEqualTo("msg-0");
    }

    @Test
    void loadHistory_100MessagesWindow50_returnsLast50WithBreadcrumb() {
        seedMessages(100);

        List<ChatMessage> history = conversationService.loadHistory(50);

        // 50 real messages + 1 breadcrumb = 51 total
        assertThat(history).hasSize(51);

        // First entry must be the synthetic breadcrumb
        ChatMessage breadcrumb = history.get(0);
        assertThat(breadcrumb.content()).contains("50 earlier turns omitted");
        assertThat(breadcrumb.content()).contains("rolling window=50");

        // The 50 real messages should be the LAST 50 (msg-50 … msg-99)
        assertThat(history.get(1).content()).isEqualTo("msg-50");
        assertThat(history.get(50).content()).isEqualTo("msg-99");
    }

    @Test
    void loadHistory_withWindow_breadcrumbCountAccurate() {
        seedMessages(75);

        List<ChatMessage> history = conversationService.loadHistory(30);

        // 30 real + 1 breadcrumb = 31
        assertThat(history).hasSize(31);
        assertThat(history.get(0).content()).contains("45 earlier turns omitted");
        assertThat(history.get(0).content()).contains("rolling window=30");
    }

    @Test
    void loadHistory_invalidWindowSize_throwsIllegalArgument() {
        assertThatThrownBy(() -> conversationService.loadHistory(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversationService.loadHistory(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Default overload matches old behaviour ────────────────────────────────

    @Test
    void loadHistory_default_behavesLikeWindowOf50ForSmallHistory() {
        seedMessages(20);

        List<ChatMessage> defaultResult = conversationService.loadHistory();
        List<ChatMessage> explicit50 = conversationService.loadHistory(50);

        assertThat(defaultResult).hasSize(explicit50.size());
        for (int i = 0; i < defaultResult.size(); i++) {
            assertThat(defaultResult.get(i).content()).isEqualTo(explicit50.get(i).content());
        }
    }

    // ── syncFromOrchestrator ──────────────────────────────────────────────────

    @Test
    void syncFromOrchestrator_stampsViewContext() {
        var messages = List.of(
                new ChatMessage(ChatMessage.Role.USER, "hello", "id-1", null),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "hi there", null, null)
        );

        conversationService.syncFromOrchestrator(messages, ConversationMessage.ViewContext.SHOPPING);

        var saved = repository.findAllByOrderByCreatedAtAscIdAsc();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getViewContext()).isEqualTo(ConversationMessage.ViewContext.SHOPPING);
        assertThat(saved.get(1).getViewContext()).isEqualTo(ConversationMessage.ViewContext.SHOPPING);
    }

    @Test
    void syncFromOrchestrator_doesNotDuplicateExistingMessages() {
        var messages = List.of(
                new ChatMessage(ChatMessage.Role.USER, "hello", "id-1", null)
        );
        conversationService.syncFromOrchestrator(messages, ConversationMessage.ViewContext.PLAN);

        // Call again with same list — should not add duplicates
        conversationService.syncFromOrchestrator(messages, ConversationMessage.ViewContext.PLAN);

        assertThat(repository.count()).isEqualTo(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Seeds {@code count} USER messages with content "msg-0", "msg-1", … into the DB. */
    private void seedMessages(int count) {
        for (int i = 0; i < count; i++) {
            var msg = new ConversationMessage();
            msg.setRole(ConversationMessage.Role.USER);
            msg.setContent("msg-" + i);
            repository.save(msg);
        }
    }
}
