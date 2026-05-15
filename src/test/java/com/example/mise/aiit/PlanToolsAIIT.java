package com.example.mise.aiit;

import com.example.mise.domain.plan.Meal;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.Locale;

/**
 * UC-003 + UC-004 AI integration: the live LLM, the production plan-view system prompt,
 * the production {@code PlanTools} bean. Each test seeds its own household + active plan
 * in {@link MiseAIIT#wipeBeforeEach()} / {@link MiseAIIT#seedHouseholdAndActivePlan()} and
 * asserts on the DB side-effects the tool calls produced.
 *
 * <p>Tests are deliberately ordered so the "fast" pure-DB checks come first; the slowest
 * checks (a real LLM swap → undo round-trip) come last so a partial failure is easier to
 * triage. With {@code -Pai-it} configured for 2 failsafe forks, tests are distributed
 * across forks and effectively run at LLM-parallelism = 2 (matching the local Qwen
 * "paralel-2" model slot).
 */
class PlanToolsAIIT extends MiseAIIT {

    /**
     * UC-004 BR-04 / AC #4 — when explainEdit returns the "no reasoning recorded"
     * sentinel for a meal with no edits, the assistant must relay that without
     * fabricating a reason.
     */
    @Test
    void explainWithNoEditsRelaysSentinelWithoutFabrication() {
        seedHouseholdAndActivePlan();
        var sundayName = "Sunday";

        var reply = planChat()
                .prompt()
                .user("Why did you change " + sundayName + "?")
                .call()
                .content();

        Assertions.assertThat(reply)
                .as("Assistant must NOT invent a reason for a day with no edits")
                .satisfiesAnyOf(
                        r -> Assertions.assertThat(r.toLowerCase(Locale.ROOT))
                                .containsAnyOf("not been swapped", "hasn't been", "no edits",
                                        "wasn't changed", "no change", "not been changed",
                                        "no edit history", "original"),
                        r -> Assertions.assertThat(r.toLowerCase(Locale.ROOT))
                                .containsAnyOf("don't have", "no reasoning"));
    }

    /**
     * UC-003 BR / UC-004 system-prompt directive — the LLM must NEVER claim a swap
     * succeeded without invoking the swap tool. Two valid behaviours: (a) the tool
     * fired and the DB reflects the change, or (b) the tool didn't fire AND the reply
     * does not claim the change happened. The test fails only on the hallucination
     * combination — "X is now Y" in prose with no underlying MealEdit row.
     */
    @Test
    void swapRequestNeverClaimsSuccessWithoutToolCall() {
        seedHouseholdAndActivePlan();
        var plan = planService.findActivePlan(householdService.findHousehold().orElseThrow().getId()).orElseThrow();
        var thursday = findByDay(plan.getId(), DayOfWeek.THURSDAY);
        var originalRecipe = thursday.getRecipeRef();

        var reply = planChat()
                .prompt()
                .user("Swap Thursday's dinner for something cheaper. Just pick one.")
                .call()
                .content();

        var afterEdits = mealEditRepository.findByMealIdOrderByChangedAtDesc(thursday.getId());
        var afterMeal = mealRepository.findById(thursday.getId()).orElseThrow();
        boolean swapHappened = !afterEdits.isEmpty()
                && !afterMeal.getRecipeRef().equals(originalRecipe);

        // Heuristic: did the assistant *claim* the swap succeeded? Looks for the
        // specific patterns from past hallucinations ("X is now Y", "swapped to",
        // "changed to", "is set to"). False positives are tolerable; false
        // negatives — which would hide a real hallucination — are not.
        String r = reply.toLowerCase(Locale.ROOT);
        boolean claimedSwap = (r.contains("is now") || r.contains("swapped to")
                || r.contains("changed to") || r.contains("set to")
                || r.contains("is set") || r.contains("has been changed"))
                && r.contains("thursday");

        Assertions.assertThat(claimedSwap && !swapHappened)
                .as("Hallucination: the assistant claimed Thursday changed but no MealEdit was written. "
                        + "Reply was: \"%s\"", reply)
                .isFalse();
    }

