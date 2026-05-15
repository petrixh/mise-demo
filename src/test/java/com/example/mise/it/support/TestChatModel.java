package com.example.mise.it.support;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * Deterministic stand-in for the autoconfigured OpenAI {@link ChatModel}, used by Playwright ITs
 * so chat round-trip tests don't depend on the live Qwen endpoint.
 *
 * <p>Tests push expected assistant replies onto the queue with {@link #queueReply(String)} before
 * triggering UI input; each {@link #call(Prompt)} / {@link #stream(Prompt)} pops one off and
 * wraps it in a single-generation {@link ChatResponse}. Streaming emits the full text as one
 * chunk — Vaadin's {@code AIOrchestrator} renders that correctly without needing multiple chunks
 * for the IT-layer assertions we care about.
 */
public class TestChatModel implements ChatModel {

    private final Deque<String> queuedReplies = new ConcurrentLinkedDeque<>();
    private final Deque<Prompt> receivedPrompts = new ConcurrentLinkedDeque<>();

    public void queueReply(String text) {
        queuedReplies.add(text);
    }

    public void reset() {
        queuedReplies.clear();
        receivedPrompts.clear();
    }

    public List<Prompt> receivedPrompts() {
        return List.copyOf(receivedPrompts);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return buildResponse(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(buildResponse(prompt));
    }

    private ChatResponse buildResponse(Prompt prompt) {
        receivedPrompts.add(prompt);
        String text = queuedReplies.pollFirst();
        if (text == null) {
            text = "(no reply queued — TestChatModel default)";
        }
        // Set finish_reason=stop so Vaadin's SpringAILLMProvider sees a terminal chunk
        // (otherwise it logs a "LLM stream ended without observing a terminal chunk" warning).
        ChatGenerationMetadata terminal = ChatGenerationMetadata.builder().finishReason("stop").build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), terminal)));
    }
}
