package com.example.mise.domain.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for UC-002 PlanService (pinMeal, markStatus) and UC-004 PlanService (undoLastEdit).
 */
@SpringBootTest
@Transactional
class PlanServiceTest {

    @Autowired
    private PlanService planService;

    @Autowired
    private MealRepository mealRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private MealEditRepository mealEditRepository;

    private Meal savedMeal;

    @BeforeEach
    void setUp() {
        // Create a minimal plan + meal for testing
        var plan = new Plan();
        plan.setHouseholdId(999L);
        plan.setWeekStartDate(java.time.LocalDate.of(2026, 5, 11));
        plan.setStatus(Plan.Status.ACTIVE);
        var savedPlan = planRepository.save(plan);

        var meal = new Meal();
        meal.setPlanId(savedPlan.getId());
        meal.setDate(java.time.LocalDate.of(2026, 5, 12));
        meal.setSlot(Meal.Slot.DINNER);
        meal.setServings(4);
        meal.setStatus(Meal.Status.PLANNED);
        meal.setRecipeRef("test-recipe");
        meal.setLastEditedBy(Meal.Editor.USER);
        savedMeal = mealRepository.save(meal);
    }

    @Test
    void pinMeal_setsPinnedTrueAndUpdatesAuditFields() {
        assertThat(savedMeal.isPinned()).isFalse();
        Instant before = Instant.now();

        planService.pinMeal(savedMeal.getId(), true);

        var updated = mealRepository.findById(savedMeal.getId()).orElseThrow();
        assertThat(updated.isPinned()).isTrue();
        assertThat(updated.getLastEditedBy()).isEqualTo(Meal.Editor.USER);
        assertThat(updated.getLastEditedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void pinMeal_togglesBackToFalse() {
        planService.pinMeal(savedMeal.getId(), true);
        planService.pinMeal(savedMeal.getId(), false);

        var updated = mealRepository.findById(savedMeal.getId()).orElseThrow();
        assertThat(updated.isPinned()).isFalse();
        assertThat(updated.getLastEditedBy()).isEqualTo(Meal.Editor.USER);
    }

    @Test
    void pinMeal_throwsForUnknownId() {
        assertThatThrownBy(() -> planService.pinMeal(-999L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-999");
    }

    @Test
    void markStatus_updatesStatusAndAuditFields() {
        assertThat(savedMeal.getStatus()).isEqualTo(Meal.Status.PLANNED);
        Instant before = Instant.now();

        planService.markStatus(savedMeal.getId(), Meal.Status.COOKED);

        var updated = mealRepository.findById(savedMeal.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(Meal.Status.COOKED);
        assertThat(updated.getLastEditedBy()).isEqualTo(Meal.Editor.USER);
        assertThat(updated.getLastEditedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void markStatus_skipped() {
        planService.markStatus(savedMeal.getId(), Meal.Status.SKIPPED);

        var updated = mealRepository.findById(savedMeal.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(Meal.Status.SKIPPED);
    }

    // ── UC-004 undoLastEdit tests ─────────────────────────────────────────

    @Test
    void undoLastEdit_restoresPreviousStateAndWritesAuditRow() {
        // First swap: set recipe to "new-recipe" (creates a MealEdit row)
        planService.swapMeal(savedMeal.getId(), "new-recipe", "AI chose new-recipe");

        var afterSwap = mealRepository.findById(savedMeal.getId()).orElseThrow();
        assertThat(afterSwap.getRecipeRef()).isEqualTo("new-recipe");

        Instant before = Instant.now();

        // Undo: should restore to "test-recipe"
        var undoEdit = planService.undoLastEdit(savedMeal.getId(), Meal.Editor.USER);

        // Meal restored
        var restored = mealRepository.findById(savedMeal.getId()).orElseThrow();
        assertThat(restored.getRecipeRef()).isEqualTo("test-recipe");
        assertThat(restored.getServings()).isEqualTo(4);
        assertThat(restored.getStatus()).isEqualTo(Meal.Status.PLANNED);
        assertThat(restored.getLastEditedBy()).isEqualTo(Meal.Editor.USER);
        assertThat(restored.getLastEditedAt()).isAfterOrEqualTo(before);

        // New MealEdit row documents the undo (BR-03)
        assertThat(undoEdit).isNotNull();
        assertThat(undoEdit.getPreviousRecipeRef()).isEqualTo("new-recipe");
        assertThat(undoEdit.getChangedBy()).isEqualTo(Meal.Editor.USER);
        assertThat(undoEdit.getReason()).contains("Undo of edit #");
        assertThat(undoEdit.getReason()).contains("AI chose new-recipe");

        // There are now 2 MealEdit rows (swap + undo)
        var allEdits = mealEditRepository.findByMealIdOrderByChangedAtDesc(savedMeal.getId());
        assertThat(allEdits).hasSize(2);
    }

    @Test
    void undoLastEdit_noEditHistory_throwsIllegalArgument() {
        assertThatThrownBy(() -> planService.undoLastEdit(savedMeal.getId(), Meal.Editor.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No edit history");
    }

    @Test
    void undoLastEdit_pinnedMeal_throwsPinnedMealException() {
        // Swap first to create history
        planService.swapMeal(savedMeal.getId(), "new-recipe", "test");
        // Pin the meal
        planService.pinMeal(savedMeal.getId(), true);

        assertThatThrownBy(() -> planService.undoLastEdit(savedMeal.getId(), Meal.Editor.USER))
                .isInstanceOf(PinnedMealException.class);
    }

    @Test
    void undoLastEdit_unknownMealId_throwsIllegalArgument() {
        assertThatThrownBy(() -> planService.undoLastEdit(-999L, Meal.Editor.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-999");
    }

    @Test
    void undoLastEdit_multipleSwaps_onlyUndoneMostRecent() {
        // Two swaps: test-recipe → swap1 → swap2
        planService.swapMeal(savedMeal.getId(), "swap1", "First swap");
        planService.swapMeal(savedMeal.getId(), "swap2", "Second swap");

        var afterTwo = mealRepository.findById(savedMeal.getId()).orElseThrow();
        assertThat(afterTwo.getRecipeRef()).isEqualTo("swap2");

        // First undo: undoes the most-recent MealEdit (swap2 → swap1), restores swap1 (BR-02)
        planService.undoLastEdit(savedMeal.getId(), Meal.Editor.USER);

        var afterFirstUndo = mealRepository.findById(savedMeal.getId()).orElseThrow();
        assertThat(afterFirstUndo.getRecipeRef()).isEqualTo("swap1");

        // Second undo: undoes the undo audit row (which had prev=swap2), bringing back swap2.
        // This is correct BR-02 behaviour: each undo is itself an edit; repeating undo
        // reverts the undo, not the original swap. Callers who want multi-level undo
        // issue multiple undo requests in sequence against the original edits.
        planService.undoLastEdit(savedMeal.getId(), Meal.Editor.USER);

        var afterSecondUndo = mealRepository.findById(savedMeal.getId()).orElseThrow();
        // The undo-of-undo restores swap2 (the undo edit row's previousRecipeRef was "swap2")
        assertThat(afterSecondUndo.getRecipeRef()).isEqualTo("swap2");

        // Total of 4 MealEdit rows: swap1, swap2, undo-swap2 (→swap1), undo-undo (→swap2)
        var allEdits = mealEditRepository.findByMealIdOrderByChangedAtDesc(savedMeal.getId());
        assertThat(allEdits).hasSize(4);
    }
}
