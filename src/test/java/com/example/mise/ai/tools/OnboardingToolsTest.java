package com.example.mise.ai.tools;

import com.example.mise.capabilities.personas.Persona;
import com.example.mise.capabilities.personas.PersonaCatalog;
import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeIngredient;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanRepository;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.domain.shopping.PantryItem;
import com.example.mise.domain.shopping.PantryRepository;
import com.example.mise.domain.shopping.PantryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link OnboardingTools#recordHousehold}.
 *
 * Verifies:
 * (a) Calling recordHousehold populates Household + ≥4 historical plans + 1 active plan + pantry.
 * (b) Allergies stated during onboarding are reflected in Household.allergies
 *     and no meal in the seeded plans contains a blocked ingredient.
 */
@SpringBootTest
@Transactional
class OnboardingToolsTest {

    @Autowired
    private OnboardingTools onboardingTools;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private MealRepository mealRepository;

    @Autowired
    private PantryRepository pantryRepository;

    @MockitoBean
    private PersonaCatalog personaCatalog;

    @MockitoBean
    private RecipeCatalog recipeCatalog;

    @BeforeEach
    void setUp() {
        // Clean state
        mealRepository.deleteAll();
        planRepository.deleteAll();
        pantryRepository.deleteAll();
        householdRepository.deleteAll();

        // Mock persona
        var persona = new Persona();
        persona.setId("test-persona");
        persona.setName("Test Family");
        persona.setSeedWeeks(4);
        persona.setDefaultPantry(List.of("olive oil", "salt", "garlic"));
        persona.setLovedFoods(List.of("pasta"));
        persona.setCuisinePrefs(List.of("Italian"));
        when(personaCatalog.findActivePersona()).thenReturn(Optional.of(persona));

        // Build 15 test recipes — some contain "shrimp" (allergen), some don't
        var allRecipes = new ArrayList<Recipe>();
        String[] safeNames = {
            "Pasta Bolognese", "Chicken Rice", "Veggie Soup", "Beef Stew",
            "Lentil Curry", "Pork Chops", "Turkey Roast", "Potato Gratin",
            "Minced Meat Sauce", "Pea Risotto"
        };
        for (int i = 0; i < safeNames.length; i++) {
            allRecipes.add(buildSafeRecipe("recipe-" + i, safeNames[i]));
        }
        // Add recipes with allergen "shrimp"
        allRecipes.add(buildAllergenRecipe("shrimp-pasta", "Shrimp Pasta", "shrimp"));
        allRecipes.add(buildAllergenRecipe("shrimp-risotto", "Shrimp Risotto", "shrimp"));
        allRecipes.add(buildAllergenRecipe("shrimp-stir-fry", "Shrimp Stir Fry", "shrimp"));

        when(recipeCatalog.findAll()).thenReturn(allRecipes);
    }

    @Test
    void recordHousehold_populatesHouseholdAndPlansAndPantry() {
        // Act
        String result = onboardingTools.recordHousehold(
                4,          // size
                120.0,      // budget
                List.of("shrimp"),  // allergies
                List.of("liver"),   // hatedFoods
                List.of("pasta"),   // lovedFoods
                List.of(),          // dietaryConstraints
                "occasional Saturday hosting"
        );

        // Assert return message
        assertThat(result).doesNotContain("already configured");
        assertThat(result).contains("4"); // household size
        assertThat(result).contains("120"); // budget

        // (a) Household persisted
        var households = householdRepository.findAll();
        assertThat(households).hasSize(1);
        var household = households.get(0);
        assertThat(household.getSize()).isEqualTo(4);
        assertThat(household.getWeeklyBudget().doubleValue()).isEqualTo(120.0);
        assertThat(household.getAllergies()).contains("shrimp");
        assertThat(household.getHatedFoods()).contains("liver");
        assertThat(household.getHostingPattern()).isEqualTo("occasional Saturday hosting");

        // (a) Active plan + 4 historical plans = 5 total
        var plans = planRepository.findAll();
        assertThat(plans).hasSizeGreaterThanOrEqualTo(5);

        long activePlans = plans.stream().filter(p -> p.getStatus() == Plan.Status.ACTIVE).count();
        long historicalPlans = plans.stream().filter(p -> p.getStatus() == Plan.Status.HISTORICAL).count();
        assertThat(activePlans).isEqualTo(1);
        assertThat(historicalPlans).isGreaterThanOrEqualTo(4);

        // (a) Pantry seeded
        var pantryItems = pantryRepository.findByHouseholdId(household.getId());
        assertThat(pantryItems).isNotEmpty();
        var pantryNames = pantryItems.stream().map(PantryItem::getIngredientName).toList();
        assertThat(pantryNames).contains("olive oil", "salt", "garlic");

        // (b) Allergies reflected in Household
        assertThat(household.getAllergies()).contains("shrimp");

        // (b) No meal in any plan references a shrimp recipe
        var allMeals = mealRepository.findAll();
        assertThat(allMeals).isNotEmpty();
        for (var meal : allMeals) {
            assertThat(meal.getRecipeRef())
                    .as("Meal %s references allergen recipe", meal.getId())
                    .doesNotContain("shrimp");
        }
    }

    @Test
    void recordHousehold_isIdempotent() {
        // First call
        onboardingTools.recordHousehold(3, 90.0, List.of(), List.of(), List.of(), List.of(), "rarely");
        long householdCount = householdRepository.count();

        // Second call should return "already configured" and not create another household
        String result = onboardingTools.recordHousehold(3, 90.0, List.of(), List.of(), List.of(), List.of(), "rarely");
        assertThat(result).isEqualTo("already configured");
        assertThat(householdRepository.count()).isEqualTo(householdCount);
    }

    // ---- helpers ----

    private Recipe buildSafeRecipe(String id, String name) {
        var recipe = new Recipe();
        recipe.setId(id);
        recipe.setName(name);
        recipe.setCuisine("European");
        recipe.setCategoryTags(List.of("dinner"));
        recipe.setPrepMinutes(30);
        recipe.setDefaultServings(4);
        recipe.setEstimatedCost(10.0);
        var ing = new RecipeIngredient();
        ing.setName("chicken");
        ing.setQuantity(500);
        ing.setUnit("g");
        ing.setAisle("meat");
        ing.setOptional(false);
        recipe.setIngredients(List.of(ing));
        return recipe;
    }

    private Recipe buildAllergenRecipe(String id, String name, String allergen) {
        var recipe = new Recipe();
        recipe.setId(id);
        recipe.setName(name);
        recipe.setCuisine("Asian");
        recipe.setCategoryTags(List.of("dinner", "seafood"));
        recipe.setPrepMinutes(25);
        recipe.setDefaultServings(4);
        recipe.setEstimatedCost(14.0);
        var ing = new RecipeIngredient();
        ing.setName(allergen);
        ing.setQuantity(300);
        ing.setUnit("g");
        ing.setAisle("fish");
        ing.setOptional(false);
        recipe.setIngredients(List.of(ing));
        return recipe;
    }
}
