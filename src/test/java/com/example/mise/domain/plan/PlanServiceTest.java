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
 * Tests for UC-002 PlanService additions: pinMeal and markStatus.
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
}
