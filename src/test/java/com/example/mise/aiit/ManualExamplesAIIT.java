package com.example.mise.aiit;

import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.domain.preferences.ViewPreference;
import com.example.mise.ui.ViewedWeekState;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.time.temporal.TemporalAdjusters.previousOrSame;
import static org.mockito.Mockito.when;

/**
 * Release-gate verification that the user manual's <b>Example queries</b> table
 * ({@code docs/manual/mise-manual.typ}) actually works against the live model — one test per
 * documented chat example, sending the manual's <b>verbatim</b> prompt. The manual claims each row is
 * "verified against a running build"; this class makes that claim a regression-backed contract for the
 * chat-driven examples. (The two Reports <i>widget-reshape</i> examples — chart repoint and leaderboard
 * rank-by-kcal — depend on Vaadin controller tools that don't exist in this headless harness; they are
 * covered by the Playwright {@code ManualExamplesReportsIT} instead.)
 *
 * <p><b>Tagged {@code manual-example}</b> and run only by the opt-in {@code manual-verify} Maven
 * profile (excluded from the regular {@code ai-it} regression suite), so this heavyweight, live-LLM,
 * model-dependent set runs by intention before a release — not in normal CI.
 *
 * <p><b>Deterministic Arrange, model-free.</b> Only the manual prompt under test (the <i>Act</i>) is
 * allowed to exercise the model. Preconditions are set deterministically: seed the current week via
 * {@link MiseAIIT#seedHouseholdAndActivePlan()} (dates derive from {@code LocalDate.now()}), then place
 * known meals by writing {@code recipeRef} directly through {@link #mealRepository} (no model, and — unlike
 * {@code planService.swapMeal} — no {@code MealEdit} audit row, which would confound edit-based
 * assertions like #1). The exceptions are #2/#3, whose documented flow assumes a prior edit exists, so
 * those deliberately use {@code planService.swapMeal} in setup. Assertions are <b>structural</b> (a swap
 * happened, total under budget, a PLANNED week exists, …) — never the manual's exact euro figures, which
 * come from one run and won't reproduce.
 */
@Tag("manual-example")
class ManualExamplesAIIT extends MiseAIIT {

    @Autowired
    private MealCostCalculator mealCostCalculator;

    /** Present for example #6 (viewed-week grounding); unstubbed it returns null → active plan. */
    @MockitoBean
    private ViewedWeekState viewedWeekState;

    // ── #1 — "Make Wednesday vegetarian, kid is having a friend over." ──────────
    @Test
    void makeWednesdayVegetarian_swapsToVegetarianRecipe() {
        var hh = seedHouseholdAndActivePlan();
        var plan = planService.findActivePlan(hh.getId()).orElseThrow();
        var wednesday = findByDay(plan.getId(), DayOfWeek.WEDNESDAY);
        // Deterministic precondition (no MealEdit): Wednesday starts as a meat dish.
        forceRecipe(wednesday, "spaghetti-bolognese");

        var reply = planChat()
                .prompt()
                .user("Make Wednesday vegetarian, kid is having a friend over.")
                .call()
                .content();

        var after = mealRepository.findById(wednesday.getId()).orElseThrow();
        var newRecipe = recipeCatalog.findById(after.getRecipeRef()).orElseThrow();
        Assertions.assertThat(newRecipe.getCategoryTags())
                .as("Wednesday's new recipe must be vegetarian/vegan. Recipe=%s tags=%s. Reply: \"%s\"",
                        newRecipe.getId(), newRecipe.getCategoryTags(), reply)
                .containsAnyOf("vegetarian", "vegan");
        Assertions.assertThat(mealEditRepository.findByMealIdOrderByChangedAtDesc(wednesday.getId()))
                .as("The vegetarian change must be recorded as a MealEdit. Reply: \"%s\"", reply)
                .isNotEmpty();
    }

