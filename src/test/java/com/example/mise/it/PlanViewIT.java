package com.example.mise.it;

import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationMessage;
import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.vaadin.addons.dramafinder.element.MessageInputElement;
import org.vaadin.addons.dramafinder.element.MessageListElement;

import java.math.BigDecimal;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UC-002 Playwright IT — Plan view with seeded household + active plan.
 *
 * <p>Lifecycle: {@code setupTest()} is overridden (same technique as
 * {@link OnboardingRedirectIT}) to seed a Household and an ACTIVE Plan before
 * the browser navigates to /plan, so the view renders a full 7-row meal grid.
 *
 * <p>Chat-round-trip tests stub the {@link com.example.mise.it.support.TestChatModel}
 * via {@code chatModel.queueReply(...)} before submitting user input. No live LLM
 * endpoint is contacted.
 *
 * <p>Cleanup in {@code @AfterEach} deletes rows in FK-safe order:
 * conversation_message → meal → plan → household.
 */
class PlanViewIT extends MisePlaywrightIT {

    @Autowired
    private HouseholdService householdService;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private PlanService planService;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private MealRepository mealRepository;

    @Autowired
    private RecipeCatalog recipeCatalog;

    @Autowired
    private ConversationMessageRepository conversationMessageRepository;

    @Override
    public String getView() {
        return "/plan";
    }

    /**
     * Override to seed state BEFORE the base class navigates to /plan.
     * The base @BeforeEach on setupTest() is hidden by this override in JUnit 5;
     * super.setupTest() must be called explicitly to perform the browser navigation.
     */
    @Override
    @BeforeEach
    public void setupTest() throws Exception {
        // Seed household
        Household h = new Household();
        h.setName("IT Plan Household");
        h.setSize(2);
        h.setWeeklyBudget(new BigDecimal("100.00"));
        h.setAllergies(List.of());
        h.setHatedFoods(List.of());
        householdService.save(h);

        // Seed the active plan via the production code path — picks 7 recipes from
        // the YAML catalog and saves Plan + 7 Meal rows with status=PLANNED.
        var household = householdService.findHousehold().orElseThrow();
        planService.generateActivePlan(household, recipeCatalog);

        // Navigate AFTER seeding so PlanView.beforeEnter() finds the household and plan.
        super.setupTest();
    }

    @AfterEach
    void cleanUp() {
        // Delete in FK-safe order: conversation → meal → plan → household
        conversationMessageRepository.deleteAll();
        // Get all plans for this household (need to delete meals per plan)
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
     * UC-002 AC: loading /plan renders the page with the correct title.
     */
    @Test
    void hasPageTitle() {
        assertThat(page).hasTitle("Mise — Plan");
    }

    /**
     * UC-002 AC: the weekly KPI strip with all four cards is visible.
     * BR-03: stats are computed from the currently shown meals.
     */
    @Test
    void kpiStripRenders() {
        assertThat(page.getByTestId("kpi-strip")).isVisible();
        assertThat(page.getByTestId("kpi-card-weekly-cost")).isVisible();
        assertThat(page.getByTestId("kpi-card-avg-meal")).isVisible();
        assertThat(page.getByTestId("kpi-card-total-prep")).isVisible();
        assertThat(page.getByTestId("kpi-card-avg-kcal")).isVisible();
    }

    /**
     * UC-002 AC: the meal grid has exactly 7 rows (Mon–Sun).
     * BR-02: grid always has 7 rows; missing slots show empty placeholders.
     */
    @Test
    void mealGridHasSevenRows() {
        assertThat(page.getByTestId("meal-row")).hasCount(7);
    }

    /**
     * UC-002 AC: the cost-by-category panel is visible and shows its title.
     */
    @Test
    void costByCategoryPanelRenders() {
        assertThat(page.getByTestId("cost-by-category-panel")).isVisible();
        assertThat(page.getByTestId("cost-by-category-panel").getByText("COST BY CATEGORY"))
                .isVisible();
    }

    /**
     * UC-002 AC: chat round-trip via the shared dock works correctly on the Plan view.
     * The stubbed assistant reply appears in the MessageList and updates the preview span.
     *
     * <p>Locator note: {@code data-testid="chat-message-list"} is set directly on the
     * {@code vaadin-message-list} element. We wrap it with {@code new MessageListElement(...)}
     * rather than the {@code get(locator)} factory, which would search for a
     * {@code vaadin-message-list} *inside* the provided locator (one level too deep).
     */
    @Test
    void chatRoundTripWorksOnSharedDock() {
        chatModel.queueReply("Friday is creamy salmon pasta.");

        // Scope the MessageInput to the chat dock — chat-dock contains vaadin-message-input.
        var chatDockLocator = page.getByTestId("chat-dock");
        MessageInputElement input = MessageInputElement.get(chatDockLocator);
        input.typeAndSubmit("What's on Friday?");

        // Focus the message input to trigger :focus-within on the dock, which CSS
        // expands the message-history region from max-height:0 to visible.
        input.focus();

        // Wrap the testid locator directly — the testid IS the vaadin-message-list.
        MessageListElement messages = new MessageListElement(page.getByTestId("chat-message-list"));
        messages.assertMessageCount(2);

        // The last-AI-message preview span must be updated with the queued reply.
        assertThat(page.getByTestId("chat-last-ai-message"))
                .containsText("Friday is creamy salmon pasta.");
    }

    /**
     * UC-002 / BR-06: after a chat round-trip on /plan, the persisted messages carry
     * ViewContext.PLAN so the conversation is correctly attributed to the Plan view.
     */
    @Test
    void conversationPersistedWithPlanViewContext() {
        chatModel.queueReply("Friday is creamy salmon pasta.");

        var chatDockLocator = page.getByTestId("chat-dock");
        MessageInputElement input = MessageInputElement.get(chatDockLocator);
        input.typeAndSubmit("What's on Friday?");

        // Focus the input to expand the dock and wait for the assistant turn to land.
        input.focus();
        // Wrap testid locator directly (testid IS the vaadin-message-list element).
        MessageListElement messages = new MessageListElement(page.getByTestId("chat-message-list"));
        messages.assertMessageCount(2);

        // Both the user and assistant messages should be stamped PLAN.
        var rows = conversationMessageRepository.findAll();
        Assertions.assertThat(rows)
                .filteredOn(r -> r.getViewContext() == ConversationMessage.ViewContext.PLAN)
                .hasSizeGreaterThanOrEqualTo(2);
    }
}
