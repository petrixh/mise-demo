package com.example.mise.ui.reports;

import com.vaadin.flow.component.ai.orchestrator.AIController;
import com.vaadin.flow.component.ai.orchestrator.RequestListener.RequestEvent;
import com.vaadin.flow.component.ai.orchestrator.ResponseListener.ResponseEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReportsAIControllerTest {

    private final AIController trend = mock(AIController.class);
    private final AIController category = mock(AIController.class);
    private final AIController leaderboard = mock(AIController.class);
    private final Runnable refresh = mock(Runnable.class);
    private final ReportsAIController controller =
            new ReportsAIController(trend, category, leaderboard, refresh);

    @Test
    void forwardsRequestEventToEveryWidget() {
        var event = new RequestEvent("Show weekly costs", "message-1", java.util.List.of());

        controller.onRequest(event);

        verify(trend).onRequest(event);
        verify(category).onRequest(event);
        verify(leaderboard).onRequest(event);
    }

    @Test
    void forwardsResponseEventBeforeRefreshingData() {
        var event = new ResponseEvent("Updated reports", null, null);

        controller.onResponse(event);

        var order = inOrder(trend, category, leaderboard, refresh);
        order.verify(trend).onResponse(event);
        order.verify(category).onResponse(event);
        order.verify(leaderboard).onResponse(event);
        order.verify(refresh).run();
    }

    @Test
    void widgetFailureStillRendersOtherWidgetsAndRefreshesBeforeRethrowing() {
        var event = new ResponseEvent(null, new IllegalStateException("LLM request failed"), null);
        var failure = new IllegalStateException("trend render failed");
        doThrow(failure).when(trend).onResponse(event);
        doThrow(new IllegalStateException("category render failed"))
                .when(category).onResponse(event);

        assertThatThrownBy(() -> controller.onResponse(event)).isSameAs(failure);

        var order = inOrder(trend, category, leaderboard, refresh);
        order.verify(trend).onResponse(event);
        order.verify(category).onResponse(event);
        order.verify(leaderboard).onResponse(event);
        order.verify(refresh).run();
    }
}
