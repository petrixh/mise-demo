package com.example.mise.domain.shopping;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.pricing.Store;
import com.example.mise.capabilities.pricing.StoreItem;
import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeIngredient;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.preferences.ViewPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DetourEvaluator (UC-006).
 */
@SpringBootTest
@Transactional
class DetourEvaluatorTest {

    @Autowired
    private DetourEvaluator detourEvaluator;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private MealRepository mealRepository;

    @Autowired
    private MealEditRepository mealEditRepository;

    @Autowired
    private PantryRepository pantryRepository;

    @Autowired
    private ExtraShoppingItemRepository extraShoppingItemRepository;

    @Autowired
    private ViewPreferenceRepository viewPreferenceRepository;

    @MockitoBean
    private RecipeCatalog recipeCatalog;

    @MockitoBean
    private PriceCatalog priceCatalog;

    private Household household;
    private Plan activePlan;

    private static final LocalDate WEEK_START = LocalDate.of(2026, 5, 11);

    // Price data that matches "default seed data" scenario:
    //   Prima is the default store (0 detour minutes)
    //   Lidl has salmon at €5.99, Prima has salmon at €13.98 → saving = €7.99
    //   Lidl detour = 8 minutes → threshold = 8 * 0.50 = €4.00 → WORTH_IT (€7.99 > €4.00)
    //
    // For NOT_WORTH_IT scenario we use small savings:
    //   Only canned tomatoes: Prima €0.99, Lidl €0.79 → saving = €0.20
    //   threshold 8 * 0.50 = €4.00 → NOT_WORTH_IT

    @BeforeEach
    void setUp() {
        mealEditRepository.deleteAll();
        mealRepository.deleteAll();
        planRepository.deleteAll();
        viewPreferenceRepository.deleteAll();
        extraShoppingItemRepository.deleteAll();
        pantryRepository.deleteAll();
        householdRepository.deleteAll();

        household = new Household();
        household.setSize(2);
        household.setWeeklyBudget(BigDecimal.valueOf(80));
        household = householdRepository.save(household);

        activePlan = new Plan();
        activePlan.setHouseholdId(household.getId());
        activePlan.setWeekStartDate(WEEK_START);
        activePlan.setStatus(Plan.Status.ACTIVE);
        activePlan = planRepository.save(activePlan);

        when(recipeCatalog.findAll()).thenReturn(List.of());
        when(recipeCatalog.findById(anyString())).thenReturn(Optional.empty());
    }

    /**
     * Default seed data scenario: Lidl has salmon for €5.99, Prima for €13.98.
     * With a salmon-pasta meal, the saving is €7.99. Detour = 8 min → threshold = €4.00.
     * Expected: WORTH_IT.
     *
     * Note: The ShoppingService CHEAPEST_ALT_THRESHOLD (€0.50) filters what shows up
     * as a cheapestAlternative, so DetourEvaluator relies on that being populated.
     * Salmon saving (€7.99) is well above €0.50, so it will appear as cheapestAlternative.
     */
    @Test
    void evaluate_withSalmonPasta_defaultSeedData_worthIt() {
        // Prima = default store, no detour
        var prima = buildStore("prima", "Prima Supermarket", 0, true,
                si("salmon fillet", 13.98),
                si("pasta", 1.29),
                si("cream", 1.79));
        // Lidl = 8-min detour, salmon cheaper
        var lidl = buildStore("lidl", "Lidl", 8, false,
                si("salmon fillet", 5.99),
                si("pasta", 0.99),
                si("cream", 1.49));

        when(priceCatalog.findAllStores()).thenReturn(List.of(prima, lidl));
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(prima));
        when(priceCatalog.findPrice(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return prima.getCatalog().stream()
                    .filter(s -> s.getIngredientName().equalsIgnoreCase(name))
                    .findFirst()
                    .map(com.example.mise.capabilities.pricing.StoreItem::getPrice);
        });

        // Salmon-pasta recipe
        when(recipeCatalog.findById("salmon-pasta")).thenReturn(Optional.of(
                buildRecipe("salmon-pasta", "Creamy Salmon Pasta",
                        ing("salmon fillet", 400, "g", "Protein"),
                        ing("pasta", 400, "g", "Dry Goods"),
                        ing("cream", 200, "ml", "Dairy"))));

        seedMeal("salmon-pasta");

        var verdict = detourEvaluator.evaluate(household.getId(), "lidl");

