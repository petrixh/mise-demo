package com.example.mise.ai;

import com.vaadin.flow.component.ai.common.AIAttachment;
import com.vaadin.flow.component.ai.common.ChatMessage;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link LLMProvider} decorator that drops leading assistant turn(s) from the history sent
 * to the model, guaranteeing a user-first message sequence.
 *
 * <p>Strict chat templates — notably qwen on LM Studio — reject a model message sequence that
 * begins with an assistant turn before any user turn, failing the call with
 * {@code "Error rendering prompt with jinja template: No user query found in messages."}.
 * (llama.cpp tolerates it, so the bug is endpoint-specific.) Mise's persisted history is
 * <em>structurally</em> assistant-led in two ways, both of which would otherwise trip this:
 * <ul>
 *   <li>the onboarding opener is seeded as an assistant message (for display) and persisted,
 *       so it is the first item every later view loads; and</li>
 *   <li>{@code ConversationService.loadHistory} prepends an assistant-role "earlier conversation
 *       summary" breadcrumb once the conversation exceeds the rolling window.</li>
 * </ul>
 *
 * <p>Stripping leading assistant turns is safe to apply globally: real conversation history only
 * ever has assistant turns <em>after</em> the first user turn, so this removes synthetic openers
 * (greeting / breadcrumb) only — never a genuine exchange. Display and persistence are unaffected;
 * only the model-bound history passed to {@link #setHistory} is trimmed.
 *
 * <p>Wired in {@link com.example.mise.config.AIConfig} between {@link CancellableLLMProvider} and
 * the underlying {@code SpringAILLMProvider}, so it applies to every {@code AIOrchestrator}
 * (onboarding and the main chat dock alike).
 */
public final class UserFirstHistoryProvider implements LLMProvider {

    private final LLMProvider delegate;

    public UserFirstHistoryProvider(LLMProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public Flux<String> stream(LLMRequest request) {
        return delegate.stream(request);
    }

    @Override
    public void setHistory(List<ChatMessage> history, Map<String, List<AIAttachment>> attachments) {
        var trimmed = new ArrayList<>(history);
        while (!trimmed.isEmpty() && trimmed.get(0).role() == ChatMessage.Role.ASSISTANT) {
            trimmed.remove(0);
        }
        delegate.setHistory(trimmed, attachments);
    }
}
