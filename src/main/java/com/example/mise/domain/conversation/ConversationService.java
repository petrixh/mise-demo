package com.example.mise.domain.conversation;

import com.vaadin.flow.component.ai.common.ChatMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the persisted chat history that the AI orchestrator uses
 * as its long-lived memory. The demo runs single-household, so there is no
 * household filter yet.
 */
@Service
public class ConversationService {

    private final ConversationMessageRepository repository;

    public ConversationService(ConversationMessageRepository repository) {
        this.repository = repository;
    }

    /** Load the full history in chronological order, mapped to Vaadin's ChatMessage. */
    @Transactional(readOnly = true)
    public List<ChatMessage> loadHistory() {
        var rows = repository.findAllByOrderByCreatedAtAscIdAsc();
        var out = new ArrayList<ChatMessage>(rows.size());
        for (var row : rows) {
            out.add(new ChatMessage(
                    toVaadinRole(row.getRole()),
                    row.getContent(),
                    row.getMessageId(),
                    row.getCreatedAt()));
        }
        return out;
    }

    /**
     * Reconcile the orchestrator's current history against the DB by appending
     * anything beyond what we have already persisted. We can't use messageId
     * for dedupe because the orchestrator hands the assistant {@code ChatMessage}
     * a null messageId (see {@code AIOrchestrator#streamResponseToMessage}), so
     * we use list-order instead: the orchestrator's history is append-only and
     * matches the DB up to {@code repository.count()}.
     */
    @Transactional
    public void syncFromOrchestrator(List<ChatMessage> orchestratorHistory) {
        long persisted = repository.count();
        for (int i = (int) persisted; i < orchestratorHistory.size(); i++) {
            var msg = orchestratorHistory.get(i);
            var row = new ConversationMessage();
            row.setMessageId(msg.messageId()); // may be null for assistant turns
            row.setRole(toEntityRole(msg.role()));
            row.setContent(msg.content() == null ? "" : msg.content());
            repository.save(row);
        }
    }

    private static ConversationMessage.Role toEntityRole(ChatMessage.Role r) {
        return switch (r) {
            case USER -> ConversationMessage.Role.USER;
            case ASSISTANT -> ConversationMessage.Role.ASSISTANT;
        };
    }

    private static ChatMessage.Role toVaadinRole(ConversationMessage.Role r) {
        return switch (r) {
            case USER -> ChatMessage.Role.USER;
            case ASSISTANT -> ChatMessage.Role.ASSISTANT;
            case SYSTEM, TOOL -> ChatMessage.Role.ASSISTANT; // not produced today; map for safety
        };
    }
}
