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

    /** Returns the active plan for a household, if any. */
    @Transactional(readOnly = true)
    public Optional<Plan> findActivePlan(Long householdId) {
        return planRepository.findByHouseholdIdAndStatus(householdId, Plan.Status.ACTIVE);
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
    @Transactional
    public Plan generateActivePlan(Household household, RecipeCatalog recipeCatalog) {
        LocalDate monday = currentWeekMonday();
        return generatePlan(household, recipeCatalog, monday, Plan.Status.ACTIVE, Meal.Status.PLANNED);
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
            var plan = generatePlan(household, recipeCatalog, weekStart, Plan.Status.HISTORICAL, Meal.Status.COOKED);
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
                               LocalDate weekStart, Plan.Status planStatus, Meal.Status mealStatus) {
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
            meal.setLastEditedBy(Meal.Editor.USER);

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
