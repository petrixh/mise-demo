package com.example.mise.ai;

import com.vaadin.flow.component.ai.common.AIAttachment;
import com.vaadin.flow.component.ai.common.ChatMessage;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UC-013: an {@link LLMProvider} decorator that makes the in-flight response stream
 * cancellable from the UI thread.
 *
 * <p>Vaadin's {@code AIOrchestrator} owns the streaming subscription and exposes no
 * cancel API; the only stage the app controls is {@link LLMProvider#stream}. This wrapper
 * decorates the delegate's {@link Flux} with {@code takeUntilOther} so {@link #cancel()}
 * can complete it early. Because the orchestrator treats that early completion as a normal
 * stream end, it resets its internal {@code isProcessing} flag and fires its
 * {@code ResponseListener} with whatever text accumulated — so the chat unblocks and the
 * partial reply is persisted.
 *
 * <p>A {@code (stopped)} marker is appended (via {@code concatWith}) only when the
 * completion was a user cancel, so it streams into the same assistant bubble. The marker is
 * markdown italic and renders because the chat {@code MessageList} has markdown enabled.
 *
 * <p>One provider instance is built per {@code AIOrchestrator} (the bean is prototype-scoped,
 * see {@link com.example.mise.config.AIConfig}), so the single {@link #stopSink} field safely
 * tracks the cancel handle for that UI's currently streaming turn.
 */
public final class CancellableLLMProvider implements LLMProvider {

    private final LLMProvider delegate;

    /** Cancel handle for the turn currently streaming; replaced on each {@link #stream}. */
    private volatile Sinks.Empty<Object> stopSink;
    /** Set when {@link #cancel()} fires so {@link #stream}'s {@code concatWith} appends the marker. */
    private volatile AtomicBoolean cancelled;

    public CancellableLLMProvider(LLMProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public Flux<String> stream(LLMProvider.LLMRequest request) {
        var stop = Sinks.<Object>empty();
        var flag = new AtomicBoolean(false);
        this.stopSink = stop;
        this.cancelled = flag;
        return delegate.stream(request)
                // A cancel emits on stopSink → upstream is cancelled and this Flux completes.
                .takeUntilOther(stop.asMono())
                // On a user cancel only, append a marker into the same assistant bubble.
                .concatWith(Flux.defer(() -> flag.get() ? Flux.just(" _(stopped)_") : Flux.empty()));
    }

    @Override
    public void setHistory(List<ChatMessage> history, Map<String, List<AIAttachment>> attachments) {
        delegate.setHistory(history, attachments);
    }

    /**
     * Signals the currently streaming turn (if any) to stop.
     *
     * @return {@code true} if a stream was active and the cancel signal was accepted
     */
    public boolean cancel() {
        var flag = this.cancelled;
        var stop = this.stopSink;
        if (flag == null || stop == null) {
            return false;
        }
        flag.set(true);
        return stop.tryEmitEmpty().isSuccess();
    }
}
