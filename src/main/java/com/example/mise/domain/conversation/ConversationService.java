package com.example.mise.domain.conversation;

import com.vaadin.flow.component.ai.common.ChatMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads and writes the persisted chat history that the AI orchestrator uses
 * as its long-lived memory. The demo runs single-household, so there is no
 * household filter yet.
 */
@Service
public class ConversationService {

    /** Default rolling-window size (BR-06). */
    public static final int DEFAULT_ROLLING_WINDOW = 50;

    private final ConversationMessageRepository repository;

    public ConversationService(ConversationMessageRepository repository) {
        this.repository = repository;
    }

    /**
     * Load history with the default rolling window ({@value #DEFAULT_ROLLING_WINDOW} messages).
     * If there are more messages in the DB, a synthetic breadcrumb is prepended.
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> loadHistory() {
        return loadHistory(DEFAULT_ROLLING_WINDOW);
    }

    /**
     * Load history with a configurable rolling window (BR-06).
     * When the DB has more than {@code rollingWindow} messages, only the last
     * {@code rollingWindow} are returned, preceded by a synthetic ASSISTANT
     * breadcrumb: {@code "Earlier conversation summary: {N} earlier turns omitted; rolling window={W}"}.
     *
     * @param rollingWindow maximum number of real messages to return; must be &gt; 0
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> loadHistory(int rollingWindow) {
        if (rollingWindow <= 0) throw new IllegalArgumentException("rollingWindow must be > 0");

        long total = repository.count();

        if (total <= rollingWindow) {
            // All messages fit; return full history.
            var rows = repository.findAllByOrderByCreatedAtAscIdAsc();
            return toVaadinList(rows);
        }

        // More messages than the window: return last N with a breadcrumb at the head.
        var page = PageRequest.of(0, rollingWindow);
        var rows = repository.findLastN(page);
        // findLastN returns newest-first; reverse for chronological order.
        Collections.reverse(rows);

        long omitted = total - rollingWindow;
        var breadcrumb = new ChatMessage(
                ChatMessage.Role.ASSISTANT,
                "Earlier conversation summary: " + omitted + " earlier turns omitted; rolling window=" + rollingWindow,
                null,
                Instant.EPOCH);

        var out = new ArrayList<ChatMessage>(rows.size() + 1);
        out.add(breadcrumb);
        out.addAll(toVaadinList(rows));
        return out;
    }

    private List<ChatMessage> toVaadinList(List<ConversationMessage> rows) {
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
        syncFromOrchestrator(orchestratorHistory, null);
    }

    /**
     * Same as {@link #syncFromOrchestrator(List)} but stamps newly persisted rows
     * with the given {@link ConversationMessage.ViewContext}.
     *
     * @param viewContext may be {@code null} (rows will have no context tag)
     */
    @Transactional
    public void syncFromOrchestrator(List<ChatMessage> orchestratorHistory,
                                     ConversationMessage.ViewContext viewContext) {
        long persisted = repository.count();
        for (int i = (int) persisted; i < orchestratorHistory.size(); i++) {
            var msg = orchestratorHistory.get(i);
            var row = new ConversationMessage();
            row.setMessageId(msg.messageId()); // may be null for assistant turns
            row.setRole(toEntityRole(msg.role()));
            row.setContent(msg.content() == null ? "" : msg.content());
            row.setViewContext(viewContext);
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
