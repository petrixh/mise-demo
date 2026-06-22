package com.example.mise.ui;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for issue #79: the viewed week must be readable off the
 * Vaadin UI thread.
 *
 * <p>The AI tools resolve dates on a Reactor {@code boundedElastic} thread, where
 * {@code VaadinSession.getCurrent()} is {@code null}. The previous
 * {@link ViewedWeekState} stored the value in {@code VaadinSession}, so writing
 * and reading it from any non-UI thread (including this test JVM) silently
 * returned {@code null} — and the chat tools fell back to the ACTIVE plan,
 * ignoring the viewed week. These tests run with no {@code VaadinSession} active,
 * exactly like the production tool thread.
 */
class ViewedWeekStateTest {

    private final ViewedWeekState state = new ViewedWeekState();

    @Test
    void setThenGet_worksWithoutAVaadinSession() {
        LocalDate monday = LocalDate.of(2026, 6, 1);
        state.setViewedMonday(monday);

        assertThat(state.getViewedMonday()).isEqualTo(monday);
        assertThat(state.getCurrentParam()).isEqualTo("2026-06-01");
    }

    @Test
    void clear_resetsToActivePlanDefault() {
        state.setViewedMonday(LocalDate.of(2026, 6, 1));
        state.clear();

        assertThat(state.getViewedMonday()).isNull();
        assertThat(state.getCurrentParam()).isNull();
    }

    @Test
    void unset_returnsNull() {
        assertThat(state.getViewedMonday()).isNull();
        assertThat(state.getCurrentParam()).isNull();
    }

    /**
     * The crux of issue #79: a value written on one thread (the UI thread in
     * production) must be visible on a different thread (the AI tool-execution
     * thread). With the old VaadinSession-backed implementation this returned
     * null on the reading thread.
     */
    @Test
    void valueSetOnOneThreadIsVisibleOnAnother() throws Exception {
        LocalDate monday = LocalDate.of(2026, 6, 1);
        state.setViewedMonday(monday);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Callable<String> readOnOtherThread = state::getCurrentParam;
            Future<String> result = pool.submit(readOnOtherThread);
            assertThat(result.get()).isEqualTo("2026-06-01");
        } finally {
            pool.shutdownNow();
        }
    }
}
