package com.example.mise.ui.shared;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * App-wide refresh channel: views register a hook on attach and deregister on
 * detach/leave; {@code MainLayout} fires once after every AI turn so open views
 * re-read their data. One channel serves Plan, Shopping, and Reports — a
 * refresh is a cheap re-read, so there is no per-view routing.
 *
 * <p>Threading: {@code fireRefresh()} runs on the background streaming thread;
 * each hook is expected to wrap its own UI mutation in {@code ui.access(...)}
 * (views register {@code () -> ui.access(this::refresh)}). The listener list is
 * a {@link CopyOnWriteArrayList} so registration changes during iteration are
 * safe. A failing hook is dropped from the iteration's effects but does not
 * stop the others.
 */
@Component
public class ViewRefreshBroadcaster {

    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();

    public void register(Runnable hook) {
        if (hook != null && !hooks.contains(hook)) {
            hooks.add(hook);
        }
    }

    public void deregister(Runnable hook) {
        hooks.remove(hook);
    }

    public void fireRefresh() {
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (Exception ignored) {
                // a detached UI's hook may throw; never block the other views
            }
        }
    }
}
