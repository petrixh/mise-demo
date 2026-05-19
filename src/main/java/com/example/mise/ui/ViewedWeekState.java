package com.example.mise.ui;

import com.vaadin.flow.server.VaadinSession;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * UC-010: Session-scoped holder for the currently viewed week's Monday.
 *
 * <p>State is stored in {@link VaadinSession} as a named attribute so it persists
 * across same-session navigation between /plan, /shopping, and /reports (BR-05).
 * Reading returns null when no VaadinSession is active (e.g. in tests), which
 * makes tool helpers fall back to the ACTIVE plan gracefully (BR-08 fallback).
 *
 * <p>Because this bean itself is stateless (all state is in VaadinSession),
 * it can be a plain singleton and is safe to inject into UI-scoped or
 * singleton components alike.
 */
@Component
public class ViewedWeekState {

    private static final String ATTR = "mise.viewedMonday";

    /**
     * Returns the currently viewed Monday, or {@code null} when no week is selected
     * (meaning "show the active plan").
     */
    public LocalDate getViewedMonday() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) return null;
        return (LocalDate) session.getAttribute(ATTR);
    }

    public void setViewedMonday(LocalDate viewedMonday) {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) return;
        session.setAttribute(ATTR, viewedMonday);
    }

    /**
     * Returns the ISO date string of the viewed Monday, or {@code null} if none is set.
     * Mirrors the ?week= URL parameter format.
     */
    public String getCurrentParam() {
        LocalDate monday = getViewedMonday();
        return monday != null ? monday.toString() : null;
    }

    /** Clears the viewed week state (resets to "show active plan"). */
    public void clear() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) return;
        session.setAttribute(ATTR, null);
    }
}
