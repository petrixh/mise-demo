package com.example.mise.domain.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persisted chat message. Mirrors the orchestrator's history so the conversation
 * survives JVM restarts. See spec/datamodel/datamodel.md.
 */
@Entity
@Table(name = "conversation_message")
public class ConversationMessage {

    public enum Role { USER, ASSISTANT, SYSTEM, TOOL }

    public enum ViewContext { PLAN, SHOPPING, REPORTS, ONBOARDING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable identifier from {@code com.vaadin.flow.component.ai.common.ChatMessage#messageId}. */
    @Column(name = "message_id", length = 128, unique = true)
    private String messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String content;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "tool_call_id", length = 128)
    private String toolCallId;

    @Enumerated(EnumType.STRING)
    @Column(name = "view_context", length = 16)
    private ViewContext viewContext;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public ViewContext getViewContext() { return viewContext; }
    public void setViewContext(ViewContext viewContext) { this.viewContext = viewContext; }
    public Instant getCreatedAt() { return createdAt; }
}
