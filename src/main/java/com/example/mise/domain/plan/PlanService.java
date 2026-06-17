package com.example.mise.domain.plan;

import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.household.Household;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Creates and manages weekly dinner plans.
 * Plan generation picks 7 distinct recipes per week honoring allergies (hard exclusions)
 * and soft-avoiding hated foods.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final PlanRepository planRepository;
    private final MealRepository mealRepository;
    private final MealEditRepository mealEditRepository;

    public PlanService(PlanRepository planRepository,
                       MealRepository mealRepository,
                       MealEditRepository mealEditRepository) {
        this.planRepository = planRepository;
        this.mealRepository = mealRepository;
        this.mealEditRepository = mealEditRepository;
    }

    /**
     * Returns the active plan for a household, if any.
     *
     * <p>UC-011 (BR-06): before reading, runs the on-demand status-promotion sweep so that
     * if the real-world week has rolled over into a {@code PLANNED} week, that plan is promoted
     * to {@code ACTIVE} (and the prior {@code ACTIVE} demoted to {@code HISTORICAL}) inside this
     * one transaction. No scheduled job — the first {@code findActivePlan} after a rollover does it.
     */
    @Transactional
    public Optional<Plan> findActivePlan(Long householdId) {
        promoteRolledOverWeeks(householdId);
        return planRepository.findByHouseholdIdAndStatus(householdId, Plan.Status.ACTIVE);
    }

    /**
     * UC-011 (BR-06): on-demand promotion of a {@code PLANNED} week to {@code ACTIVE} when the
     * real-world date has moved past the current active week. Public so it can be invoked /
     * unit-tested directly through the Spring proxy; {@link #findActivePlan} calls the same
     * private implementation inside its own transaction.
     */
    @Transactional
    public void promoteIfRolledOver(Long householdId) {
        promoteRolledOverWeeks(householdId);
    }

    /**
     * Swaps statuses when the active week has ended and a PLANNED plan covers the current week.
     * Preserves the "exactly one ACTIVE plan per household" invariant (UC-002 BR-01):
     * <ul>
     *   <li>If the active week is still current (or future-dated), this is a no-op.</li>
     *   <li>If the active week has ended but no PLANNED plan covers the current week (a gap),
     *       the now-stale ACTIVE is left in place rather than dropped — never zero ACTIVE plans.</li>
     *   <li>Otherwise the prior ACTIVE and any PLANNED weeks that have already elapsed are demoted
     *       to HISTORICAL, and the PLANNED plan whose week contains today is promoted to ACTIVE.</li>
     * </ul>
     * Runs inside the caller's transaction (plain private call — not self-invoked through the proxy).
     */
    private void promoteRolledOverWeeks(Long householdId) {
        var activeOpt = planRepository.findByHouseholdIdAndStatus(householdId, Plan.Status.ACTIVE);
        if (activeOpt.isEmpty()) return;
        var active = activeOpt.get();

        LocalDate currentMonday = currentWeekMonday();
        // Active week is still the current week (or, defensively, future-dated): nothing to do.
        if (!active.getWeekStartDate().isBefore(currentMonday)) return;

        var planned = planRepository
                .findByHouseholdIdAndStatusOrderByWeekStartDateAsc(householdId, Plan.Status.PLANNED);
        Plan promote = planned.stream()
                .filter(p -> p.getWeekStartDate().equals(currentMonday))
                .findFirst().orElse(null);
        if (promote == null) {
            // Gap — no plan covers the current week. Keep the (stale) ACTIVE so the invariant holds.
            log.debug("Active week {} has ended but no PLANNED plan covers current week {}; leaving ACTIVE in place",
                    active.getWeekStartDate(), currentMonday);
            return;
        }

        active.setStatus(Plan.Status.HISTORICAL);
        planRepository.save(active);
        // Any PLANNED weeks strictly in the past have elapsed without promotion → HISTORICAL.
        for (Plan p : planned) {
            if (p.getWeekStartDate().isBefore(currentMonday)) {
                p.setStatus(Plan.Status.HISTORICAL);
                planRepository.save(p);
            }
        }
        promote.setStatus(Plan.Status.ACTIVE);
        planRepository.save(promote);
        log.info("UC-011 rollover: promoted PLANNED week {} to ACTIVE; demoted prior ACTIVE week {}",
                currentMonday, active.getWeekStartDate());
    }

    /** Returns all plans for a household, most recent first. */
    @Transactional(readOnly = true)
    public List<Plan> findAllPlans(Long householdId) {
        return planRepository.findByHouseholdIdOrderByWeekStartDateDesc(householdId);
    }

    /** Returns all plans for a household, oldest first. */
    @Transactional(readOnly = true)
    public List<Plan> findAllPlansOrderedAsc(Long householdId) {
        var desc = planRepository.findByHouseholdIdOrderByWeekStartDateDesc(householdId);
        if (desc.isEmpty()) return desc;
        var asc = new java.util.ArrayList<>(desc);
        java.util.Collections.reverse(asc);
        return asc;
    }

    /** Returns the plan for a specific week start date, if any. */
    @Transactional(readOnly = true)
    public Optional<Plan> findByWeekStartDate(Long householdId, LocalDate weekStartDate) {
        return planRepository.findByHouseholdIdAndWeekStartDate(householdId, weekStartDate);
    }

    /** Returns meals for a given plan. */
    @Transactional(readOnly = true)
    public List<Meal> findMeals(Long planId) {
        return mealRepository.findByPlanIdOrderByDateAsc(planId);
    }

    /**
     * Generates the current active week's plan for the household.
     * weekStartDate is the Monday of the current week.
     */
    /** Max future weeks a single {@link #generatePlannedWeeks} call may create (UC-011 BR-05). */
    public static final int MAX_WEEKS_PER_CALL = 8;

    @Transactional
    public Plan generateActivePlan(Household household, RecipeCatalog recipeCatalog) {
        LocalDate monday = currentWeekMonday();
        return generatePlan(household, recipeCatalog, monday,
                Plan.Status.ACTIVE, Meal.Status.PLANNED, Meal.Editor.USER);
    }

    /**
     * UC-011: generates one or more future {@code PLANNED} weeks in the inclusive Monday range
     * {@code [fromMonday, throughMonday]}, reusing the same eligible-recipe pipeline as
     * {@link #generateActivePlan} (BR-04).
     *
     * <p>Enforces the business rules: forward-only (BR-02 — anything before "active week + 1" is
     * clamped forward), idempotent per week (BR-03 — existing weeks are skipped), and an 8-week cap
     * on weeks actually created (BR-05). Inputs are snapped to Monday defensively. Runs a rollover
     * sweep first so "earliest allowed" is computed against the true current ACTIVE week.
     *
     * @return a {@link PlannedWeeksResult} describing what was created / skipped (for the tool's
     *         non-fabricated summary, BR-08)
     */
    @Transactional
    public PlannedWeeksResult generatePlannedWeeks(Household household, LocalDate fromMonday,
                                                   LocalDate throughMonday, RecipeCatalog recipeCatalog) {
        promoteRolledOverWeeks(household.getId());

        var activeOpt = planRepository.findByHouseholdIdAndStatus(household.getId(), Plan.Status.ACTIVE);
        if (activeOpt.isEmpty()) {
            return new PlannedWeeksResult(List.of(), List.of(), false, null, true);
        }
        // BR-02: earliest plannable Monday is the week after the current ACTIVE week.
        LocalDate earliestAllowed = activeOpt.get().getWeekStartDate().plusWeeks(1);

        LocalDate start = snapMonday(fromMonday);
        LocalDate end = snapMonday(throughMonday);
        if (start == null || end == null) {
            return new PlannedWeeksResult(List.of(), List.of(), false, earliestAllowed, false);
        }
        // Clamp the start forward to the earliest allowed week (BR-02).
        if (start.isBefore(earliestAllowed)) start = earliestAllowed;
        if (end.isBefore(start)) {
            // Whole request resolved to the past / current week — nothing to generate.
            return new PlannedWeeksResult(List.of(), List.of(), false, earliestAllowed, false);
        }

        List<Plan> created = new ArrayList<>();
        List<LocalDate> skipped = new ArrayList<>();
        LocalDate monday = start;
        while (!monday.isAfter(end) && created.size() < MAX_WEEKS_PER_CALL) {
            boolean exists = planRepository
                    .findByHouseholdIdAndWeekStartDate(household.getId(), monday).isPresent();
            if (exists) {
                skipped.add(monday); // BR-03 idempotence: leave existing week untouched
            } else {
                created.add(generatePlan(household, recipeCatalog, monday,
                        Plan.Status.PLANNED, Meal.Status.PLANNED, Meal.Editor.AI));
            }
            monday = monday.plusWeeks(1);
        }
        // BR-05: cap hit only if we stopped at the limit with weeks still left in the requested range.
        boolean capHit = created.size() >= MAX_WEEKS_PER_CALL && !monday.isAfter(end);
        return new PlannedWeeksResult(created, skipped, capHit, earliestAllowed, false);
    }

    private static LocalDate snapMonday(LocalDate any) {
        return any == null ? null : any.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * Seeds {@code seedWeeks} historical plans going back from the current week.
     * Each historical week uses COOKED status on meals.
     */
    @Transactional
    public List<Plan> seedHistory(Household household, int seedWeeks, RecipeCatalog recipeCatalog) {
        LocalDate monday = currentWeekMonday();
        List<Plan> seeded = new ArrayList<>();
        for (int i = 1; i <= seedWeeks; i++) {
            LocalDate weekStart = monday.minusWeeks(i);
            var plan = generatePlan(household, recipeCatalog, weekStart,
                    Plan.Status.HISTORICAL, Meal.Status.COOKED, Meal.Editor.USER);
            seeded.add(plan);
        }
        return seeded;
    }

    /** Returns Monday of the current week. */
    public static LocalDate currentWeekMonday() {
        LocalDate today = LocalDate.now();
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private Plan generatePlan(Household household, RecipeCatalog recipeCatalog,
                               LocalDate weekStart, Plan.Status planStatus, Meal.Status mealStatus,
                               Meal.Editor editor) {
        var plan = new Plan();
        plan.setHouseholdId(household.getId());
        plan.setWeekStartDate(weekStart);
        plan.setStatus(planStatus);
        var savedPlan = planRepository.save(plan);

        // Pick 7 recipes for the week
        List<Recipe> eligible = eligibleRecipes(recipeCatalog, household);
        List<Recipe> chosen = pickDistinct(eligible, 7);

        for (int day = 0; day < 7; day++) {
            var meal = new Meal();
            meal.setPlanId(savedPlan.getId());
            meal.setDate(weekStart.plusDays(day));
            meal.setSlot(Meal.Slot.DINNER);
            meal.setServings(household.getSize());
            meal.setStatus(mealStatus);
            meal.setLastEditedBy(editor);

            if (day < chosen.size()) {
                meal.setRecipeRef(chosen.get(day).getId());
            } else {
                // Fallback: reuse first recipe
                meal.setRecipeRef(chosen.isEmpty() ? "unknown" : chosen.get(0).getId());
            }
            mealRepository.save(meal);
        }

        log.info("Generated {} plan for week {} with {} meals",
                planStatus, weekStart, Math.min(chosen.size(), 7));
        return savedPlan;
    }

    /**
     * Returns all recipes that pass the hard allergy constraint.
     * Soft-avoids hated foods but includes them if we'd otherwise fall below 7.
     */
    private List<Recipe> eligibleRecipes(RecipeCatalog catalog, Household household) {
        var all = catalog.findAll();
        var allergies = household.getAllergies();
        var hated = household.getHatedFoods();

        // Hard filter: remove allergen-containing recipes
        var noAllergens = all.stream()
                .filter(r -> allergies == null || allergies.stream().noneMatch(r::containsAllergen))
                .toList();

        // Soft filter: prefer non-hated
        var preferred = noAllergens.stream()
                .filter(r -> hated == null || hated.stream().noneMatch(t -> r.getName().toLowerCase().contains(t.toLowerCase())))
                .toList();

        // Use preferred if we have at least 7; otherwise fall back to noAllergens
        return preferred.size() >= 7 ? new ArrayList<>(preferred) : new ArrayList<>(noAllergens);
    }

    /**
     * Toggles the pinned flag on a meal, updating audit fields.
     */
    @Transactional
    public void pinMeal(Long mealId, boolean pinned) {
        var meal = mealRepository.findById(mealId)
                .orElseThrow(() -> new IllegalArgumentException("Meal not found: " + mealId));
        meal.setPinned(pinned);
        meal.setLastEditedAt(java.time.Instant.now());
        meal.setLastEditedBy(Meal.Editor.USER);
        mealRepository.save(meal);
    }

    /**
     * Updates the status of a meal (PLANNED, EDITED, COOKED, SKIPPED).
     */
    @Transactional
    public void markStatus(Long mealId, Meal.Status status) {
        var meal = mealRepository.findById(mealId)
                .orElseThrow(() -> new IllegalArgumentException("Meal not found: " + mealId));
        meal.setStatus(status);
        meal.setLastEditedAt(java.time.Instant.now());
        meal.setLastEditedBy(Meal.Editor.USER);
        mealRepository.save(meal);
    }

    /** Find a meal by plan and date. */
    @Transactional(readOnly = true)
    public Optional<Meal> findMealByDate(Long planId, LocalDate date) {
        return mealRepository.findByPlanIdOrderByDateAsc(planId).stream()
                .filter(m -> m.getDate().equals(date))
                .findFirst();
    }

    /**
     * Swaps the recipe on a single meal, recording a {@link MealEdit} audit row.
     * Throws {@link PinnedMealException} if the meal is pinned.
     */
    @Transactional
    public MealEdit swapMeal(Long mealId, String newRecipeRef, String reason) {
        var meal = mealRepository.findById(mealId)
                .orElseThrow(() -> new IllegalArgumentException("Meal not found: " + mealId));

        if (meal.isPinned()) {
            throw new PinnedMealException(
                    "Meal on " + meal.getDate() + " (" + meal.getRecipeRef() + ") is pinned and cannot be changed.");
        }

        // Capture previous state before mutating
        var edit = new MealEdit();
        edit.setMealId(mealId);
        edit.setPreviousRecipeRef(meal.getRecipeRef());
        edit.setPreviousServings(meal.getServings());
        edit.setPreviousStatus(meal.getStatus());
        edit.setChangedBy(Meal.Editor.AI);
        edit.setReason(reason);

        // Mutate meal
        meal.setRecipeRef(newRecipeRef);
        meal.setStatus(Meal.Status.EDITED);
        meal.setLastEditedBy(Meal.Editor.AI);
        meal.setLastEditedAt(Instant.now());
        mealRepository.save(meal);

        return mealEditRepository.save(edit);
    }

    /**
     * Atomically applies multiple swaps (UC-003 constraint negotiation).
     * If any swap targets a pinned meal the whole transaction rolls back.
     */
    @Transactional
    public List<MealEdit> negotiateWeek(Long planId, List<MealSwapRequest> swaps, String reason) {
        var edits = new ArrayList<MealEdit>();
        for (var swap : swaps) {
            // swapMeal validates pin state; PinnedMealException propagates and rolls back the tx
            edits.add(swapMeal(swap.mealId(), swap.newRecipeRef(), reason));
        }
        return edits;
    }

    /**
     * Sets the pinned flag on a meal with an explicit editor identity.
     * Keeps the existing {@link #pinMeal(Long, boolean)} (hardcoded USER) for UI handlers.
     */
    @Transactional
    public void setPinned(Long mealId, boolean pinned, Meal.Editor changedBy) {
        var meal = mealRepository.findById(mealId)
                .orElseThrow(() -> new IllegalArgumentException("Meal not found: " + mealId));
        meal.setPinned(pinned);
        meal.setLastEditedAt(Instant.now());
        meal.setLastEditedBy(changedBy);
        mealRepository.save(meal);
    }

    /**
     * Returns all edits for a given meal, newest first.
     * Used by UC-004 "why?" queries.
     */
    @Transactional(readOnly = true)
    public List<MealEdit> findEdits(Long mealId) {
        return mealEditRepository.findByMealIdOrderByChangedAtDesc(mealId);
    }

    /**
     * UC-004: Undoes the most recent edit for the given meal by restoring the
     * previous recipe ref, servings, and status from the latest {@link MealEdit} row.
     * Writes a new {@link MealEdit} row documenting the revert (BR-03).
     * Refuses if the meal is currently pinned.
     *
     * @param mealId    the meal to revert
     * @param changedBy whether the undo was initiated by USER or AI
     * @return the newly created {@link MealEdit} documenting the revert
     * @throws PinnedMealException      if the meal is pinned
     * @throws IllegalArgumentException if the meal has no edit history
     */
    @Transactional
    public MealEdit undoLastEdit(Long mealId, Meal.Editor changedBy) {
        var meal = mealRepository.findById(mealId)
                .orElseThrow(() -> new IllegalArgumentException("Meal not found: " + mealId));

        if (meal.isPinned()) {
            throw new PinnedMealException(
                    "Meal on " + meal.getDate() + " (" + meal.getRecipeRef() + ") is pinned and cannot be undone.");
        }

        var edits = mealEditRepository.findByMealIdOrderByChangedAtDesc(mealId);
        if (edits.isEmpty()) {
            throw new IllegalArgumentException("No edit history for meal " + mealId);
        }

        var lastEdit = edits.get(0);

        // Capture current state before reverting (to record in the undo MealEdit row)
        String currentRecipeRef = meal.getRecipeRef();
        int currentServings = meal.getServings();
        Meal.Status currentStatus = meal.getStatus();

        // Restore previous state
        meal.setRecipeRef(lastEdit.getPreviousRecipeRef());
        meal.setServings(lastEdit.getPreviousServings());
        meal.setStatus(lastEdit.getPreviousStatus());
        meal.setLastEditedBy(changedBy);
        meal.setLastEditedAt(Instant.now());
        mealRepository.save(meal);

        // Write undo audit row (BR-03)
        var undoEdit = new MealEdit();
        undoEdit.setMealId(mealId);
        undoEdit.setPreviousRecipeRef(currentRecipeRef);
        undoEdit.setPreviousServings(currentServings);
        undoEdit.setPreviousStatus(currentStatus);
        undoEdit.setChangedBy(changedBy);
        String undoReason = "Undo of edit #" + lastEdit.getId()
                + " (was: " + (lastEdit.getReason() != null ? lastEdit.getReason() : "no reason recorded") + ")";
        undoEdit.setReason(undoReason);

        return mealEditRepository.save(undoEdit);
    }

    private List<Recipe> pickDistinct(List<Recipe> pool, int count) {
        if (pool.isEmpty()) return List.of();
        var shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }
}
