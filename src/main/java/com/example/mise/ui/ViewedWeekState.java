package com.example.mise.ui;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UC-010: Holder for the currently viewed week's Monday.
 *
 * <p>The viewed Monday must be readable both from the Vaadin UI thread (where
 * {@link MainLayout} writes it on every navigation) <b>and</b> from the AI
 * tool-execution thread, where {@code PlanTools} / {@code ShoppingTools} resolve
 * dates so that "what's on Friday?" answers relative to the viewed week (BR-03 /
 * BR-06). The AI response streams on a Reactor {@code boundedElastic} thread on
 * which {@code VaadinSession.getCurrent()} is {@code null}.
 *
 * <p>Therefore the state is held in a thread-safe {@link AtomicReference} rather
 * than in {@link com.vaadin.flow.server.VaadinSession}: a session-scoped store
 * silently returned {@code null} on the tool thread, so the tools always fell
 * back to the ACTIVE plan and the viewed week was ignored (issue #79).
 *
 * <p><b>Scope tradeoff:</b> this makes the viewed week effectively app-scoped
 * rather than per-session. That is consistent with the rest of the app, which is
 * single-household / single-user by design ({@code HouseholdService.findHousehold()}
 * returns the one household with no session scoping). {@link MainLayout} rewrites
 * the value from the {@code ?week=} URL parameter on every navigation, so it stays
 * correct for the active user and resets to the active plan when navigating home.
 * State is in-memory only and does not survive a server restart (BR-05). If a
 * genuinely multi-user deployment is ever needed, key this by household/session.
 */
@Component
public class ViewedWeekState {

    private final AtomicReference<LocalDate> viewedMonday = new AtomicReference<>();

    /**
     * Returns the currently viewed Monday, or {@code null} when no week is selected
     * (meaning "show the active plan").
     */
    public LocalDate getViewedMonday() {
        return viewedMonday.get();
    }

    public void setViewedMonday(LocalDate viewedMonday) {
        this.viewedMonday.set(viewedMonday);
    }

    /**
     * Returns the ISO date string of the viewed Monday, or {@code null} if none is set.
     * Mirrors the ?week= URL parameter format.
     */
    public String getCurrentParam() {
        LocalDate monday = viewedMonday.get();
        return monday != null ? monday.toString() : null;
    }

    /** Clears the viewed week state (resets to "show active plan"). */
    public void clear() {
        viewedMonday.set(null);
    }
}
