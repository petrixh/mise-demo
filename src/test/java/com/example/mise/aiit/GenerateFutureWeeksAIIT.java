package com.example.mise.aiit;

import com.example.mise.domain.household.Household;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.Plan;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static java.time.temporal.TemporalAdjusters.previousOrSame;

/**
 * UC-011 AI integration tests: live LLM + date-grounded system prompt + PlanningTools.
 *
 * <p>All date expectations are derived from {@code LocalDate.now()} at run time — the prompt is
 * grounded with today's real date (via {@link #planningChat()}) and the active week is seeded at the
 * current week, so the tests resolve the same way no matter when they run. Never hard-code absolute
 * dates in assertions here: that silently rots as the calendar advances (it did — see
 * {@link #monthRequest_resolvesToCorrectMondays}).
 *
 * <p>Each test seeds its own household + active plan, sends a single chat turn, and
 * asserts on DB side-effects via {@link com.example.mise.domain.plan.PlanRepository} /
 * {@link com.example.mise.domain.plan.MealRepository}. Reply shape is checked leniently —
 * we only fail on the hallucination combination (claimed success + no DB write).
 */
class GenerateFutureWeeksAIIT extends MiseAIIT {

    @Autowired
    private MealCostCalculator mealCostCalculator;

    // ── Test 1: "Plan next week." creates exactly one PLANNED plan at the correct Monday ──

    /**
     * UC-011 AC#1 — "Plan next week" creates exactly one PLANNED plan whose weekStartDate
     * is the Monday immediately after the current active week (2026-06-22).
     */
    @Test
    void planNextWeek_createsOnePlannedWeekAtCorrectMonday() {
        var hh = seedHouseholdAndActivePlan();

        var reply = planningChat()
                .prompt()
                .user("Plan next week.")
                .call()
                .content();

        LocalDate expectedMonday = LocalDate.now()
                .with(previousOrSame(DayOfWeek.MONDAY))
                .plusWeeks(1);

        var plannedPlans = planRepository.findByHouseholdIdAndStatusOrderByWeekStartDateAsc(
                hh.getId(), Plan.Status.PLANNED);

        Assertions.assertThat(plannedPlans)
                .as("Exactly one PLANNED plan must be created for 'Plan next week'")
                .hasSize(1);

        Assertions.assertThat(plannedPlans.get(0).getWeekStartDate())
                .as("The PLANNED week must start on Monday %s", expectedMonday)
                .isEqualTo(expectedMonday);

        var meals = mealRepository.findByPlanId(plannedPlans.get(0).getId());
        Assertions.assertThat(meals)
                .as("The created plan must have exactly 7 meals (one per day)")
                .hasSize(7);
    }

    // ── Test 2: cost in the reply matches actual DB costs (no fabrication) ──

