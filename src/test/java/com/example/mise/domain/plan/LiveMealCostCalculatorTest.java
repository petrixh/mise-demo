package com.example.mise.domain.plan;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.pricing.Store;
import com.example.mise.capabilities.pricing.StoreItem;
import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeIngredient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LiveMealCostCalculator.
 * Verifies that costFor() reads from the live PriceCatalog, not recipe.estimatedCost.
 */
class LiveMealCostCalculatorTest {

    private RecipeCatalog recipeCatalog;
    private PriceCatalog priceCatalog;
    private LiveMealCostCalculator calculator;

    @BeforeEach
    void setUp() {
        recipeCatalog = mock(RecipeCatalog.class);
        priceCatalog  = mock(PriceCatalog.class);
        calculator    = new LiveMealCostCalculator(recipeCatalog, priceCatalog);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Meal meal(String recipeRef, int servings) {
        var m = new Meal();
        m.setPlanId(1L);
        m.setDate(LocalDate.now());
        m.setSlot(Meal.Slot.DINNER);
        m.setRecipeRef(recipeRef);
        m.setServings(servings);
        m.setStatus(Meal.Status.PLANNED);
        return m;
    }

    private Recipe recipeWith(String id, int defaultServings, double estimatedCost, RecipeIngredient... ingredients) {
        var r = new Recipe();
        r.setId(id);
        r.setDefaultServings(defaultServings);
        r.setEstimatedCost(estimatedCost);
        r.setIngredients(List.of(ingredients));
        return r;
    }

    private RecipeIngredient ing(String name, double qty, String unit, String aisle, boolean optional) {
        var i = new RecipeIngredient();
        i.setName(name);
        i.setQuantity(qty);
        i.setUnit(unit);
        i.setAisle(aisle);
        i.setOptional(optional);
        return i;
    }

    private void stubStore(String ingredientName, double price, String unit) {
        var item = new StoreItem();
        item.setIngredientName(ingredientName);
        item.setPrice(price);
        item.setUnit(unit);

        var store = new Store();
        store.setId("test-store");
        store.setDefaultStore(true);
        store.setCatalog(List.of(item));

        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(store));
    }

    private void stubMultiItemStore(StoreItem... items) {
        var store = new Store();
        store.setId("test-store");
        store.setDefaultStore(true);
        store.setCatalog(List.of(items));
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(store));
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    /**
     * Core AC: when the price in the catalog is doubled, costFor() returns double.
     * This test proves the calculator reads from PriceCatalog, not recipe.estimatedCost.
     */
    @Test
    void costFor_doublingPriceDoublesResult() {
        // 400g salmon, priced at 6.99 per 100g => 4 * 6.99 = 27.96
        var recipe = recipeWith("salmon-pasta", 4, 14.50,
                ing("salmon fillet", 400, "g", "fish", false));
        var meal = meal("salmon-pasta", 4);

        when(recipeCatalog.findById("salmon-pasta")).thenReturn(Optional.of(recipe));
        stubStore("salmon fillet", 6.99, "100g");

        BigDecimal normalCost = calculator.costFor(meal);

        // Double the price
        stubStore("salmon fillet", 13.98, "100g");
        BigDecimal doubledCost = calculator.costFor(meal);

        assertThat(doubledCost).isEqualByComparingTo(normalCost.multiply(BigDecimal.valueOf(2)));
        // Sanity: estimatedCost=14.50 but live cost = 4 * 6.99 = 27.96 (NOT 14.50)
        assertThat(normalCost).isEqualByComparingTo(new BigDecimal("27.96"));
    }