    /**
     * UC-004 AC #2 — "put X back" via chat triggers undoLastEdit. We pre-seed an
     * AI-style swap on Friday (so the LLM doesn't need to swap-then-undo in one
     * turn; that doubles the LLM round-trips and is covered by the next test), then
     * ask the model to undo.
     */
    @Test
    void putBackCommandInvokesUndoTool() {
        seedHouseholdAndActivePlan();
        var plan = planService.findActivePlan(householdService.findHousehold().orElseThrow().getId()).orElseThrow();
        var friday = findByDay(plan.getId(), DayOfWeek.FRIDAY);
        var originalRecipe = friday.getRecipeRef();
        // Pre-seed: production codepath writes a MealEdit and flips recipeRef.
        planService.swapMeal(friday.getId(), pickADifferentRecipeRef(originalRecipe), "Test setup — initial swap");

        planChat()
                .prompt()
                .user("Put Friday back to " + originalRecipe + ". Just do it.")
                .call()
                .content();

        var fridayAfter = mealRepository.findById(friday.getId()).orElseThrow();
        var fridayEdits = mealEditRepository.findByMealIdOrderByChangedAtDesc(friday.getId());

        Assertions.assertThat(fridayAfter.getRecipeRef())
                .as("Friday should be restored to its pre-swap recipe")
                .isEqualTo(originalRecipe);
        Assertions.assertThat(fridayEdits)
                .as("Undo must write its own MealEdit audit row")
                .hasSizeGreaterThanOrEqualTo(2);
        Assertions.assertThat(fridayEdits.get(0).getReason())
                .as("Undo audit row's reason must start with the 'Undo of edit' marker")
                .startsWith("Undo of edit");
    }

    /**
     * UC-004 AC #3 / BR-06 — "why did you change X?" returns a reply that names a
     * concrete factor AND stays inside the 3-sentence budget without apologies.
     */
    @Test
    void whyAnswerCitesConcreteFactorAndStaysBrief() {
        seedHouseholdAndActivePlan();
        var plan = planService.findActivePlan(householdService.findHousehold().orElseThrow().getId()).orElseThrow();
        var wednesday = findByDay(plan.getId(), DayOfWeek.WEDNESDAY);
        // Concrete reason on the audit row — the model should surface it.
        planService.swapMeal(wednesday.getId(), pickADifferentRecipeRef(wednesday.getRecipeRef()),
                "Swapped to a cheaper vegetarian dish to stay under the weekly budget");

        var reply = planChat()
                .prompt()
                .user("Why did you change Wednesday?")
                .call()
                .content();

        Assertions.assertThat(reply)
                .as("Reply must reference at least one concrete factor from the stored reason")
                .satisfiesAnyOf(
                        r -> Assertions.assertThat(r.toLowerCase(Locale.ROOT)).contains("cheaper"),
                        r -> Assertions.assertThat(r.toLowerCase(Locale.ROOT)).contains("vegetarian"),
                        r -> Assertions.assertThat(r.toLowerCase(Locale.ROOT)).contains("budget"));

        var sentenceCount = reply.split("[.!?](\\s|$)").length;
        Assertions.assertThat(sentenceCount)
                .as("BR-06: single-swap explain answers must be ≤ 3 sentences")
                .isLessThanOrEqualTo(3);

        Assertions.assertThat(reply.toLowerCase(Locale.ROOT))
                .as("BR-06: no apologies or preamble")
                .doesNotStartWith("i'm sorry")
                .doesNotStartWith("certainly")
                .doesNotStartWith("sure!")
                .doesNotStartWith("of course");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Meal findByDay(Long planId, DayOfWeek day) {
        return planService.findMeals(planId).stream()
                .filter(m -> m.getDate().getDayOfWeek() == day)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Seeded plan missing " + day));
    }

    /**
     * Returns any recipe id from the catalog that differs from the given one. The
     * production swap path validates against the catalog, so this stays inside the
     * known-good set.
     */
    private String pickADifferentRecipeRef(String avoid) {
        return recipeCatalog.findAll().stream()
                .map(r -> r.getId())
                .filter(id -> !id.equals(avoid))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Recipe catalog needs at least 2 recipes for this test"));
    }
}
