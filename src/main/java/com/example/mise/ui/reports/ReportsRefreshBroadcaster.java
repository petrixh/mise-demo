package com.example.mise.ui.reports;

import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Broadcasts a "refresh" signal to all attached ReportsView instances after an AI turn.
 * Mirrors the PlanRefreshBroadcaster / ShoppingRefreshBroadcaster pattern.
 */
@Component
public class ReportsRefreshBroadcaster {

    private final CopyOnWriteArrayList<Runnable> hooks = new CopyOnWriteArrayList<>();

    public void register(Runnable hook) {
        hooks.add(hook);
    }

    public void deregister(Runnable hook) {
        hooks.remove(hook);
    }

    public void fireRefresh() {
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (Exception ignored) {
                // Detached UI — will deregister on next event
            }
        }
    }
}
