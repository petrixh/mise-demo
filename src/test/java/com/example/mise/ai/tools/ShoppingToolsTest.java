package com.example.mise.ai.tools;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.pricing.Store;
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
import com.example.mise.domain.shopping.ExtraShoppingItemRepository;
import com.example.mise.domain.shopping.PantryItem;
import com.example.mise.domain.shopping.PantryRepository;
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
 * Tests for ShoppingTools @Tool methods.
 */
@SpringBootTest
@Transactional
class ShoppingToolsTest {

    @Autowired
    private ShoppingTools shoppingTools;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private PantryRepository pantryRepository;

    @Autowired
    private ExtraShoppingItemRepository extraShoppingItemRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private MealRepository mealRepository;

    @Autowired
    private MealEditRepository mealEditRepository;

    @Autowired
    private ViewPreferenceRepository viewPreferenceRepository;

    @MockitoBean
    private RecipeCatalog recipeCatalog;

    @MockitoBean
    private PriceCatalog priceCatalog;

    private Household household;
    private Plan activePlan;

    private static final LocalDate WEEK_START = LocalDate.of(2026, 5, 11);

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

        // Default: empty store catalog
        when(priceCatalog.findAllStores()).thenReturn(List.of());
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.empty());
        when(priceCatalog.findPrice(anyString())).thenReturn(Optional.empty());
        when(recipeCatalog.findAll()).thenReturn(List.of());
        when(recipeCatalog.findById(anyString())).thenReturn(Optional.empty());
    }

    // ── listPantryItems ───────────────────────────────────────────────────────

    @Test
    void listPantryItems_emptyPantry_reportsEmpty() {
        String result = shoppingTools.listPantryItems();
        assertThat(result).containsIgnoringCase("empty");
    }

    @Test
    void listPantryItems_withItems_listsThem() {
        var item = new PantryItem();
        item.setHouseholdId(household.getId());
        item.setIngredientName("olive oil");
        item.setQuantity(BigDecimal.valueOf(500));
        item.setUnit("ml");
        item.setStaple(true);
        pantryRepository.save(item);

        var item2 = new PantryItem();
        item2.setHouseholdId(household.getId());
        item2.setIngredientName("butter");
        item2.setStaple(false);
        pantryRepository.save(item2);

        String result = shoppingTools.listPantryItems();

        assertThat(result).containsIgnoringCase("olive oil");
        assertThat(result).containsIgnoringCase("staple");
        assertThat(result).containsIgnoringCase("butter");
    }

    // ── addPantryItem ─────────────────────────────────────────────────────────

    @Test
    void addPantryItem_notStaple_persistsWithStalseFlag() {
        String result = shoppingTools.addPantryItem("cheese", 200, "g", false);

        assertThat(result).containsIgnoringCase("cheese");
        assertThat(result).containsIgnoringCase("pantry");

        var items = pantryRepository.findByHouseholdId(household.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getIngredientName()).isEqualTo("cheese");
        assertThat(items.get(0).isStaple()).isFalse();
        assertThat(items.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(200));
    }

    @Test
    void addPantryItem_asStaple_persistsWithStapleTrue() {
        String result = shoppingTools.addPantryItem("salt", 0, "", true);

        assertThat(result).containsIgnoringCase("salt");
        assertThat(result).containsIgnoringCase("staple");

        var items = pantryRepository.findByHouseholdId(household.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).isStaple()).isTrue();
    }

    // ── addExtraToShoppingList ────────────────────────────────────────────────

    @Test
    void addExtraToShoppingList_persistsExtraItem() {
        String result = shoppingTools.addExtraToShoppingList("mozzarella", 200, "g");

        assertThat(result).containsIgnoringCase("mozzarella");

        var extras = extraShoppingItemRepository.findByHouseholdId(household.getId());
        assertThat(extras).hasSize(1);
        assertThat(extras.get(0).getIngredientName()).isEqualTo("mozzarella");
        assertThat(extras.get(0).getQuantity()).isEqualTo(200.0);
        assertThat(extras.get(0).getUnit()).isEqualTo("g");
    }

    @Test
    void addExtraToShoppingList_noUnit_persistsWithNullUnit() {
        String result = shoppingTools.addExtraToShoppingList("apple juice", 1, "");

        assertThat(result).containsIgnoringCase("apple juice");
        var extras = extraShoppingItemRepository.findByHouseholdId(household.getId());
        assertThat(extras).hasSize(1);
        // Empty unit string → null stored (per tool implementation)
        assertThat(extras.get(0).getUnit()).isNull();
    }

    // ── explainListSize ───────────────────────────────────────────────────────

    @Test
    void explainListSize_noPlan_reportsMissingPlan() {
        // Delete the active plan
        mealRepository.deleteAll();
        planRepository.deleteAll();

        String result = shoppingTools.explainListSize();
        assertThat(result).containsIgnoringCase("no active plan");
    }

    @Test
    void explainListSize_withPlan_reportsItemCounts() {
        // Seed a meal with multiple ingredients
        when(recipeCatalog.findById("soup")).thenReturn(Optional.of(
                buildRecipe("soup", "Soup",
                        ing("carrot", 300, "g", "Produce"),
                        ing("onion", 2, "piece", "Produce"),
                        ing("olive oil", 30, "ml", "Pantry"),
                        ing("tomato", 400, "g", "Produce"))));

        seedMeal("soup");

        // Add olive oil as a staple (should be counted in subtraction)
        var staple = new PantryItem();
        staple.setHouseholdId(household.getId());
        staple.setIngredientName("olive oil");
        staple.setStaple(true);
        pantryRepository.save(staple);

        String result = shoppingTools.explainListSize();

        assertThat(result).containsIgnoringCase("Total ingredients from plan");
        assertThat(result).contains("4");  // 4 ingredients total
        assertThat(result).containsIgnoringCase("subtracted");
        assertThat(result).containsIgnoringCase("soup-name");
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
        recipe.setName(name + "-name");
        recipe.setDefaultServings(4);
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
}