    // ── #2 — "Why did you change that?" ────────────────────────────────────────
    @Test
    void whyDidYouChangeThat_citesStoredReason() {
        var hh = seedHouseholdAndActivePlan();
        var plan = planService.findActivePlan(hh.getId()).orElseThrow();
        var wednesday = findByDay(plan.getId(), DayOfWeek.WEDNESDAY);
        // Documented flow assumes a prior edit — production swap records the reason.
        planService.swapMeal(wednesday.getId(), differentRecipe(wednesday.getRecipeRef()),
                "Swapped to a cheaper vegetarian dish to stay under the weekly budget");

        // "that" is a conversational follow-up — give it the same context the persisted chat would
        // (the prior change turn) so the model resolves the referent and calls explainEdit. The prior
        // assistant line is deliberately generic (no reason words), so the cited reason can only come
        // from the stored MealEdit via explainEdit — not from echoing the conversation.
        var reply = planChat()
                .prompt()
                .messages(new UserMessage("Make Wednesday vegetarian, kid is having a friend over."),
                        new AssistantMessage("Done — I updated Wednesday's dinner."))
                .user("Why did you change that?")
                .call()
                .content();

        // Assert on the cost/budget reason (not "vegetarian", which the prior user turn already said)
        // so a pass proves the stored reason was retrieved, not echoed.
        Assertions.assertThat(reply.toLowerCase(Locale.ROOT))
                .as("Reply must cite the stored cost/budget reason (via explainEdit), not echo the request. Reply: \"%s\"", reply)
                .containsAnyOf("cheaper", "budget");
    }

