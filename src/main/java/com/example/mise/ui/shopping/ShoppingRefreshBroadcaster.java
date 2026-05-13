package com.example.mise.ui.shopping;

import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton Spring bean that broadcasts a "refresh" signal to all attached ShoppingView instances.
 *
 * <p>ShoppingView registers a refresh hook on {@code onAttach} and removes it on {@code onDetach}.
 * MainLayout's responseCompleteCallback calls {@link #fireRefresh()} after every AI turn,
 * which causes every open ShoppingView to reload its derived list via {@code UI.access(...)}.
 *
 * <p>Also fired when plan mutations happen (swap/negotiate/undo) so the shopping list stays in
 * sync with meal changes within 2 seconds of the AI turn completing (BR-08).
 *
 * <p>CopyOnWriteArrayList gives thread-safe iteration from the streaming thread without
 * blocking the UI thread's register/deregister calls.
 */
@Component
public class ShoppingRefreshBroadcaster {

    private final CopyOnWriteArrayList<Runnable> hooks = new CopyOnWriteArrayList<>();

    /** Called by ShoppingView.onAttach — registers a refresh hook. */
    public void register(Runnable refreshHook) {
        hooks.add(refreshHook);
    }

    /** Called by ShoppingView.onDetach — removes the refresh hook. */
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
