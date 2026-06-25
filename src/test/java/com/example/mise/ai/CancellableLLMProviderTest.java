package com.example.mise.ai;

import com.vaadin.flow.component.ai.provider.LLMProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC-013 unit tests for {@link CancellableLLMProvider}.
 *
 * <p>{@link LLMProvider} is a functional interface ({@code stream} is its only abstract
 * method), so each delegate here is a lambda returning a controllable {@link Flux}. The
 * operators in the wrapper are synchronous, so cancellation completes on the calling thread.
 */
class CancellableLLMProviderTest {

    @Test
    void cancel_beforeAnyStream_returnsFalse() {
        var provider = new CancellableLLMProvider(req -> Flux.empty());

        assertThat(provider.cancel()).isFalse();
    }

    @Test
    void cancel_midStream_completesEarlyAndAppendsStoppedMarker() throws Exception {
        // A source that emits one chunk then never completes on its own.
        LLMProvider delegate = req -> Flux.concat(Flux.just("partial answer"), Flux.never());
        var provider = new CancellableLLMProvider(delegate);

        var emitted = new CopyOnWriteArrayList<String>();
        var done = new CompletableFuture<Void>();
        provider.stream(null).subscribe(emitted::add, done::completeExceptionally, () -> done.complete(null));

        // The first chunk has streamed and the stream is still open (parked on Flux.never()).
        assertThat(emitted).containsExactly("partial answer");

        boolean signalled = provider.cancel();
        done.get(2, TimeUnit.SECONDS); // completes once cancelled

        assertThat(signalled).isTrue();
        assertThat(emitted).containsExactly("partial answer", " _(stopped)_");
    }

    @Test
    void selfCompletingStream_doesNotAppendStoppedMarker() throws Exception {
        LLMProvider delegate = req -> Flux.just("a", "b");
        var provider = new CancellableLLMProvider(delegate);

        var emitted = new CopyOnWriteArrayList<String>();
        var done = new CompletableFuture<Void>();
        provider.stream(null).subscribe(emitted::add, done::completeExceptionally, () -> done.complete(null));

        done.get(2, TimeUnit.SECONDS);

        assertThat(emitted).containsExactly("a", "b");
    }

    @Test
    void setHistory_delegates() {
        var seen = new CopyOnWriteArrayList<Object>();
        LLMProvider delegate = new LLMProvider() {
            @Override
            public Flux<String> stream(LLMRequest request) {
                return Flux.empty();
            }

            @Override
            public void setHistory(java.util.List<com.vaadin.flow.component.ai.common.ChatMessage> history,
                                   java.util.Map<String, java.util.List<com.vaadin.flow.component.ai.common.AIAttachment>> attachments) {
                seen.add(history);
            }
        };
        var provider = new CancellableLLMProvider(delegate);

        provider.setHistory(java.util.List.of(), java.util.Map.of());

        assertThat(seen).hasSize(1);
    }
}
