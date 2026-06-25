package com.example.mise.it;

import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.vaadin.addons.dramafinder.AbstractBasePlaywrightIT;
import org.vaadin.addons.dramafinder.element.MessageInputElement;

import java.math.BigDecimal;
import java.util.List;

/**
 * Release-gate verification of the manual's UC-013 "stop" example against the <b>live model</b>.
 *
 * <p>The busy note and idle "stop" are covered deterministically by {@link ChatBusyStopIT}
 * (stub model). Cancelling a <i>real</i> in-flight turn, however, depends on the production
 * provider/orchestrator path streaming over a live connection — which only exists with the real
 * {@code OpenAiChatModel}. Like {@link ManualExamplesReportsIT}, this extends
 * {@link AbstractBasePlaywrightIT} under {@code @ActiveProfiles("manual")} and is tagged
 * {@code manual-example} → runs only under the opt-in {@code -Pmanual-verify} profile.
 *
 * <p><b>Best-effort by nature.</b> The turn must still be streaming when "stop" lands, so the
 * prompt asks for a very long generation to keep a wide in-flight window, and the test waits until
 * a substantial amount of text has streamed before stopping. If a particularly fast model finished
 * the whole essay before "stop" arrived there would be no {@code (stopped)} marker; that is the one
 * documented way this gate can legitimately flake, and it is run by intention before a release.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("manual")
@Tag("manual-example")
class ManualExamplesStopIT extends AbstractBasePlaywrightIT {

    /** Matches {@link ManualExamplesReportsIT}: the live turn can chain several slow calls. */
    private static final long REPLY_TIMEOUT_MS = 300_000L;
    /** Once this much assistant text has streamed we are solidly mid-turn and safe to stop. */
    private static final int MID_STREAM_CHARS = 200;

    @LocalServerPort
    protected int port;

    @Autowired private HouseholdService householdService;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private PlanService planService;
    @Autowired private PlanRepository planRepository;
    @Autowired private MealRepository mealRepository;
    @Autowired private MealEditRepository mealEditRepository;
    @Autowired private RecipeCatalog recipeCatalog;
    @Autowired private ConversationMessageRepository conversationMessageRepository;

    private Household household;

    @Override
    public String getUrl() {
        return "http://localhost:" + port;
    }

    @Override
    public String getView() {
        return "/plan";
    }

    @Override
    @BeforeEach
    public void setupTest() throws Exception {
        Household h = new Household();
        h.setName("Manual Stop Household");
        h.setSize(2);
        h.setWeeklyBudget(new BigDecimal("100.00"));
        h.setAllergies(List.of());
        h.setHatedFoods(List.of());
        householdService.save(h);
        household = householdService.findHousehold().orElseThrow();
        planService.generateActivePlan(household, recipeCatalog);

        super.setupTest(); // navigates to getUrl() + getView() == /plan
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
                        }));
        householdRepository.deleteAll();
    }

    // ── UC-013 — "stop" cancels an in-flight turn ───────────────────────────────
    @Test
    void stopCancelsAnInFlightTurn() {
        var chatDock = page.getByTestId("chat-dock");

        // A deliberately long generation so the turn stays in flight long enough to interrupt.
        MessageInputElement.get(chatDock).typeAndSubmit(
                "Write an extremely long, exhaustive 2000-word essay covering the complete history, "
                + "botany, cultivation, global culinary traditions, and folklore of garlic. Be very "
                + "thorough and detailed.");

        // Wait until the turn is solidly in flight: .ai-working set and a good chunk streamed.
        page.waitForFunction("d => d.classList.contains('ai-working')", chatDock.elementHandle(),
                new Page.WaitForFunctionOptions().setTimeout(REPLY_TIMEOUT_MS));
        page.waitForFunction(
                "min => { const m = [...document.querySelectorAll('vaadin-message')].pop();"
                + " return m && (m.textContent || '').length > min; }",
                MID_STREAM_CHARS, new Page.WaitForFunctionOptions().setTimeout(REPLY_TIMEOUT_MS));

        // Stop it.
        MessageInputElement.get(chatDock).typeAndSubmit("stop");

        // The turn must end because we stopped it: .ai-working clears...
        page.waitForFunction("d => !d.classList.contains('ai-working')", chatDock.elementHandle(),
                new Page.WaitForFunctionOptions().setTimeout(REPLY_TIMEOUT_MS));
        // ...and the partial reply is kept, marked (stopped).
        page.waitForFunction(
                "() => [...document.querySelectorAll('vaadin-message')]"
                + ".some(m => /\\(stopped\\)/.test(m.textContent || ''))",
                null, new Page.WaitForFunctionOptions().setTimeout(30_000L));
    }
}
