package com.example.mise.it;

import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationMessage;
import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.vaadin.addons.dramafinder.element.MessageInputElement;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.assertj.core.api.Assertions;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UC-008 chat-dock interaction model — covers BR-08 (two-state dock), BR-09
 * (scroll-to-latest-on-expand), and BR-10 (.ai-working indicator).
 *
 * <p>Setup mirrors {@link PlanViewIT}: seed a household + active plan so the
 * Plan view (which hosts the shared dock) renders fully. The chat dock lives
 * in MainLayout so any view would do; /plan is convenient.
 *
 * <p>BR-10 specifically pauses the streaming response via {@link
 * com.example.mise.it.support.TestChatModel#pauseNextResponse()} so the test
 * can observe the in-flight `.ai-working` state on the dock between submit and
 * response-complete. Releasing the latch lets the response finish and the
 * class clear.
 */
class ChatDockIT extends MisePlaywrightIT {

    @Autowired private HouseholdService householdService;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private PlanService planService;
    @Autowired private PlanRepository planRepository;
    @Autowired private MealRepository mealRepository;
    @Autowired private MealEditRepository mealEditRepository;
    @Autowired private RecipeCatalog recipeCatalog;
    @Autowired private ConversationMessageRepository conversationMessageRepository;

    @Override
    public String getView() {
        return "/plan";
    }

    @Override
    @BeforeEach
    public void setupTest() throws Exception {
        Household h = new Household();
        h.setName("IT ChatDock Household");
        h.setSize(2);
        h.setWeeklyBudget(new BigDecimal("100.00"));
        h.setAllergies(List.of());
        h.setHatedFoods(List.of());
        householdService.save(h);

        var household = householdService.findHousehold().orElseThrow();
        planService.generateActivePlan(household, recipeCatalog);

        // BR-09 needs enough chat history that the message list is scrollable.
        // Seed 12 alternating user/assistant rows; the orchestrator rehydrates
        // them when MainLayout activates the household. ConversationMessage has
        // no household FK (single-household demo) and @PrePersist fills createdAt.
        for (int i = 0; i < 12; i++) {
            ConversationMessage row = new ConversationMessage();
            row.setRole(i % 2 == 0 ? ConversationMessage.Role.USER
                                   : ConversationMessage.Role.ASSISTANT);
            row.setContent(i % 2 == 0
                    ? "Earlier user turn #" + (i / 2 + 1)
                    : "Earlier assistant reply #" + (i / 2 + 1)
                            + " — padded text to make the row tall enough that 12 of them"
                            + " produce a scrollable history.");
            row.setViewContext(ConversationMessage.ViewContext.PLAN);
            conversationMessageRepository.save(row);
        }

        super.setupTest();
    }

    @AfterEach
    void cleanUp() {
        mealEditRepository.deleteAll();
        conversationMessageRepository.deleteAll();
        householdRepository.findAll().forEach(hh ->
                planRepository.findByHouseholdIdOrderByWeekStartDateDesc(hh.getId())
                        .forEach(plan -> {
                            mealRepository.deleteAll(mealRepository.findByPlanId(plan.getId()));
                            planRepository.delete(plan);
                        })
        );
        householdRepository.deleteAll();
    }

    /**
     * BR-08 (two-state dock): blurred = collapsed (max-height 0, single-line
     * preview visible); focused = expanded (max-height > 0, preview hidden).
     */
    @Test
    void dockExpandsOnFocusAndCollapsesOnBlur() {
        var chatDock = page.getByTestId("chat-dock");
        assertThat(chatDock).isVisible();

        // Initially collapsed: chat-history max-height is 0, preview shown.
        // parseFloat() of "0px" yields a whole number — Playwright marshals it as
        // Integer; non-whole values come back as Double. Cast through Number.
        double initialHeight = ((Number) chatDock.evaluate(
                "d => parseFloat(getComputedStyle(d.querySelector('.mise-chat-history')).maxHeight)"
        )).doubleValue();
        Assertions.assertThat(initialHeight).as("collapsed max-height").isLessThanOrEqualTo(1.0);

        Boolean previewVisibleBefore = (Boolean) chatDock.evaluate(
                "d => getComputedStyle(d.querySelector('.mise-last-ai-message')).display !== 'none'"
        );
        Assertions.assertThat(previewVisibleBefore).as("preview visible when collapsed").isTrue();

        // Focus the input → expand.
        MessageInputElement.get(chatDock).focus();

        // Auto-wait for the focus-within style to land via Playwright's polling
        // assertion; max-height transitions over ~180 ms.
        page.waitForFunction(
                "d => parseFloat(getComputedStyle(d.querySelector('.mise-chat-history')).maxHeight) > 100",
                chatDock.elementHandle()
        );

        double expandedHeight = ((Number) chatDock.evaluate(
                "d => parseFloat(getComputedStyle(d.querySelector('.mise-chat-history')).maxHeight)"
        )).doubleValue();
        Assertions.assertThat(expandedHeight).as("expanded max-height").isGreaterThan(100.0);

        Boolean previewVisibleAfter = (Boolean) chatDock.evaluate(
                "d => getComputedStyle(d.querySelector('.mise-last-ai-message')).display !== 'none'"
        );
        Assertions.assertThat(previewVisibleAfter).as("preview hidden when expanded").isFalse();

        // Blur by clicking outside the dock (the meal grid is a safe target).
        page.getByTestId("kpi-strip").click();
        page.waitForFunction(
                "d => parseFloat(getComputedStyle(d.querySelector('.mise-chat-history')).maxHeight) <= 1",
                chatDock.elementHandle()
        );

        Boolean previewVisibleAfterBlur = (Boolean) chatDock.evaluate(
                "d => getComputedStyle(d.querySelector('.mise-last-ai-message')).display !== 'none'"
        );
        Assertions.assertThat(previewVisibleAfterBlur).as("preview visible after re-collapse").isTrue();
    }

    /**
     * BR-09 (scroll-to-latest on expand): after seeding ~12 messages, focusing
     * the input must scroll the message list to the bottom regardless of where
     * it was before.
     */
    @Test
    void expandScrollsToLatestMessage() {
        var chatDock = page.getByTestId("chat-dock");
        MessageInputElement input = MessageInputElement.get(chatDock);

        // Open the dock, scroll to the TOP of the history programmatically, then
        // re-focus and assert it landed at the bottom.
        input.focus();
        page.waitForFunction(
                "d => parseFloat(getComputedStyle(d.querySelector('.mise-chat-history')).maxHeight) > 100",
                chatDock.elementHandle()
        );

        chatDock.evaluate("d => { d.querySelector('.mise-chat-history').scrollTop = 0; }");

        // Blur the input, re-focus to trigger the focusin handler again.
        page.getByTestId("kpi-strip").click();
        page.waitForFunction(
                "d => parseFloat(getComputedStyle(d.querySelector('.mise-chat-history')).maxHeight) <= 1",
                chatDock.elementHandle()
        );
        input.focus();

        // Wait for the transitionend-driven scroll to land. transitionend fires
        // once the 180ms max-height transition completes; allow ~500ms buffer.
        page.waitForFunction(
                "d => { const h = d.querySelector('.mise-chat-history');"
                + "      return h.scrollHeight - (h.scrollTop + h.clientHeight) < 5; }",
                chatDock.elementHandle()
        );

        // Final assertion — confirm we're at the bottom.
        Boolean atBottom = (Boolean) chatDock.evaluate(
                "d => { const h = d.querySelector('.mise-chat-history');"
                + "      return h.scrollHeight - (h.scrollTop + h.clientHeight) < 5; }"
        );
        Assertions.assertThat(atBottom).as("scrolled to bottom on expand").isTrue();
    }

    /**
     * BR-10 (.ai-working indicator): on submit the dock gains `.ai-working`;
     * once the orchestrator's responseCompleteListener fires (after we release
     * the pause-latch), the class is removed.
     */
    @Test
    void aiWorkingClassToggledAroundSubmit() throws Exception {
        CountDownLatch responseLatch = chatModel.pauseNextResponse();
        chatModel.queueReply("Sure — Thursday is Roasted Vegetable Pasta.");

        var chatDock = page.getByTestId("chat-dock");
        MessageInputElement input = MessageInputElement.get(chatDock);
        input.typeAndSubmit("Swap Thursday for something vegetarian.");

        // After submit, the submit-listener on the UI thread adds .ai-working.
        // Wait for the class to appear (auto-polled, up to default timeout).
        page.waitForFunction(
                "d => d.classList.contains('ai-working')",
                chatDock.elementHandle()
        );

        Boolean hasWorkingDuring = (Boolean) chatDock.evaluate(
                "d => d.classList.contains('ai-working')"
        );
        Assertions.assertThat(hasWorkingDuring).as(".ai-working set while streaming").isTrue();

        // Release the streaming pause so the responseCompleteListener can fire.
        responseLatch.countDown();

        // Wait for class removal once response-complete clears it (wrapped in
        // ui.access — round-trip via the streaming thread + UI push).
        page.waitForFunction(
                "d => !d.classList.contains('ai-working')",
                chatDock.elementHandle()
        );

        Boolean hasWorkingAfter = (Boolean) chatDock.evaluate(
                "d => d.classList.contains('ai-working')"
        );
        Assertions.assertThat(hasWorkingAfter).as(".ai-working cleared after response complete").isFalse();
    }
}