    @Test
    void costFor_returnsZeroWhenRecipeNotFound() {
        when(recipeCatalog.findById("unknown")).thenReturn(Optional.empty());
        var meal = meal("unknown", 4);
        assertThat(calculator.costFor(meal)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void costFor_returnsZeroWhenNullMeal() {
        assertThat(calculator.costFor(null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void costFor_skipsOptionalIngredients() {
        var recipe = recipeWith("test", 4, 10.0,
                ing("dill", 20, "g", "produce", true));   // optional
        var meal = meal("test", 4);
        when(recipeCatalog.findById("test")).thenReturn(Optional.of(recipe));
        stubStore("dill", 1.29, "bunch");

        // Only optional ingredient — cost should be 0
        assertThat(calculator.costFor(meal)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void costFor_scalesWithServings() {
        // Recipe for 4, meal for 2 → half cost
        var recipe = recipeWith("chicken", 4, 12.0,
                ing("chicken breast", 600, "g", "meat", false));
        when(recipeCatalog.findById("chicken")).thenReturn(Optional.of(recipe));
        stubStore("chicken breast", 4.99, "kg");  // 4.99/kg

        // 600g * 4.99/1000 = 2.994 per default 4 servings
        BigDecimal costFor4 = calculator.costFor(meal("chicken", 4));
        BigDecimal costFor2 = calculator.costFor(meal("chicken", 2));

        assertThat(costFor2).isEqualByComparingTo(costFor4.divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void costFor_weightConversion_grams_to_100g() {
        // 400g @ €13.98/100g → 4 * 13.98 = 55.92
        var recipe = recipeWith("r", 4, 0.0,
                ing("salmon fillet", 400, "g", "fish", false));
        var meal = meal("r", 4);
        when(recipeCatalog.findById("r")).thenReturn(Optional.of(recipe));
        stubStore("salmon fillet", 13.98, "100g");

        assertThat(calculator.costFor(meal)).isEqualByComparingTo(new BigDecimal("55.92"));
    }

    @Test
    void costFor_weightConversion_grams_to_kg() {
        // 600g beef @ €8.99/kg → 0.6 * 8.99 = 5.394 → 5.39
        var recipe = recipeWith("r", 4, 0.0,
                ing("beef stew meat", 600, "g", "meat", false));
        var meal = meal("r", 4);
        when(recipeCatalog.findById("r")).thenReturn(Optional.of(recipe));
        stubStore("beef stew meat", 8.99, "kg");

        // 5.394 rounds to 5.39 (HALF_UP)
        assertThat(calculator.costFor(meal)).isEqualByComparingTo(new BigDecimal("5.39"));
    }

    @Test
    void costFor_volumeConversion_ml_to_200ml() {
        // 200ml cream @ €1.79/200ml → 1 * 1.79 = 1.79
        var recipe = recipeWith("r", 4, 0.0,
                ing("cream", 200, "ml", "dairy", false));
        var meal = meal("r", 4);
        when(recipeCatalog.findById("r")).thenReturn(Optional.of(recipe));
        stubStore("cream", 1.79, "200ml");

        assertThat(calculator.costFor(meal)).isEqualByComparingTo(new BigDecimal("1.79"));
    }

    @Test
    void costFor_identicalDiscreteUnits() {
        // 1 lemon @ €0.69/piece
        var recipe = recipeWith("r", 4, 0.0,
                ing("lemon", 1, "piece", "produce", false));
        var meal = meal("r", 4);
        when(recipeCatalog.findById("r")).thenReturn(Optional.of(recipe));
        stubStore("lemon", 0.69, "piece");

        assertThat(calculator.costFor(meal)).isEqualByComparingTo(new BigDecimal("0.69"));
    }

    @Test
    void costFor_incompatibleUnitsAreSkipped() {
        // kg vs piece — should skip (return 0 for that ingredient)
        var recipe = recipeWith("r", 4, 0.0,
                ing("mystery item", 1, "kg", "produce", false));
        var meal = meal("r", 4);
        when(recipeCatalog.findById("r")).thenReturn(Optional.of(recipe));
        stubStore("mystery item", 2.00, "piece");

        assertThat(calculator.costFor(meal)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void costFor_multipleIngredients_summed() {
        var recipe = recipeWith("salmon-pasta", 4, 14.50,
                ing("salmon fillet", 400, "g", "fish", false),
                ing("pasta",         400, "g", "dry-goods", false),
                ing("dill",           20, "g", "produce", true));  // optional → skipped

        var salmonItem = new StoreItem();
        salmonItem.setIngredientName("salmon fillet");
        salmonItem.setPrice(6.99);
        salmonItem.setUnit("100g");

        var pastaItem = new StoreItem();
        pastaItem.setIngredientName("pasta");
        pastaItem.setPrice(1.29);
        pastaItem.setUnit("kg");

        stubMultiItemStore(salmonItem, pastaItem);

        var meal = meal("salmon-pasta", 4);
        when(recipeCatalog.findById("salmon-pasta")).thenReturn(Optional.of(recipe));

        // 400g salmon @ 6.99/100g = 4 * 6.99 = 27.96
        // 400g pasta  @ 1.29/1000g = 0.4 * 1.29 = 0.516
        // total = 28.476 → 28.48 (HALF_UP)
        assertThat(calculator.costFor(meal)).isEqualByComparingTo(new BigDecimal("28.48"));
    }

    // ── Unit conversion helper tests ───────────────────────────────────────

    @Test
    void computeIngredientCost_g_vs_100g() {
        var item = new StoreItem();
        item.setIngredientName("salmon fillet");
        item.setPrice(13.98);
        item.setUnit("100g");

        var ing = ing("salmon fillet", 400, "g", "fish", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(55.92, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void computeIngredientCost_g_vs_kg() {
        var item = new StoreItem();
        item.setIngredientName("chicken breast");
        item.setPrice(4.99);
        item.setUnit("kg");

        var ing = ing("chicken breast", 600, "g", "meat", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(2.994, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void computeIngredientCost_ml_vs_liter() {
        var item = new StoreItem();
        item.setIngredientName("vegetable stock");
        item.setPrice(1.99);
        item.setUnit("liter");

        var ing = ing("vegetable stock", 500, "ml", "dry-goods", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(0.995, org.assertj.core.api.Assertions.within(0.001));
    }

    // ── New unit conversion tests ──────────────────────────────────────────

    @Test
    void computeIngredientCost_cloves_vs_head() {
        // 4 cloves garlic @ €0.89/head (1 head = 10 cloves) → 4/10 * 0.89 = 0.356
        var item = new StoreItem();
        item.setIngredientName("garlic");
        item.setPrice(0.89);
        item.setUnit("head");

        var ing = ing("garlic", 4, "cloves", "produce", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(0.356, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void computeIngredientCost_clove_singular_vs_head() {
        // 1 clove garlic @ €0.89/head → 1/10 * 0.89 = 0.089
        var item = new StoreItem();
        item.setIngredientName("garlic");
        item.setPrice(0.89);
        item.setUnit("head");

        var ing = ing("garlic", 1, "clove", "produce", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(0.089, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void computeIngredientCost_piece_vs_dozen() {
        // 2 eggs @ €2.99/dozen → 2/12 * 2.99 = 0.498...
        var item = new StoreItem();
        item.setIngredientName("egg");
        item.setPrice(2.99);
        item.setUnit("dozen");

        var ing = ing("egg", 2, "piece", "dairy", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(0.498, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void computeIngredientCost_produce_piece_vs_kg() {
        // 2 onions @ €1.49/kg, onion ≈ 120g → 2 * 120 / 1000 * 1.49 = 0.3576
        var item = new StoreItem();
        item.setIngredientName("onion");
        item.setPrice(1.49);
        item.setUnit("kg");

        var ing = ing("onion", 2, "piece", "produce", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(0.3576, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void computeIngredientCost_produce_piece_vs_kg_potato() {
        // 4 potatoes @ €1.49/kg, potato ≈ 150g → 4 * 150 / 1000 * 1.49 = 0.894
        var item = new StoreItem();
        item.setIngredientName("potato");
        item.setPrice(1.49);
        item.setUnit("kg");

        var ing = ing("potato", 4, "piece", "produce", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(0.894, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void computeIngredientCost_tsp_vs_liquid_unit() {
        // 2 tsp oil @ €4.99/500ml → 2*5ml / 500ml * 4.99 = 0.0998
        var item = new StoreItem();
        item.setIngredientName("olive oil");
        item.setPrice(4.99);
        item.setUnit("500ml");

        var ing = ing("olive oil", 2, "tsp", "dry-goods", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(0.0998, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void computeIngredientCost_tbsp_vs_liquid_unit() {
        // 3 tbsp soy sauce @ €2.49/250ml → 3*15ml / 250ml * 2.49 = 0.4482
        var item = new StoreItem();
        item.setIngredientName("soy sauce");
        item.setPrice(2.49);
        item.setUnit("250ml");

        var ing = ing("soy sauce", 3, "tbsp", "dry-goods", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(0.4482, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void computeIngredientCost_g_vs_head_broccoli() {
        // broccoli 300g @ €1.99/head (head ≈ 400g) → 300/400 * 1.99 = 1.4925
        var item = new StoreItem();
        item.setIngredientName("broccoli");
        item.setPrice(1.99);
        item.setUnit("head");

        var ing = ing("broccoli", 300, "g", "produce", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(1.4925, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void computeIngredientCost_g_vs_400g_canned_tomatoes() {
        // canned tomatoes 800g @ €0.99/400g → 800/400 * 0.99 = 1.98
        var item = new StoreItem();
        item.setIngredientName("canned tomatoes");
        item.setPrice(0.99);
        item.setUnit("400g");

        var ing = ing("canned tomatoes", 800, "g", "dry-goods", false);
        double cost = calculator.computeIngredientCost(ing, item);
        assertThat(cost).isEqualTo(1.98, org.assertj.core.api.Assertions.within(0.001));
    }
}