        assertThat(verdict.verdict()).isEqualTo(DetourVerdict.Verdict.WORTH_IT);
        assertThat(verdict.storeId()).isEqualTo("lidl");
        assertThat(verdict.storeName()).isEqualTo("Lidl");
        assertThat(verdict.detourMinutes()).isEqualTo(8);
        assertThat(verdict.totalSavings()).isGreaterThan(BigDecimal.ZERO);
        assertThat(verdict.itemsWorthSwitching()).isNotEmpty();
        // Salmon saving = 13.98 - 5.99 = 7.99, which is above the 8*0.50=4.00 threshold
        assertThat(verdict.totalSavings().compareTo(new BigDecimal("7.99"))).isGreaterThanOrEqualTo(0);
    }

    /**
     * Mocked-cheaper scenario (demo headline): salmon at Lidl is €1.50 (not €5.99).
     * Saving = 13.98 - 1.50 = €12.48, easily above threshold.
     * Expected: WORTH_IT with even higher savings.
     */
    @Test
    void evaluate_withCheaperSalmon_mockedPrice_worthIt() {
        var prima = buildStore("prima", "Prima Supermarket", 0, true,
                si("salmon fillet", 13.98),
                si("pasta", 1.29));
        var lidl = buildStore("lidl", "Lidl", 8, false,
                si("salmon fillet", 1.50),   // mocked cheaper salmon
                si("pasta", 0.99));

        when(priceCatalog.findAllStores()).thenReturn(List.of(prima, lidl));
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(prima));
        when(priceCatalog.findPrice(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return prima.getCatalog().stream()
                    .filter(s -> s.getIngredientName().equalsIgnoreCase(name))
                    .findFirst()
                    .map(com.example.mise.capabilities.pricing.StoreItem::getPrice);
        });

        when(recipeCatalog.findById("salmon-pasta")).thenReturn(Optional.of(
                buildRecipe("salmon-pasta", "Creamy Salmon Pasta",
                        ing("salmon fillet", 400, "g", "Protein"),
                        ing("pasta", 400, "g", "Dry Goods"))));

        seedMeal("salmon-pasta");

        var verdict = detourEvaluator.evaluate(household.getId(), "lidl");

        assertThat(verdict.verdict()).isEqualTo(DetourVerdict.Verdict.WORTH_IT);
        // Saving on salmon = 13.98 - 1.50 = 12.48
        assertThat(verdict.totalSavings().compareTo(new BigDecimal("12.48"))).isGreaterThanOrEqualTo(0);
        // Reasoning should mention the saving
        assertThat(verdict.reasoning()).isNotBlank();
    }

    /**
     * Unknown store → INSUFFICIENT_DATA.
     */
    @Test
    void evaluate_unknownStore_returnsInsufficientData() {
        var prima = buildStore("prima", "Prima Supermarket", 0, true,
                si("salmon fillet", 13.98));
        when(priceCatalog.findAllStores()).thenReturn(List.of(prima));
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(prima));
        when(priceCatalog.findPrice(anyString())).thenReturn(Optional.empty());

        seedMeal("some-recipe");

        var verdict = detourEvaluator.evaluate(household.getId(), "nonexistent-store");

        assertThat(verdict.verdict()).isEqualTo(DetourVerdict.Verdict.INSUFFICIENT_DATA);
        assertThat(verdict.reasoning()).containsIgnoringCase("don't have data");
    }

    /**
     * Active plan exists but has no meals (or empty list) → graceful NOT_WORTH_IT.
     */
    @Test
    void evaluate_emptyPlan_gracefulHandling() {
        var prima = buildStore("prima", "Prima Supermarket", 0, true,
                si("salmon fillet", 13.98));
        var lidl = buildStore("lidl", "Lidl", 8, false,
                si("salmon fillet", 5.99));

        when(priceCatalog.findAllStores()).thenReturn(List.of(prima, lidl));
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(prima));
        when(priceCatalog.findPrice(anyString())).thenReturn(Optional.empty());

        // No meals seeded — list will be empty
        var verdict = detourEvaluator.evaluate(household.getId(), "lidl");

        // Empty plan → no shopping list → NOT_WORTH_IT or no items
        assertThat(verdict.verdict()).isIn(
                DetourVerdict.Verdict.NOT_WORTH_IT,
                DetourVerdict.Verdict.INSUFFICIENT_DATA);
        assertThat(verdict.totalSavings()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(verdict.itemsWorthSwitching()).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void seedMeal(String recipeId) {
        var meal = new Meal();
        meal.setPlanId(activePlan.getId());
        meal.setDate(WEEK_START);
        meal.setSlot(Meal.Slot.DINNER);
        meal.setServings(2);
        meal.setStatus(Meal.Status.PLANNED);
        meal.setRecipeRef(recipeId);
        meal.setLastEditedBy(Meal.Editor.USER);
        mealRepository.save(meal);
    }

    private Recipe buildRecipe(String id, String name, RecipeIngredient... ingredients) {
        var recipe = new Recipe();
        recipe.setId(id);
        recipe.setName(name);
        recipe.setDefaultServings(4);
        recipe.setCategoryTags(List.of("fish", "pasta"));
        recipe.setIngredients(List.of(ingredients));
        return recipe;
    }

    private RecipeIngredient ing(String name, double qty, String unit, String aisle) {
        var i = new RecipeIngredient();
        i.setName(name);
        i.setQuantity(qty);
        i.setUnit(unit);
        i.setAisle(aisle);
        return i;
    }

    private Store buildStore(String id, String name, int detourMinutes,
                              boolean defaultStore, StoreItem... items) {
        var store = new Store();
        store.setId(id);
        store.setName(name);
        store.setDetourMinutesFromRoute(detourMinutes);
        store.setDefaultStore(defaultStore);
        store.setCatalog(List.of(items));
        return store;
    }

    private StoreItem si(String name, double price) {
        var item = new StoreItem();
        item.setIngredientName(name);
        item.setPrice(price);
        item.setUnit("piece");
        return item;
    }
}