    /**
     * UC-011 BR-08 anti-fabrication — if the model states a euro amount in its reply,
     * it must be within ±2 of the real computed week cost (rounding tolerance).
     * If the model omits a number, that is acceptable — only a mismatched claim fails.
     */
    @Test
    void costSummaryMatchesPricedMeals_noFabrication() {
        var hh = seedHouseholdAndActivePlan();

        var reply = planningChat()
                .prompt()
                .user("Plan next week.")
                .call()
                .content();

        var plannedPlans = planRepository.findByHouseholdIdAndStatusOrderByWeekStartDateAsc(
                hh.getId(), Plan.Status.PLANNED);

        // If no plan was created (e.g. model refused), skip the cost check — it can't fabricate
        // a cost for a plan that doesn't exist.
        if (plannedPlans.isEmpty()) {
            return;
        }

        List<Meal> meals = mealRepository.findByPlanId(plannedPlans.get(0).getId());
        BigDecimal realCost = meals.stream()
                .map(mealCostCalculator::costFor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long realRounded = realCost.setScale(0, RoundingMode.HALF_UP).longValue();

        // Extract any euro amount the model mentioned (e.g. "€45" or "€ 45").
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("€\\s?(\\d+)")
                .matcher(reply);

        while (m.find()) {
            long stated = Long.parseLong(m.group(1));
            Assertions.assertThat(Math.abs(stated - realRounded))
                    .as("Fabrication: model stated €%d but real week cost is €%d. Reply: \"%s\"",
                            stated, realRounded, reply)
                    .isLessThanOrEqualTo(2L);
        }
    }

    // ── Test 3: "Plan the rest of June." resolves to exactly the right Mondays ──

    /**
     * UC-011 AC#2 — "Plan the rest of &lt;current month&gt;" must create PLANNED plans for exactly the
     * Mondays of the current month that are strictly after the active week (BR-02: earliest allowed =
     * active week + 1). Both the requested month and the expected Mondays are derived from
     * {@code LocalDate.now()} so the test holds on any run date (it previously hard-coded
     * {June 22, June 29} against a {@code LocalDate.now()}-grounded prompt and silently broke once the
     * real date passed mid-June).
     *
     * <p>Edge case: when today is in the last week of the month there are no later Mondays in it, so
     * {@code expected} is empty and the model must create nothing — also a valid BR-02 assertion.
     */
    @Test
    void monthRequest_resolvesToCorrectMondays() {
        var hh = seedHouseholdAndActivePlan();

        LocalDate today = LocalDate.now();
        LocalDate activeMonday = today.with(previousOrSame(DayOfWeek.MONDAY));
        String monthName = today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        // The weeks generatePlannedWeeks would create: every Monday in the current month strictly
        // after the active week. (A month has at most 5 Mondays, so the 8-week cap never bites here.)
        Set<LocalDate> expectedMondays = new java.util.TreeSet<>();
        for (LocalDate m = activeMonday.plusWeeks(1);
             m.getMonthValue() == today.getMonthValue() && m.getYear() == today.getYear();
             m = m.plusWeeks(1)) {
            expectedMondays.add(m);
        }

        var reply = planningChat()
                .prompt()
                .user("Plan the rest of " + monthName + ".")
                .call()
                .content();

        var plannedPlans = planRepository.findByHouseholdIdAndStatusOrderByWeekStartDateAsc(
                hh.getId(), Plan.Status.PLANNED);

        Set<LocalDate> actualMondays = plannedPlans.stream()
                .map(Plan::getWeekStartDate)
                .collect(Collectors.toSet());

        // All created Mondays must be Monday-aligned (sanity).
        actualMondays.forEach(d ->
                Assertions.assertThat(d.getDayOfWeek())
                        .as("Created plan date %s is not a Monday", d)
                        .isEqualTo(DayOfWeek.MONDAY));

        // Core assertion: the created weeks equal the current month's Mondays after the active week.
        Assertions.assertThat(actualMondays)
                .as("'Plan the rest of %s' from %s must produce exactly that month's Mondays after the "
                        + "active week (%s). Reply was: \"%s\"", monthName, today, expectedMondays, reply)
                .isEqualTo(expectedMondays);
    }

    // ── Test 4: second "Plan next week." call is idempotent ──

    /**
     * UC-011 BR-03 idempotence — a second "Plan next week." does not create a duplicate plan.
     * After both turns, exactly one PLANNED plan exists for that Monday.
     */
    @Test
    void idempotentReplan_noDuplicates() {
        var hh = seedHouseholdAndActivePlan();

        planningChat()
                .prompt()
                .user("Plan next week.")
                .call()
                .content();

        long countAfterFirst = planRepository
                .findByHouseholdIdAndStatusOrderByWeekStartDateAsc(hh.getId(), Plan.Status.PLANNED)
                .size();

        var secondReply = planningChat()
                .prompt()
                .user("Plan next week.")
                .call()
                .content();

        var plannedAfterSecond = planRepository
                .findByHouseholdIdAndStatusOrderByWeekStartDateAsc(hh.getId(), Plan.Status.PLANNED);

        Assertions.assertThat(plannedAfterSecond)
                .as("Idempotence: a second 'Plan next week' must not create a duplicate plan (count was %d after first)",
                        countAfterFirst)
                .hasSize((int) countAfterFirst);

        // Soft check: the second reply should acknowledge it was already planned.
        String lower = secondReply.toLowerCase(Locale.ROOT);
        boolean acknowledgesAlreadyPlanned = lower.contains("already") || lower.contains("exists")
                || lower.contains("existing") || lower.contains("skipped") || lower.contains("no new");
        // We don't fail on this — it's a best-effort UX check, not a correctness assertion.
        if (!acknowledgesAlreadyPlanned) {
            System.out.println("[SOFT] Idempotence reply did not contain 'already/exists/skipped'. "
                    + "Reply was: \"" + secondReply + "\"");
        }
    }

    // ── Test 5: "Plan last week." is refused; no PLANNED plans created ──

    /**
     * UC-011 BR-02 — the model (or tool) must refuse a request to plan a past week.
     * Anti-fabrication: zero PLANNED plans must exist after the turn.
     */
    @Test
    void pastWeekRefused_noWrites() {
        var hh = seedHouseholdAndActivePlan();

        var reply = planningChat()
                .prompt()
                .user("Plan last week.")
                .call()
                .content();

        var plannedPlans = planRepository.findByHouseholdIdAndStatusOrderByWeekStartDateAsc(
                hh.getId(), Plan.Status.PLANNED);

        // Anti-fabrication: if any PLANNED plans were created, the model must not have
        // claimed success for a past-week request in a truthful reply.
        String lower = reply.toLowerCase(Locale.ROOT);
        boolean claimedSuccess = (lower.contains("planned") || lower.contains("created")
                || lower.contains("added")) && !lower.contains("already") && !lower.contains("refused");

        Assertions.assertThat(claimedSuccess && !plannedPlans.isEmpty())
                .as("Hallucination: model claimed planning last week succeeded AND DB has PLANNED rows. "
                        + "Reply: \"%s\"", reply)
                .isFalse();

        // Primary assertion: no PLANNED plans written for a past-week request.
        Assertions.assertThat(plannedPlans)
                .as("Requesting 'Plan last week' must produce zero PLANNED plans (BR-02). "
                        + "Reply: \"%s\"", reply)
                .isEmpty();
    }

    // ── Test 6: 8-week cap is enforced ──

    /**
     * UC-011 BR-05 — asking for more than 8 future weeks must produce exactly 8 PLANNED plans,
     * regardless of how many weeks were requested.
     */
    @Test
    void eightWeekCap_enforcedAt8Plans() {
        var hh = seedHouseholdAndActivePlan();

        var reply = planningChat()
                .prompt()
                .user("Plan the next 12 weeks.")
                .call()
                .content();

        var plannedPlans = planRepository.findByHouseholdIdAndStatusOrderByWeekStartDateAsc(
                hh.getId(), Plan.Status.PLANNED);

        Assertions.assertThat(plannedPlans)
                .as("BR-05: planning 12 weeks must be capped at 8 PLANNED plans. "
                        + "Reply: \"%s\"", reply)
                .hasSize(8);

        // Soft check: the reply should mention a limit or offer to continue.
        String lower = reply.toLowerCase(Locale.ROOT);
        boolean mentionsLimit = lower.contains("limit") || lower.contains("8") || lower.contains("eight")
                || lower.contains("continue") || lower.contains("rest") || lower.contains("more");
        if (!mentionsLimit) {
            System.out.println("[SOFT] 8-week cap reply did not mention limit/continue. Reply: \"" + reply + "\"");
        }
    }

    // ── Test 7: allergy filter — no shellfish in a household with shellfish allergy ──

    /**
     * UC-011 BR-01 — for a household with a shellfish allergy, the generated PLANNED plan's
     * meals must not reference any recipe that contains shellfish.
     */
    @Test
    void allergyFilter_noShellfishInPlannedWeek() {
        // Seed a household WITH shellfish allergy.
        var hh = new Household();
        hh.setName("Shellfish Allergy HH " + System.nanoTime());
        hh.setSize(2);
        hh.setWeeklyBudget(new BigDecimal("100.00"));
        hh.setAllergies(List.of("shellfish"));
        hh.setHatedFoods(List.of());
        var saved = householdService.save(hh);
        planService.generateActivePlan(saved, recipeCatalog);

        var reply = planningChat()
                .prompt()
                .user("Plan next week.")
                .call()
                .content();

        var plannedPlans = planRepository.findByHouseholdIdAndStatusOrderByWeekStartDateAsc(
                saved.getId(), Plan.Status.PLANNED);

        if (plannedPlans.isEmpty()) {
            // Model refused or did not create — not a failure (it can't fabricate absent DB rows).
            return;
        }

        List<Meal> meals = mealRepository.findByPlanId(plannedPlans.get(0).getId());
        for (Meal meal : meals) {
            String recipeRef = meal.getRecipeRef();
            var recipeOpt = recipeCatalog.findById(recipeRef);
            recipeOpt.ifPresent(recipe ->
                    Assertions.assertThat(recipe.containsAllergen("shellfish"))
                            .as("Allergy violation: meal on %s references recipe '%s' which contains shellfish. "
                                    + "Reply: \"%s\"", meal.getDate(), recipeRef, reply)
                            .isFalse());
        }
    }
}
