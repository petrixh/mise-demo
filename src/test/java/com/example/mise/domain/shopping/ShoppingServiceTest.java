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
 * Unit tests for ShoppingService.deriveList covering the spec's business rules.
 */
@SpringBootTest
@Transactional
class ShoppingServiceTest {

    @Autowired
    private ShoppingService shoppingService;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private MealRepository mealRepository;

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

    @BeforeEach
    void setUp() {
        // Clean state
        viewPreferenceRepository.deleteAll();
        extraShoppingItemRepository.deleteAll();
        pantryRepository.deleteAll();
        mealRepository.deleteAll();
        planRepository.deleteAll();
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

        // Set up three stores: prima (default), lidl, local-market
        var prima = buildStore("prima", "Prima Supermarket", true,
                storeItem("carrot", 1.29),
                storeItem("olive oil", 4.99),
                storeItem("tomato", 1.49),
                storeItem("onion", 1.49),
                storeItem("cheese", 3.99));
        var lidl = buildStore("lidl", "Lidl", false,
                storeItem("carrot", 0.69),   // much cheaper at Lidl
                storeItem("olive oil", 3.99),
                storeItem("tomato", 1.19),
                storeItem("onion", 0.99),
                storeItem("cheese", 3.49));
        var localMarket = buildStore("local-market", "Local Market", false,
                storeItem("carrot", 1.89),   // most expensive
                storeItem("olive oil", 5.49),
                storeItem("tomato", 1.89),
                storeItem("onion", 1.79),
                storeItem("cheese", 4.49));

        when(priceCatalog.findAllStores()).thenReturn(List.of(prima, lidl, localMarket));
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(prima));
        when(priceCatalog.findPrice(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return prima.getCatalog().stream()
                    .filter(si -> si.getIngredientName().equalsIgnoreCase(name))
                    .findFirst()
                    .map(si -> si.getPrice());
        });
    }

    // ── BR-02: Consolidation ──────────────────────────────────────────────────

    @Test
    void deriveList_twoRecipesWithCarrots_produceSingleRow() {
        // Two recipes both containing carrots in the same unit
        when(recipeCatalog.findById("recipe-a")).thenReturn(Optional.of(
                buildRecipe("recipe-a", "Carrot Soup",
                        ingredient("carrot", 300, "g", "Produce"),
                        ingredient("onion", 1, "piece", "Produce"))));
        when(recipeCatalog.findById("recipe-b")).thenReturn(Optional.of(
                buildRecipe("recipe-b", "Carrot Stew",
                        ingredient("carrot", 200, "g", "Produce"))));

        seedMeals("recipe-a", "recipe-b");

        var list = shoppingService.deriveList(household.getId(), StoreMode.ONE_STORE);

        // Find carrot in the list
        var allItems = list.aisleGroups().stream()
                .flatMap(g -> g.items().stream())
                .toList();

        var carrotRows = allItems.stream()
                .filter(i -> i.ingredientName().equalsIgnoreCase("carrot"))
                .toList();

        assertThat(carrotRows).hasSize(1);
        assertThat(carrotRows.get(0).quantity()).isEqualTo(500.0);  // 300 + 200
        assertThat(carrotRows.get(0).usedInRecipes()).containsExactlyInAnyOrder("recipe-a", "recipe-b");
    }

    @Test
    void deriveList_incompatibleUnits_produceSeparateRows() {
        // One recipe uses "g", another uses "piece" for the same ingredient
        when(recipeCatalog.findById("recipe-a")).thenReturn(Optional.of(
                buildRecipe("recipe-a", "Soup",
                        ingredient("tomato", 400, "g", "Produce"))));
        when(recipeCatalog.findById("recipe-b")).thenReturn(Optional.of(
                buildRecipe("recipe-b", "Salad",
                        ingredient("tomato", 2, "piece", "Produce"))));

        seedMeals("recipe-a", "recipe-b");

        var list = shoppingService.deriveList(household.getId(), StoreMode.ONE_STORE);

        var allItems = list.aisleGroups().stream()
                .flatMap(g -> g.items().stream())
                .toList();

        var tomatoRows = allItems.stream()
                .filter(i -> i.ingredientName().equalsIgnoreCase("tomato"))
                .toList();

        // Should be two separate rows (different units)
        assertThat(tomatoRows).hasSize(2);
    }

    // ── BR-03: Staple subtraction ─────────────────────────────────────────────

    @Test
    void deriveList_stapleOliveOil_removedFromList() {
        when(recipeCatalog.findById("recipe-a")).thenReturn(Optional.of(
                buildRecipe("recipe-a", "Pasta",
                        ingredient("olive oil", 30, "ml", "Pantry"),
                        ingredient("tomato", 400, "g", "Produce"))));
        seedMeals("recipe-a");

        // Olive oil is a staple in pantry
        var staple = new PantryItem();
        staple.setHouseholdId(household.getId());
        staple.setIngredientName("olive oil");
        staple.setStaple(true);
        pantryRepository.save(staple);

        var list = shoppingService.deriveList(household.getId(), StoreMode.ONE_STORE);

        var allItems = list.aisleGroups().stream()
                .flatMap(g -> g.items().stream())
                .toList();

        assertThat(allItems.stream().noneMatch(i -> i.ingredientName().equalsIgnoreCase("olive oil")))
                .isTrue();

        // Pantry section should include olive oil
        assertThat(list.pantrySection().items()).anyMatch(
                p -> p.getIngredientName().equalsIgnoreCase("olive oil"));
    }

    // ── BR-04: Non-staple pantry subtraction ──────────────────────────────────

    @Test
    void deriveList_nonStaplePantryItem_removedFromList() {
        when(recipeCatalog.findById("recipe-a")).thenReturn(Optional.of(
                buildRecipe("recipe-a", "Cheese Pasta",
                        ingredient("cheese", 100, "g", "Dairy"),
                        ingredient("tomato", 200, "g", "Produce"))));
        seedMeals("recipe-a");

        // Cheese marked as "already have" but not a staple (BR-04)
        var pantryItem = new PantryItem();
        pantryItem.setHouseholdId(household.getId());
        pantryItem.setIngredientName("cheese");
        pantryItem.setStaple(false);
        pantryRepository.save(pantryItem);

        var list = shoppingService.deriveList(household.getId(), StoreMode.ONE_STORE);

        var allItems = list.aisleGroups().stream()
                .flatMap(g -> g.items().stream())
                .toList();

        assertThat(allItems.stream().noneMatch(i -> i.ingredientName().equalsIgnoreCase("cheese")))
                .isTrue();
    }

    // ── BR-05: Store mode ──────────────────────────────────────────────────────

    @Test
    void deriveList_oneStoreMode_allItemsFromSameStore() {
        when(recipeCatalog.findById("recipe-a")).thenReturn(Optional.of(
                buildRecipe("recipe-a", "Soup",
                        ingredient("carrot", 300, "g", "Produce"),
                        ingredient("onion", 2, "piece", "Produce"))));
        seedMeals("recipe-a");

        var list = shoppingService.deriveList(household.getId(), StoreMode.ONE_STORE);

        assertThat(list.storeMode()).isEqualTo(StoreMode.ONE_STORE);
        // In ONE_STORE mode all items should reference the same store
        var storeIds = list.aisleGroups().stream()
                .flatMap(g -> g.items().stream())
                .map(ShoppingItem::recommendedStoreId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        assertThat(storeIds).hasSize(1);
    }

    @Test
    void deriveList_cheapestMixMode_carrots_assignedToLidl() {
        // Lidl has carrots at 0.69 vs prima at 1.29 — CHEAPEST_MIX should prefer Lidl
        when(recipeCatalog.findById("recipe-a")).thenReturn(Optional.of(
                buildRecipe("recipe-a", "Soup",
                        ingredient("carrot", 300, "g", "Produce"))));
        seedMeals("recipe-a");

        var list = shoppingService.deriveList(household.getId(), StoreMode.CHEAPEST_MIX);

        assertThat(list.storeMode()).isEqualTo(StoreMode.CHEAPEST_MIX);

        var carrotRow = list.aisleGroups().stream()
                .flatMap(g -> g.items().stream())
                .filter(i -> i.ingredientName().equalsIgnoreCase("carrot"))
                .findFirst();

        assertThat(carrotRow).isPresent();
        // In CHEAPEST_MIX mode, carrot should be at Lidl (cheaper)
        assertThat(carrotRow.get().recommendedStoreId()).isEqualTo("lidl");
    }

    // ── BR-06: Cheapest alternative hint ──────────────────────────────────────

    @Test
    void deriveList_oneStoreMode_cheaperAlternativeAtLidl_hintsPopulated() {
        // Setup: prima wins overall (lower total), but Lidl has carrot much cheaper (saving > €0.50)
        // Carrot: prima=2.00, lidl=1.00 (saving €1.00 > threshold)
        // Onion: prima=1.49, lidl=9.00 (makes prima win the ONE_STORE selection)
        var prima = buildStore("prima", "Prima Supermarket", true,
                storeItem("carrot", 2.00),
                storeItem("onion", 1.49));
        var lidl = buildStore("lidl", "Lidl", false,
                storeItem("carrot", 1.00),   // €1.00 cheaper than prima — saving = €1.00 > €0.50 threshold
                storeItem("onion", 9.00));
        when(priceCatalog.findAllStores()).thenReturn(List.of(prima, lidl));
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(prima));

        when(recipeCatalog.findById("recipe-a")).thenReturn(Optional.of(
                buildRecipe("recipe-a", "Soup",
                        ingredient("carrot", 300, "g", "Produce"),
                        ingredient("onion", 2, "piece", "Produce"))));
        seedMeals("recipe-a");

        var list = shoppingService.deriveList(household.getId(), StoreMode.ONE_STORE);

        // Prima should win ONE_STORE (total prima: 2.00+1.49=3.49 vs lidl: 1.00+9.00=10.00)
        assertThat(list.recommendedStore()).isNotNull();
        assertThat(list.recommendedStore().getId()).isEqualTo("prima");

        var carrotRow = list.aisleGroups().stream()
                .flatMap(g -> g.items().stream())
                .filter(i -> i.ingredientName().equalsIgnoreCase("carrot"))
                .findFirst();

        assertThat(carrotRow).isPresent();
        // Carrot is €1.00 cheaper at Lidl — should have a cheapest alternative hint
        assertThat(carrotRow.get().cheapestAlternative()).isNotNull();
        assertThat(carrotRow.get().cheapestAlternative().storeId()).isEqualTo("lidl");
    }

    @Test
    void deriveList_oneStoreMode_noMeaningfulSaving_noHint() {
        // Cheese: prima 3.99, lidl 3.49 — saving = 0.50, at the threshold exactly (not strictly greater)
        // So no hint should be shown (threshold requires saving > 0.50)
        when(recipeCatalog.findById("recipe-a")).thenReturn(Optional.of(
                buildRecipe("recipe-a", "Cheesy Dish",
                        ingredient("cheese", 100, "g", "Dairy"))));
        seedMeals("recipe-a");

        // Override: make lidl cheese 3.59 (saving 0.40 < threshold)
        var prima = buildStore("prima", "Prima Supermarket", true, storeItem("cheese", 3.99));
        var lidlClose = buildStore("lidl", "Lidl", false, storeItem("cheese", 3.59));
        when(priceCatalog.findAllStores()).thenReturn(List.of(prima, lidlClose));
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(prima));

        var list = shoppingService.deriveList(household.getId(), StoreMode.ONE_STORE);

        var cheeseRow = list.aisleGroups().stream()
                .flatMap(g -> g.items().stream())
                .filter(i -> i.ingredientName().equalsIgnoreCase("cheese"))
                .findFirst();

        assertThat(cheeseRow).isPresent();
        assertThat(cheeseRow.get().cheapestAlternative()).isNull();
    }

    // ── Empty list when everything is in pantry ───────────────────────────────

    @Test
    void deriveList_allIngredientsAreStaples_emptyList() {
        when(recipeCatalog.findById("recipe-a")).thenReturn(Optional.of(
                buildRecipe("recipe-a", "Simple Salad",
                        ingredient("olive oil", 30, "ml", "Pantry"),
                        ingredient("onion", 1, "piece", "Produce"))));
        seedMeals("recipe-a");

        // Both ingredients are staples
        for (String name : List.of("olive oil", "onion")) {
            var s = new PantryItem();
            s.setHouseholdId(household.getId());
            s.setIngredientName(name);
            s.setStaple(true);
            pantryRepository.save(s);
        }

        var list = shoppingService.deriveList(household.getId(), StoreMode.ONE_STORE);

        assertThat(list.aisleGroups()).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    // ── Mode trade-off (recommendation panel / toggle summary) ────────────────

    @Test
    void tradeoff_oneStoreIsSingleStop_mixIsCheaperOrEqual() {
        when(recipeCatalog.findById("recipe-a")).thenReturn(Optional.of(
                buildRecipe("recipe-a", "Carrot Soup",
                        ingredient("carrot", 300, "g", "Produce"),
                        ingredient("cheese", 1, "piece", "Dairy"))));
        seedMeals("recipe-a");

        var oneStore = shoppingService.deriveList(household.getId(), StoreMode.ONE_STORE);
        var oneTradeoff = shoppingService.tradeoff(oneStore);
        assertThat(oneTradeoff.stops()).isEqualTo(1);
        // Full basket priced at the recommended store, not the per-item cheapest
        var recStore = oneStore.recommendedStore();
        assertThat(recStore).isNotNull();

        var mix = shoppingService.deriveList(household.getId(), StoreMode.CHEAPEST_MIX);
        var mixTradeoff = shoppingService.tradeoff(mix);
        assertThat(mixTradeoff.stops()).isGreaterThanOrEqualTo(1);
        assertThat(mixTradeoff.total()).isLessThanOrEqualTo(oneTradeoff.total());
        assertThat(mixTradeoff.total()).isEqualByComparingTo(mix.totalCost());
    }

    private void seedMeals(String... recipeIds) {
        for (int i = 0; i < recipeIds.length; i++) {
            var meal = new Meal();
            meal.setPlanId(activePlan.getId());
            meal.setDate(WEEK_START.plusDays(i));
            meal.setSlot(Meal.Slot.DINNER);
            meal.setServings(2);
            meal.setStatus(Meal.Status.PLANNED);
            meal.setRecipeRef(recipeIds[i]);
            meal.setLastEditedBy(Meal.Editor.USER);
            mealRepository.save(meal);
        }
    }

    private Recipe buildRecipe(String id, String name, RecipeIngredient... ingredients) {
        var recipe = new Recipe();
        recipe.setId(id);
        recipe.setName(name);
        recipe.setDefaultServings(4);
        recipe.setIngredients(List.of(ingredients));
        return recipe;
    }

    private RecipeIngredient ingredient(String name, double qty, String unit, String aisle) {
        var ing = new RecipeIngredient();
        ing.setName(name);
        ing.setQuantity(qty);
        ing.setUnit(unit);
        ing.setAisle(aisle);
        return ing;
    }

    private Store buildStore(String id, String name, boolean defaultStore, StoreItem... items) {
        var store = new Store();
        store.setId(id);
        store.setName(name);
        store.setDefaultStore(defaultStore);
        store.setCatalog(List.of(items));
        return store;
    }

    private StoreItem storeItem(String name, double price) {
        var si = new StoreItem();
        si.setIngredientName(name);
        si.setPrice(price);
        si.setUnit("kg");
        return si;
    }
}