    // ── #3 — "Put Wednesday's old meal back." ──────────────────────────────────
    @Test
    void putWednesdaysOldMealBack_undoesTheEdit() {
        var hh = seedHouseholdAndActivePlan();
        var plan = planService.findActivePlan(hh.getId()).orElseThrow();
        var wednesday = findByDay(plan.getId(), DayOfWeek.WEDNESDAY);
        var original = wednesday.getRecipeRef();
        planService.swapMeal(wednesday.getId(), differentRecipe(original), "Test setup — initial swap");

        planChat()
                .prompt()
                .user("Put Wednesday's old meal back.")
                .call()
                .content();

        var after = mealRepository.findById(wednesday.getId()).orElseThrow();
        Assertions.assertThat(after.getRecipeRef())
                .as("Wednesday must be restored to its pre-swap recipe")
                .isEqualTo(original);
        Assertions.assertThat(mealEditRepository.findByMealIdOrderByChangedAtDesc(wednesday.getId()))
                .as("Undo must write its own MealEdit audit row")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    // ── #4 — "Pin Saturday — I'm hosting." ─────────────────────────────────────
    @Test
    void pinSaturday_setsPinnedFlag() {
        var hh = seedHouseholdAndActivePlan();
        var plan = planService.findActivePlan(hh.getId()).orElseThrow();
        var saturday = findByDay(plan.getId(), DayOfWeek.SATURDAY);

        var reply = planChat()
                .prompt()
                .user("Pin Saturday — I'm hosting.")
                .call()
                .content();

        Assertions.assertThat(mealRepository.findById(saturday.getId()).orElseThrow().isPinned())
                .as("Saturday's meal must be pinned. Reply: \"%s\"", reply)
                .isTrue();
    }

    // ── #5 — "Get this week under €80 without dropping Sunday's cod." ───────────
    @Test
    void getWeekUnder80_respectingSundaysCod() {
        // Household size 3 so a costly week clears €80 under live ingredient pricing — a size-2 week
        // tops out ~€70 from this catalog, making "under €80" already-satisfied and the example moot.
        var h = new Household();
        h.setName("Budget HH " + System.nanoTime());
        h.setSize(3);
        h.setWeeklyBudget(new BigDecimal("100.00"));
        h.setAllergies(List.of());
        h.setHatedFoods(List.of());
        var hh = householdService.save(h);
        planService.generateActivePlan(hh, recipeCatalog);
        var plan = planService.findActivePlan(hh.getId()).orElseThrow();
        // Deterministic >€80 week (no MealEdit rows): costly mains + cod on Sunday.
        String[] costly = {"turkey-roast", "beef-stew", "salmon-pasta", "meatballs", "pork-chops", "chicken-rice"};
        int i = 0;
        for (Meal m : planService.findMeals(plan.getId())) {
            forceRecipe(m, m.getDate().getDayOfWeek() == DayOfWeek.SUNDAY ? "baked-cod" : costly[i++ % costly.length]);
        }
        double before = weekTotal(plan.getId());
        Assertions.assertThat(before)
                .as("Test setup sanity: the seeded week must start above €80 (was €%.2f)", before)
                .isGreaterThan(80.0);

        var reply = planChat()
                .prompt()
                .user("Get this week under €80 without dropping Sunday's cod.")
                .call()
                .content();

        double after = weekTotal(plan.getId());
        Assertions.assertThat(after)
                .as("Week total must be brought under €80 (was €%.2f, now €%.2f). Reply: \"%s\"",
                        before, after, reply)
                .isLessThanOrEqualTo(80.0);
        Assertions.assertThat(findByDay(plan.getId(), DayOfWeek.SUNDAY).getRecipeRef())
                .as("Sunday's cod must be preserved. Reply: \"%s\"", reply)
                .isEqualTo("baked-cod");
    }

    // ── #6 — "What's on Wednesday?" (while viewing an earlier week) ─────────────
    @Test
    void whatsOnWednesday_whileViewingEarlierWeek() {
        var hh = seedHouseholdAndActivePlan();
        var active = planService.findActivePlan(hh.getId()).orElseThrow();
        forceRecipe(findByDay(active.getId(), DayOfWeek.WEDNESDAY), "lentil-soup"); // must NOT be named

        List<Plan> history = planService.seedHistory(hh, 1, recipeCatalog);
        var historical = history.get(0);
        forceRecipe(findByDay(historical.getId(), DayOfWeek.WEDNESDAY), "baked-cod"); // the canary

        LocalDate lastMonday = PlanService.currentWeekMonday().minusWeeks(1);
        when(viewedWeekState.getCurrentParam()).thenReturn(lastMonday.toString());

        var reply = planChat()
                .prompt()
                .user("What's on Wednesday?")
                .call()
                .content();

        String lower = reply.toLowerCase(Locale.ROOT);
        Assertions.assertThat(lower)
                .as("Must name the viewed (historical) week's Wednesday meal 'baked cod'. Reply: \"%s\"", reply)
                .contains("baked cod");
        Assertions.assertThat(lower)
                .as("Must NOT name the active week's Wednesday meal 'lentil soup'. Reply: \"%s\"", reply)
                .doesNotContain("lentil soup");
    }

    // ── #7 — "Plan next week." ─────────────────────────────────────────────────
    @Test
    void planNextWeek_createsOnePlannedWeek() {
        var hh = seedHouseholdAndActivePlan();

        planningChat().prompt().user("Plan next week.").call().content();

        LocalDate expected = LocalDate.now().with(previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1);
        var planned = planRepository.findByHouseholdIdAndStatusOrderByWeekStartDateAsc(
                hh.getId(), Plan.Status.PLANNED);
        Assertions.assertThat(planned).as("Exactly one PLANNED week must be created").hasSize(1);
        Assertions.assertThat(planned.get(0).getWeekStartDate())
                .as("PLANNED week must start next Monday (%s)", expected).isEqualTo(expected);
        Assertions.assertThat(mealRepository.findByPlanId(planned.get(0).getId()))
                .as("The planned week must have 7 meals").hasSize(7);
    }

    // ── #8 — "Plan the rest of <month>." ───────────────────────────────────────
    @Test
    void planTheRestOfMonth_resolvesRemainingMondays() {
        var hh = seedHouseholdAndActivePlan();
        LocalDate today = LocalDate.now();
        LocalDate activeMonday = today.with(previousOrSame(DayOfWeek.MONDAY));
        String monthName = today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        Set<LocalDate> expected = new TreeSet<>();
        for (LocalDate m = activeMonday.plusWeeks(1);
             m.getMonthValue() == today.getMonthValue() && m.getYear() == today.getYear();
             m = m.plusWeeks(1)) {
            expected.add(m);
        }

        var reply = planningChat().prompt().user("Plan the rest of " + monthName + ".").call().content();

        Set<LocalDate> actual = planRepository
                .findByHouseholdIdAndStatusOrderByWeekStartDateAsc(hh.getId(), Plan.Status.PLANNED)
                .stream().map(Plan::getWeekStartDate).collect(Collectors.toSet());
        Assertions.assertThat(actual)
                .as("'Plan the rest of %s' must create exactly that month's Mondays after the active "
                        + "week (%s). Reply: \"%s\"", monthName, expected, reply)
                .isEqualTo(expected);
    }

    // ── #9 — "Should I bother with Lido this week?" ────────────────────────────
    @Test
    void shouldIBotherWithLido_givesGroundedVerdict() {
        seedHouseholdAndActivePlan();

        var reply = shoppingChat().prompt().user("Should I bother with Lido this week?").call().content();

        Assertions.assertThat(reply)
                .as("Reply must cite Lido with a € amount, or give a clear verdict. Reply: \"%s\"", reply)
                .satisfiesAnyOf(
                        r -> {
                            Assertions.assertThat(r.toLowerCase(Locale.ROOT)).contains("lido");
                            Assertions.assertThat(r).containsPattern(Pattern.compile("€\\s*\\d"));
                        },
                        r -> Assertions.assertThat(r.toLowerCase(Locale.ROOT)).containsAnyOf(
                                "not worth", "not really", "no need", "skip", "don't bother",
                                "worth it", "worth the trip", "worth a detour"));
        var euro = Pattern.compile("€\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(reply);
        while (euro.find()) {
            Assertions.assertThat(Double.parseDouble(euro.group(1)))
                    .as("Fabrication guard: Lido saving/cost cannot exceed the €100 plan budget. Reply: \"%s\"", reply)
                    .isLessThanOrEqualTo(100.0);
        }
    }

    // ── #10 — "I want the savings without the detour." ─────────────────────────
    @Test
    void savingsWithoutDetour_presentsSwapsWithoutApplying() {
        seedHouseholdAndActivePlan();
        long editsBefore = mealEditRepository.count();

        // Conversational follow-up — give "the detour" its referent (the prior Lido question), as the
        // persisted chat would, so the model knows which store to route around instead of asking.
        var reply = shoppingChat()
                .prompt()
                .messages(new UserMessage("Should I bother with Lido this week?"),
                        new AssistantMessage("Lido would save a little, but it's a second stop."))
                .user("I want the savings without the detour.")
                .call()
                .content();

        String lower = reply.toLowerCase(Locale.ROOT);
        Assertions.assertThat(lower)
                .as("Reply must surface swap context (day / recipe) or refuse cleanly. Reply: \"%s\"", reply)
                .satisfiesAnyOf(
                        r -> Assertions.assertThat(r).containsAnyOf("monday", "tuesday", "wednesday",
                                "thursday", "friday", "saturday", "sunday"),
                        r -> Assertions.assertThat(r).containsAnyOf("salmon", "tuna", "beef", "chicken",
                                "pasta", "stew", "soup", "risotto", "gratin", "pork", "turkey", "cod",
                                "lentil", "veggie", "vegetable", "meatball"),
                        r -> Assertions.assertThat(r).containsAnyOf("no swap", "not needed",
                                "already", "one store", "no beneficial"));
        Assertions.assertThat(mealEditRepository.count())
                .as("BR-04: suggesting swaps must NOT auto-apply any (no new MealEdit rows). Reply: \"%s\"", reply)
                .isEqualTo(editsBefore);
    }

    // ── #11 — "Why was last week more expensive?" ──────────────────────────────
    @Test
    void whyWasLastWeekMoreExpensive_isGrounded() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);

        var reply = reportsChat().prompt().user("Why was last week more expensive?").call().content();

        Assertions.assertThat(reply.toLowerCase(Locale.ROOT))
                .as("Reply must cite real meals/weeks, a € figure, or explain there's no data — never "
                        + "invent facts. Reply: \"%s\"", reply)
                .satisfiesAnyOf(
                        r -> Assertions.assertThat(r).containsAnyOf("baked cod", "beef stew", "chicken",
                                "lentil soup", "meatball", "minced meat", "pea risotto", "pork chop",
                                "potato gratin", "salmon pasta", "spaghetti", "tuna pasta", "turkey",
                                "vegetable", "veggie"),
                        r -> Assertions.assertThat(r).containsAnyOf("€", "eur"),
                        r -> Assertions.assertThat(r).containsAnyOf("don't have", "no data",
                                "not enough", "which week", "clarify"));
    }

