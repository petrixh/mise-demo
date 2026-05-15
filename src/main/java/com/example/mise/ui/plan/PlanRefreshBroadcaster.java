package com.example.mise.ui.plan;

import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton Spring bean that broadcasts a "refresh" signal to all attached PlanView instances.
 *
 * <p>PlanView registers a refresh hook on {@code onAttach} and removes it on {@code onDetach}.
 * MainLayout's responseCompleteCallback calls {@link #fireRefresh()} after every AI turn,
 * which causes every open PlanView to reload its data via {@code UI.access(...)}.
 *
 * <p>CopyOnWriteArrayList gives thread-safe iteration from the streaming thread without
 * blocking the UI thread's register/deregister calls.
 */
@Component
public class PlanRefreshBroadcaster {

    private final CopyOnWriteArrayList<Runnable> hooks = new CopyOnWriteArrayList<>();

    /** Called by PlanView.onAttach — registers a refresh hook. */
    public void register(Runnable refreshHook) {
        hooks.add(refreshHook);
    }

    /** Called by PlanView.onDetach — removes the refresh hook. */
    public void deregister(Runnable refreshHook) {
        hooks.remove(refreshHook);
    }

    /**
     * Called by MainLayout's responseCompleteCallback after every AI turn.
     * Each hook wraps its work in {@code UI.access(...)} so it's safe to call
     * from a background streaming thread.
     */
    public void fireRefresh() {
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (Exception e) {
                // Ignore failures from detached UIs — they'll deregister on next request
            }
        }
    }
}
