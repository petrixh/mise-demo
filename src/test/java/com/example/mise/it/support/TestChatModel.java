package com.example.mise.it.support;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * When non-null, {@link #stream(Prompt)} blocks on this latch before emitting.
     * Used by BR-10 tests to observe the in-flight `.ai-working` state — the test
     * pushes a reply, submits, asserts the class is present, then counts the latch
     * down to let the response complete and asserts the class clears.
     */
    private final AtomicReference<CountDownLatch> pauseLatch = new AtomicReference<>();

    public void queueReply(String text) {
        queuedReplies.add(text);
    }

    /**
     * Pause the NEXT streaming response until the returned latch is counted down.
     * Use within a single test only; the latch is one-shot (consumed by the next
     * stream() invocation and cleared so subsequent responses are not blocked).
     */
    public CountDownLatch pauseNextResponse() {
        CountDownLatch latch = new CountDownLatch(1);
        pauseLatch.set(latch);
        return latch;
    }

    public void reset() {
        queuedReplies.clear();
        receivedPrompts.clear();
        CountDownLatch leftover = pauseLatch.getAndSet(null);
        if (leftover != null) leftover.countDown();
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
        // Defer the build so the latch.await() runs on the subscriber thread,
        // not on the test thread that called stream().
        return Flux.defer(() -> {
            CountDownLatch latch = pauseLatch.getAndSet(null);
            if (latch != null) {
                try {
                    if (!latch.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("TestChatModel pause latch not released within 10s");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("TestChatModel pause latch interrupted", e);
                }
            }
            return Flux.just(buildResponse(prompt));
        });
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
