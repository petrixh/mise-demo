package com.example.mise.it;

import org.junit.jupiter.api.Test;
import org.vaadin.addons.dramafinder.element.MessageInputElement;
import org.vaadin.addons.dramafinder.element.MessageListElement;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UC-001 round-trip IT against a deterministic {@link com.example.mise.it.support.TestChatModel}.
 *
 * <p>Covers the chat plumbing only: seeded greeting renders, user input is echoed, the
 * stubbed assistant reply lands in the {@code MessageList}, no LLM endpoint is contacted.
 * Tool-call coverage (the {@code recordHousehold} flow that drives navigation to {@code /plan})
 * is exercised separately by {@code OnboardingToolsTest} as a unit test — keeping this IT focused
 * on the Vaadin <-> Spring AI <-> stubbed ChatModel wiring.
 */
class OnboardingViewIT extends MisePlaywrightIT {

    private static final String OPENING_GREETING_FRAGMENT = "Hi — I'll set up your dinners";

    @Override
    public String getView() {
        return "/welcome";
    }

    @Test
    void seededGreetingRendersOnFirstLoad() {
        MessageListElement messages = MessageListElement.get(page);
        messages.assertMessageCount(1);
        assertThat(messages.getMessage(0)).containsText(OPENING_GREETING_FRAGMENT);
    }

    @Test
    void userInputRoundTripsThroughStubbedAssistant() {
        chatModel.queueReply("Got it — two adults, no fish, €90/week. Sound right?");

        MessageInputElement input = MessageInputElement.get(page);
        MessageListElement messages = MessageListElement.get(page);

        input.typeAndSubmit("Two adults, no fish, around €90 a week");

        // Greeting + user + assistant
        messages.assertMessageCount(3);
        assertThat(messages.getMessage(1)).containsText("Two adults, no fish");
        assertThat(messages.getMessage(2)).containsText("Got it — two adults, no fish");
    }

    @Test
    void defaultReplyUsedWhenTestForgetsToQueueOne() {
        // Guardrail: a forgotten queueReply() shouldn't silently call the (unreachable) real LLM.
        // The stub falls back to a sentinel string instead.
        MessageInputElement input = MessageInputElement.get(page);
        MessageListElement messages = MessageListElement.get(page);

        input.typeAndSubmit("hello");

        messages.assertMessageCount(3);
        assertThat(messages.getMessage(2)).containsText("no reply queued");
    }
}