    // ── #14 — "Reset the leaderboard." ─────────────────────────────────────────
    @Test
    void resetTheLeaderboard_deletesViewPreference() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);
        viewPreferenceService.saveSettings(hh.getId(), ViewPreference.View.REPORTS,
                "leaderboard", Map.of("query", "SELECT recipe_name AS \"Meal\" FROM meal_history"));

        var reply = reportsChat().prompt().user("Reset the leaderboard.").call().content();

        Assertions.assertThat(viewPreferenceService.getSettings(
                        hh.getId(), ViewPreference.View.REPORTS, "leaderboard"))
                .as("The leaderboard ViewPreference must be deleted after reset. Reply: \"%s\"", reply)
                .isEmpty();
    }

    // ── #15 — "Chart my carbon footprint per meal." (graceful refusal) ─────────
    @Test
    void carbonFootprint_isGracefullyRefused() {
        var hh = seedHouseholdAndActivePlan();
        seedFourWeeksHistory(hh);

        var reply = reportsChat().prompt().user("Chart my carbon footprint per meal.").call().content();

        String lower = reply.toLowerCase(Locale.ROOT);
        Assertions.assertThat(lower)
                .as("Reply must say carbon data isn't available (no fabricated figure). Reply: \"%s\"", reply)
                .containsAnyOf("don't have", "do not have", "doesn't include", "does not include",
                        "no carbon", "no column", "not in the schema", "schema doesn't", "don't track",
                        "do not track", "not available", "isn't available", "is not available",
                        "can't", "cannot", "unable", "no data");
        Assertions.assertThat(lower)
                .as("Reply must not state a fabricated kg-CO2 figure. Reply: \"%s\"", reply)
                .doesNotContain("kg co2", "kgco2", "co2e");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Meal findByDay(Long planId, DayOfWeek day) {
        return planService.findMeals(planId).stream()
                .filter(m -> m.getDate().getDayOfWeek() == day)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Plan " + planId + " missing " + day));
    }

    /** Deterministic, model-free, audit-free precondition: set a meal's recipe directly. */
    private void forceRecipe(Meal meal, String recipeRef) {
        meal.setRecipeRef(recipeRef);
        mealRepository.save(meal);
    }

    private String differentRecipe(String avoid) {
        return recipeCatalog.findAll().stream().map(Recipe::getId)
                .filter(id -> !id.equals(avoid)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Catalog needs ≥2 recipes"));
    }

    private double weekTotal(Long planId) {
        return planService.findMeals(planId).stream()
                .map(mealCostCalculator::costFor)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .doubleValue();
    }
}
