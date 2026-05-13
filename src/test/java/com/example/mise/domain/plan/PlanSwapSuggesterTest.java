package com.example.mise.domain.plan;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.pricing.Store;
import com.example.mise.capabilities.pricing.StoreItem;
import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeIngredient;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.preferences.ViewPreferenceRepository;
import com.example.mise.domain.shopping.ExtraShoppingItemRepository;
import com.example.mise.domain.shopping.PantryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PlanSwapSuggester (UC-006).
 */
@SpringBootTest
@Transactional
class PlanSwapSuggesterTest {

    @Autowired
    private PlanSwapSuggester planSwapSuggester;

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
        household.setAllergies(new ArrayList<>());
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
     * Given a plan with a salmon-pasta meal (salmon exclusively cheaper at Lidl vs Prima),
     * the suggester should return at least 1 swap candidate without salmon.
     *
     * Setup:
     *   Prima (default): salmon=13.98, pasta=1.29, cream=1.79, chicken=4.99
     *   Lidl: ONLY salmon is cheaper (5.99 vs 13.98). pasta/cream/chicken same or pricier.
     *   Alternative recipe (chicken-pasta) has no salmon → avoidIngredientCount=0 < current (1).
     */
    @Test
    void suggestSwaps_lidlOnlyIngredient_returnsAtLeastOneCandidate() {
        // Prima = default; Lidl ONLY has salmon cheaper
        var prima = buildStore("prima", "Prima Supermarket", 0, true,
                si("salmon fillet", 13.98),
                si("pasta", 1.29),
                si("cream", 1.79),
                si("chicken breast", 4.99));
        var lidl = buildStore("lidl", "Lidl", 8, false,
                si("salmon fillet", 5.99),    // cheaper than prima (13.98 vs 5.99)
                si("pasta", 1.49),             // MORE expensive than prima → not in avoidIngredients
                si("cream", 1.99),             // MORE expensive
                si("chicken breast", 5.99));   // MORE expensive

        when(priceCatalog.findAllStores()).thenReturn(List.of(prima, lidl));
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(prima));

        // Current recipe: salmon-pasta — contains salmon fillet (only Lidl-cheaper item)
        var salmonPasta = buildRecipe("salmon-pasta", "Creamy Salmon Pasta",
                List.of("fish", "pasta"),
                ing("salmon fillet", 400, "g"),
                ing("pasta", 400, "g"),
                ing("cream", 200, "ml"));

        // Alternative recipe: chicken-pasta — no salmon, shares "pasta" tag
        var chickenPasta = buildRecipe("chicken-pasta", "Chicken Pasta",
                List.of("pasta", "quick"),
                ing("chicken breast", 400, "g"),
                ing("pasta", 400, "g"));

        when(recipeCatalog.findById("salmon-pasta")).thenReturn(Optional.of(salmonPasta));
        when(recipeCatalog.findById("chicken-pasta")).thenReturn(Optional.of(chickenPasta));
        when(recipeCatalog.findAll()).thenReturn(List.of(salmonPasta, chickenPasta));

        seedMeal("salmon-pasta");

        var suggestions = planSwapSuggester.suggestSwapsToAvoidStore(household.getId(), "lidl");

        assertThat(suggestions).isNotEmpty();
        // The suggested recipe should be different from the current one
        assertThat(suggestions.get(0).currentRecipeRef()).isEqualTo("salmon-pasta");
        assertThat(suggestions.get(0).suggestedRecipeRef()).isNotEqualTo("salmon-pasta");
        // There should be a positive savings estimate
        assertThat(suggestions.get(0).estimatedSavings()).isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * Allergy filter: if the only replacement recipe contains an allergen, no swaps are returned.
     */
    @Test
    void suggestSwaps_allergyFilter_neverProposesForbiddenRecipe() {
        // Household has a "salmon" allergy (should already not have salmon in plan,
        // but we test that the candidate with "chicken" is allergy-safe and
        // a hypothetical replacement containing the allergen is filtered out)

        // Set up allergy: fish allergy
        household.setAllergies(List.of("salmon"));
        householdRepository.save(household);

        var prima = buildStore("prima", "Prima Supermarket", 0, true,
                si("salmon fillet", 13.98),
                si("chicken breast", 4.99));
        var lidl = buildStore("lidl", "Lidl", 8, false,
                si("salmon fillet", 5.99),
                si("chicken breast", 3.99));

        when(priceCatalog.findAllStores()).thenReturn(List.of(prima, lidl));
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.of(prima));

        // Current recipe: chicken pasta (no salmon, so allergy-ok for current meal)
        var chickenPasta = buildRecipe("chicken-pasta", "Chicken Pasta",
                List.of("pasta"),
                ing("chicken breast", 400, "g"),
                ing("pasta", 400, "g"));

        // Only available alternative: salmon pasta — contains allergen (should be filtered!)
        var salmonPasta = buildRecipe("salmon-pasta", "Creamy Salmon Pasta",
                List.of("pasta", "fish"),
                ing("salmon fillet", 400, "g"),
                ing("pasta", 400, "g"));

        when(recipeCatalog.findById("chicken-pasta")).thenReturn(Optional.of(chickenPasta));
        when(recipeCatalog.findById("salmon-pasta")).thenReturn(Optional.of(salmonPasta));
        when(recipeCatalog.findAll()).thenReturn(List.of(chickenPasta, salmonPasta));

        seedMeal("chicken-pasta");

        var suggestions = planSwapSuggester.suggestSwapsToAvoidStore(household.getId(), "lidl");

        // All suggestions must be allergy-safe: none should use salmon
        for (var s : suggestions) {
            var recipe = recipeCatalog.findById(s.suggestedRecipeRef());
            recipe.ifPresent(r ->
                    assertThat(r.containsAllergen("salmon")).isFalse());
        }
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

    private Recipe buildRecipe(String id, String name, List<String> tags,
                                RecipeIngredient... ingredients) {
        var recipe = new Recipe();
        recipe.setId(id);
        recipe.setName(name);
        recipe.setDefaultServings(4);
        recipe.setCategoryTags(new ArrayList<>(tags));
        recipe.setIngredients(List.of(ingredients));
        return recipe;
    }

    private RecipeIngredient ing(String name, double qty, String unit) {
        var i = new RecipeIngredient();
        i.setName(name);
        i.setQuantity(qty);
        i.setUnit(unit);
        i.setAisle("Protein");
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
