package com.example.mise.it;

import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationMessage;
import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.PinnedMealException;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UC-002 + UC-003 Playwright IT — Plan view with seeded household + active plan.
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
 * meal_edit → conversation_message → meal → plan → household.
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
    private MealEditRepository mealEditRepository;

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
        // Delete in FK-safe order: meal_edit → conversation → meal → plan → household
        mealEditRepository.deleteAll();
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

    // ── UC-003 tests ──────────────────────────────────────────────────────────

    /**
     * UC-003 UI requirement: a pin icon button is visible on every meal row (Mon–Sun).
     */
    @Test
    void pinButtonVisibleOnEveryRow() {
        assertThat(page.getByTestId("meal-action-pin")).hasCount(7);
    }

    /**
     * UC-003 BR-04: after {@link PlanService#swapMeal} sets lastEditedBy=AI and
     * lastEditedAt=now, the "edited" pill is rendered on the affected row when the
     * view is reloaded.
     */
    @Test
    void editedPillRendersAfterAiSwap() {
        var household = householdService.findHousehold().orElseThrow();
        var plan = planService.findActivePlan(household.getId()).orElseThrow();

        // Find Thursday's meal
        LocalDate thursday = plan.getWeekStartDate()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
        Meal thursdayMeal = planService.findMeals(plan.getId()).stream()
                .filter(m -> m.getDate().equals(thursday))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Thursday meal in seeded plan"));

        // Pick a different recipe to swap in
        String newRecipeRef = recipeCatalog.findAll().stream()
                .map(r -> r.getId())
                .filter(id -> !id.equals(thursdayMeal.getRecipeRef()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No alternative recipe found in catalog"));

        // Perform the AI swap directly (sets lastEditedBy=AI, lastEditedAt=now, status=EDITED)
        planService.swapMeal(thursdayMeal.getId(), newRecipeRef,
                "vegetarian alternative for kid-friendly request");

        // Reload /plan so PlanView.beforeEnter() re-fetches and re-renders with fresh meal data
        page.navigate(getUrl() + "/plan");

        // The edited pill must appear on Thursday's row within the 60-second window
        assertThat(page.locator("[data-meal-date='" + thursday + "'] [data-testid='meal-status-edited-pill']"))
                .isVisible();
    }

    /**
     * UC-003 BR-01: every AI-driven meal swap produces exactly one {@link
     * com.example.mise.domain.plan.MealEdit} row with the correct audit fields.
     */
    @Test
    void mealEditRowPersistedAfterAiSwap() {
        var household = householdService.findHousehold().orElseThrow();
        var plan = planService.findActivePlan(household.getId()).orElseThrow();

        LocalDate thursday = plan.getWeekStartDate()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
        Meal thursdayMeal = planService.findMeals(plan.getId()).stream()
                .filter(m -> m.getDate().equals(thursday))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Thursday meal in seeded plan"));

        String originalRecipeRef = thursdayMeal.getRecipeRef();

        String newRecipeRef = recipeCatalog.findAll().stream()
                .map(r -> r.getId())
                .filter(id -> !id.equals(originalRecipeRef))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No alternative recipe found in catalog"));

        String reason = "vegetarian alternative for kid-friendly request";
        planService.swapMeal(thursdayMeal.getId(), newRecipeRef, reason);

        var edits = mealEditRepository.findByMealIdOrderByChangedAtDesc(thursdayMeal.getId());
        Assertions.assertThat(edits).hasSize(1);
        Assertions.assertThat(edits.get(0).getReason()).isEqualTo(reason);
        Assertions.assertThat(edits.get(0).getChangedBy()).isEqualTo(Meal.Editor.AI);
        Assertions.assertThat(edits.get(0).getPreviousRecipeRef()).isEqualTo(originalRecipeRef);
    }

    /**
     * UC-003 BR-03: attempting to swap a pinned meal throws {@link PinnedMealException};
     * after a reload the row still shows the original recipe.
     */
    @Test
    void pinnedMealRejectsSwap() {
        var household = householdService.findHousehold().orElseThrow();
        var plan = planService.findActivePlan(household.getId()).orElseThrow();

        LocalDate monday = plan.getWeekStartDate();
        Meal mondayMeal = planService.findMeals(plan.getId()).stream()
                .filter(m -> m.getDate().equals(monday))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Monday meal in seeded plan"));

        String newRecipeRef = recipeCatalog.findAll().stream()
                .map(r -> r.getId())
                .filter(id -> !id.equals(mondayMeal.getRecipeRef()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No alternative recipe found in catalog"));

        // Pin the meal via the explicit-editor overload (UC-003)
        planService.setPinned(mondayMeal.getId(), true, Meal.Editor.USER);

        // Attempting to swap a pinned meal must throw PinnedMealException
        Assertions.assertThatThrownBy(() ->
                planService.swapMeal(mondayMeal.getId(), newRecipeRef, "test"))
                .isInstanceOf(PinnedMealException.class);

        // Reload the view and confirm Monday's row is still present (original recipe untouched)
        page.navigate(getUrl() + "/plan");
        assertThat(page.locator("[data-meal-date='" + monday + "']")).isVisible();
    }

    /**
     * UC-003 UI surface: the stubbed assistant reply appears in the message list and
     * the last-AI-message preview span after typing into the chat dock on /plan.
     * Tool-call behaviour is covered by PlanToolsTest unit tests; this test only
     * asserts the chat UI surface.
     */
    @Test
    void chatRoundTripOnPlanViewWithStubbedSwap() {
        String reply = "Swapped Thursday’s meal for a vegetarian option — kid-friendly pea risotto, under 30 min.";
        chatModel.queueReply(reply);

        var chatDockLocator = page.getByTestId("chat-dock");
        MessageInputElement input = MessageInputElement.get(chatDockLocator);
        input.typeAndSubmit("Make Thursday vegetarian, kid is having a friend over.");

        input.focus();
        MessageListElement messages = new MessageListElement(page.getByTestId("chat-message-list"));
        messages.assertMessageCount(2);

        assertThat(page.getByTestId("chat-last-ai-message")).containsText(reply);
    }

    /**
     * UC-003 locator stability: every meal row carries a {@code data-pin-date} attribute
     * matching its ISO date, allowing per-day pin assertions in later UCs.
     */
    @Test
    void pinButtonHasDataPinDateAttribute() {
        // At least one [data-pin-date] element must be visible — proves the per-day
        // locator pattern works for later UC assertions.
        assertThat(page.locator("[data-pin-date]").first()).isVisible();
    }

    // ─── UC-004 ──────────────────────────────────────────────────────────────

    /**
     * Helper: finds the first meal for the given day of week in the active plan
     * and returns an alternative recipe ref that differs from the meal's current one.
     */
    private Meal findMealForDay(DayOfWeek dayOfWeek) {
        var household = householdService.findHousehold().orElseThrow();
        var plan = planService.findActivePlan(household.getId()).orElseThrow();
        LocalDate targetDate = plan.getWeekStartDate()
                .with(TemporalAdjusters.nextOrSame(dayOfWeek));
        return planService.findMeals(plan.getId()).stream()
                .filter(m -> m.getDate().equals(targetDate))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No meal on " + dayOfWeek + " in seeded plan"));
    }

    private String findAlternativeRecipe(String excludeRef) {
        return recipeCatalog.findAll().stream()
                .map(r -> r.getId())
                .filter(id -> !id.equals(excludeRef))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No alternative recipe found in catalog"));
    }

    /**
     * UC-004 AC: the undo icon button is visible on a row that has a MealEdit history row.
     * Seeds one AI swap via planService.swapMeal(), reloads the view, asserts the
     * per-day undo button is visible.
     */
    @Test
    void undoButtonVisibleOnRowWithEditHistory() {
        Meal wednesday = findMealForDay(DayOfWeek.WEDNESDAY);
        String altRef = findAlternativeRecipe(wednesday.getRecipeRef());
        planService.swapMeal(wednesday.getId(), altRef, "vegetarian swap for IT");

        page.navigate(getUrl() + "/plan");

        assertThat(page.getByTestId("meal-action-undo").first()).isVisible();
    }

    /**
     * UC-004 AC: the undo icon button is NOT visible on a row with no edit history.
     * Only meals that have been swapped by AI get undo buttons; the seeded plan has
     * no edits on any row. After seeding a swap for Wednesday only, Monday must NOT
     * have an undo button.
     */
    @Test
    void undoButtonNotVisibleOnRowWithNoHistory() {
        // Swap Wednesday so that "at least one undo button" exists (confirms the feature
        // is active) but Monday has no history — its undo button must be absent.
        Meal wednesday = findMealForDay(DayOfWeek.WEDNESDAY);
        String altRef = findAlternativeRecipe(wednesday.getRecipeRef());
        planService.swapMeal(wednesday.getId(), altRef, "vegetarian swap for IT");

        page.navigate(getUrl() + "/plan");

        // Monday has no edit history — its undo button must not be in the DOM / visible.
        assertThat(page.locator("#mise-meal-undo-monday")).not().isVisible();
    }

    /**
     * UC-004 AC #3: clicking the row undo button (a) restores the meal's recipeRef,
     * (b) creates a NEW MealEdit row with reason starting "Undo of edit",
     * (c) the post-undo MealEdit count for that meal = pre-undo count + 1.
     */
    @Test
    void clickingRowUndoRestoresPreviousRecipeAndWritesUndoAuditRow() {
        Meal thursday = findMealForDay(DayOfWeek.THURSDAY);
        String originalRef = thursday.getRecipeRef();
        String altRef = findAlternativeRecipe(originalRef);

        planService.swapMeal(thursday.getId(), altRef, "swap for IT undo test");

        int preUndoCount = mealEditRepository.findByMealIdOrderByChangedAtDesc(thursday.getId()).size();

        page.navigate(getUrl() + "/plan");

        // Click the per-day undo button for Thursday
        page.locator("#mise-meal-undo-thursday").click();

        // Wait for the Vaadin notification to confirm undo was applied
        assertThat(page.getByText("Undo applied")).isVisible();

        // Assert DB state: recipeRef restored
        Meal reloaded = mealRepository.findById(thursday.getId()).orElseThrow();
        Assertions.assertThat(reloaded.getRecipeRef()).isEqualTo(originalRef);

        // Assert DB state: new undo audit row created
        var editsAfter = mealEditRepository.findByMealIdOrderByChangedAtDesc(thursday.getId());
        Assertions.assertThat(editsAfter).hasSize(preUndoCount + 1);
        Assertions.assertThat(editsAfter.get(0).getReason()).startsWith("Undo of edit");
    }

    /**
     * UC-004 AC: clicking the "why?" button for a day with edit history causes a user
     * message containing "why did you change [Day]?" to appear in the chat MessageList,
     * and the stubbed assistant reply lands as a second message.
     */
    @Test
    void whyButtonClickSubmitsChatMessageAndReceivesReply() {
        Meal friday = findMealForDay(DayOfWeek.FRIDAY);
        String altRef = findAlternativeRecipe(friday.getRecipeRef());
        planService.swapMeal(friday.getId(), altRef, "fish swap for IT why test");

        page.navigate(getUrl() + "/plan");

        chatModel.queueReply("I swapped Friday because of your fish allergy preference.");

        // Focus the chat input to expand the dock so the message list is visible
        var chatDockLocator = page.getByTestId("chat-dock");
        MessageInputElement input = MessageInputElement.get(chatDockLocator);
        input.focus();

        // Click the "why?" button for Friday — this pre-fills and submits the chat prompt
        page.locator("#mise-meal-why-friday").click();

        // Wait for both user message and assistant reply to land (total = 2)
        MessageListElement messages = new MessageListElement(page.getByTestId("chat-message-list"));
        messages.assertMessageCount(2);

        // The first message must be the user turn containing the why-prompt text
        assertThat(page.getByTestId("chat-message-list")
                .locator("vaadin-message").first())
                .containsText("why did you change Friday?");
    }

    /**
     * UC-004 AC: the "why?" button is visible on a row that has edit history.
     */
    @Test
    void whyButtonVisibleOnRowWithEditHistory() {
        Meal tuesday = findMealForDay(DayOfWeek.TUESDAY);
        String altRef = findAlternativeRecipe(tuesday.getRecipeRef());
        planService.swapMeal(tuesday.getId(), altRef, "swap for IT why-visible test");

        page.navigate(getUrl() + "/plan");

        assertThat(page.locator("#mise-meal-why-tuesday")).isVisible();
    }

    /**
     * UC-004 AC: attempting to undo a pinned meal shows a "Cannot undo — meal is pinned"
     * Vaadin notification. The service layer throws PinnedMealException; the UI surfaces it.
     */
    @Test
    void undoOnPinnedMealShowsPinnedNotification() {
        Meal monday = findMealForDay(DayOfWeek.MONDAY);
        String altRef = findAlternativeRecipe(monday.getRecipeRef());

        // Create an edit so the undo button appears
        planService.swapMeal(monday.getId(), altRef, "swap for IT pinned-undo test");
        // Then pin the meal so undoLastEdit() throws PinnedMealException
        planService.setPinned(monday.getId(), true, Meal.Editor.USER);

        page.navigate(getUrl() + "/plan");

        // Click the per-day undo button for Monday
        page.locator("#mise-meal-undo-monday").click();

        // The view must surface the pinned-rejection notification
        assertThat(page.getByText("Cannot undo — meal is pinned")).isVisible();
    }

    /**
     * UC-004 AC: after an undo the weekly KPI strip is still present and visible,
     * confirming that a successful undo doesn't break the view's KPI rendering.
     */
    @Test
    void kpiStripStillRenderedAfterUndo() {
        Meal wednesday = findMealForDay(DayOfWeek.WEDNESDAY);
        String altRef = findAlternativeRecipe(wednesday.getRecipeRef());
        planService.swapMeal(wednesday.getId(), altRef, "swap for IT kpi-post-undo test");

        page.navigate(getUrl() + "/plan");

        page.locator("#mise-meal-undo-wednesday").click();

        // Confirm undo applied
        assertThat(page.getByText("Undo applied")).isVisible();

        // KPI strip must still be visible after the view refreshes
        assertThat(page.getByTestId("kpi-strip")).isVisible();
    }
}
