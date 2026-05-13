package com.example.mise.ai.tools;

import com.example.mise.capabilities.personas.PersonaCatalog;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.domain.shopping.PantryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring AI {@code @Tool} beans for the onboarding flow.
 * Exposed to the {@code AIOrchestrator} during onboarding via
 * {@code AIOrchestrator.builder().withTools(onboardingTools)}.
 */
@Component
public class OnboardingTools {

    private static final Logger log = LoggerFactory.getLogger(OnboardingTools.class);

    private final HouseholdService householdService;
    private final PantryService pantryService;
    private final PlanService planService;
    private final RecipeCatalog recipeCatalog;
    private final PersonaCatalog personaCatalog;

    public OnboardingTools(HouseholdService householdService,
                           PantryService pantryService,
                           PlanService planService,
                           RecipeCatalog recipeCatalog,
                           PersonaCatalog personaCatalog) {
        this.householdService = householdService;
        this.pantryService = pantryService;
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.personaCatalog = personaCatalog;
    }

    /**
     * Records household details from the onboarding chat, seeds pantry staples,
     * generates the active plan for the current week, and seeds historical plans.
     * Idempotent: if Household already exists, returns "already configured".
     *
     * @param size               number of people eating at home
     * @param weeklyBudget       weekly grocery budget in EUR
     * @param allergies          hard-blocked ingredients (empty list if none)
     * @param hatedFoods         soft-avoided foods (empty list if none)
     * @param lovedFoods         preferred ingredients/cuisines (empty list if unknown)
     * @param dietaryConstraints e.g. vegetarian, vegan, halal (empty list if none)
     * @param hostingPattern     free-form hosting frequency description
     * @return confirmation summary for the assistant to relay to the user
     */
    @Tool(description = "Record household onboarding details, generate the active meal plan and seed history")
    public String recordHousehold(
            @ToolParam(description = "Number of people eating at home regularly") int size,
            @ToolParam(description = "Weekly grocery budget in EUR") double weeklyBudget,
            @ToolParam(description = "Hard-blocked allergens (empty list if none)") List<String> allergies,
            @ToolParam(description = "Soft-avoided foods (empty list if none)") List<String> hatedFoods,
            @ToolParam(description = "Loved/preferred foods or cuisines (empty list if unknown)") List<String> lovedFoods,
            @ToolParam(description = "Dietary constraints, e.g. vegetarian (empty list if none)") List<String> dietaryConstraints,
            @ToolParam(description = "Hosting pattern, e.g. 'occasional Saturday hosting'") String hostingPattern) {

        if (householdService.exists()) {
            log.info("recordHousehold called but household already exists; skipping");
            return "already configured";
        }

        log.info("recordHousehold: size={}, budget={}, allergies={}", size, weeklyBudget, allergies);

        // Load persona as seed baseline
        var persona = personaCatalog.findActivePersona().orElse(null);

        var household = new Household();
        household.setSize(size);
        household.setWeeklyBudget(BigDecimal.valueOf(weeklyBudget));
        household.setAllergies(allergies != null ? allergies : List.of());
        household.setHatedFoods(hatedFoods != null ? hatedFoods : List.of());
        household.setLovedFoods(lovedFoods != null ? lovedFoods : List.of());
        household.setDietaryConstraints(dietaryConstraints != null ? dietaryConstraints : List.of());
        household.setHostingPattern(hostingPattern);
        household.setCurrency("EUR");

        // Merge persona cuisinePrefs if user didn't provide lovedFoods cuisine context
        if (persona != null) {
            household.setName(persona.getName());
            if ((lovedFoods == null || lovedFoods.isEmpty()) && persona.getLovedFoods() != null) {
                household.setLovedFoods(persona.getLovedFoods());
            }
            if (persona.getCuisinePrefs() != null) {
                household.setCuisinePrefs(persona.getCuisinePrefs());
            }
        }

        var saved = householdService.save(household);

        // Seed pantry staples from persona (or a minimal default)
        List<String> staples = (persona != null && persona.getDefaultPantry() != null)
                ? persona.getDefaultPantry()
                : List.of("olive oil", "salt", "black pepper", "garlic", "onion");
        pantryService.seedStaples(saved.getId(), staples);

        // Generate active plan (current week)
        planService.generateActivePlan(saved, recipeCatalog);

        // Seed historical plans
        int seedWeeks = (persona != null && persona.getSeedWeeks() > 0) ? persona.getSeedWeeks() : 4;
        planService.seedHistory(saved, seedWeeks, recipeCatalog);

        return String.format(
                "Setup complete! Household of %d configured with a €%.0f weekly budget. " +
                "I've generated this week's dinner plan and %d weeks of history. " +
                "Head to the plan view to see your meals.",
                size, weeklyBudget, seedWeeks);
    }
}
