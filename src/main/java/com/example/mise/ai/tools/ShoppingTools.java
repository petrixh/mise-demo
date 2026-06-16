package com.example.mise.ai.tools;

import com.example.mise.domain.household.HouseholdService;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.domain.plan.PlanSwapSuggester;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.shopping.DetourEvaluator;
import com.example.mise.domain.shopping.DetourVerdict;
import com.example.mise.domain.shopping.ExtraShoppingItem;
import com.example.mise.domain.shopping.ExtraShoppingItemRepository;
import com.example.mise.domain.shopping.PantryItem;
import com.example.mise.domain.shopping.PantryService;
import com.example.mise.domain.shopping.ShoppingService;
import com.example.mise.ui.ViewedWeekService;
import com.example.mise.ui.ViewedWeekState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Spring AI tools exposed on the /shopping view.
 * Registered globally on the {@link com.example.mise.ai.HouseholdOrchestrator} alongside
 * PlanTools (UC-008 will introduce proper view-scoped tool registration).
 */
@Component
public class ShoppingTools {

    private static final Logger log = LoggerFactory.getLogger(ShoppingTools.class);

    private final HouseholdService householdService;
    private final PantryService pantryService;
    private final ExtraShoppingItemRepository extraShoppingItemRepository;
    private final ShoppingService shoppingService;
    private final PlanService planService;
    private final RecipeCatalog recipeCatalog;
    private final DetourEvaluator detourEvaluator;
    private final PlanSwapSuggester planSwapSuggester;
    private final ViewedWeekService viewedWeekService;
    private final ViewedWeekState viewedWeekState;

    public ShoppingTools(HouseholdService householdService,
                         PantryService pantryService,
                         ExtraShoppingItemRepository extraShoppingItemRepository,
                         ShoppingService shoppingService,
                         PlanService planService,
                         RecipeCatalog recipeCatalog,
                         DetourEvaluator detourEvaluator,
                         PlanSwapSuggester planSwapSuggester,
                         ViewedWeekService viewedWeekService,
                         ViewedWeekState viewedWeekState) {
        this.householdService = householdService;
        this.pantryService = pantryService;
        this.extraShoppingItemRepository = extraShoppingItemRepository;
        this.shoppingService = shoppingService;
        this.planService = planService;
        this.recipeCatalog = recipeCatalog;
        this.detourEvaluator = detourEvaluator;
        this.planSwapSuggester = planSwapSuggester;
        this.viewedWeekService = viewedWeekService;
        this.viewedWeekState = viewedWeekState;
    }

    /** UC-010: returns the currently viewed plan id, falling back to the active plan. */
    private Long resolveViewedPlanId(Long householdId) {
        var plan = viewedWeekService.resolveViewedPlan(householdId, viewedWeekState.getCurrentParam())
                .orElse(null);
        return plan != null ? plan.getId() : null;
    }

    /**
     * Lists all pantry items for the household.
     * Answers "what's already on hand?" and "what do I already have?"
     */
    @Tool(description = "List all pantry items for the household — their name, quantity, unit, and whether they are a permanent staple. Use this to answer 'what do I already have?' or 'what's on hand?'. Do NOT use this to list the shopping items — that is shown in the UI.")
    public String listPantryItems() {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            var items = pantryService.findByHousehold(hh.getId());
            if (items.isEmpty()) return "Pantry is empty — no items on hand.";

            return items.stream()
                    .map(p -> String.format("%s: %s %s%s",
                            p.getIngredientName(),
                            p.getQuantity() != null ? p.getQuantity().toPlainString() : "—",
                            p.getUnit() != null ? p.getUnit() : "",
                            p.isStaple() ? " (staple)" : ""))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("listPantryItems error: {}", e.getMessage());
            return "Could not load pantry: " + e.getMessage();
        }
    }

    /**
     * Adds an item to the household's pantry.
     * Caller specifies whether it is a permanent staple.
     */
    @Tool(description = "Add an ingredient to the household pantry. Use when the user says 'I already have X', 'add X to pantry', or 'I've got Y'. Set staple=false unless the user says 'always have', 'staple', or 'always on hand'. After adding, the shopping list will no longer include this ingredient.")
    public String addPantryItem(
            @ToolParam(description = "Ingredient name (e.g. 'olive oil', 'salt')") String name,
            @ToolParam(description = "Quantity (e.g. 500). Use 0 if unspecified.") double quantity,
            @ToolParam(description = "Unit (e.g. 'ml', 'g', 'piece'). Use empty string if unspecified.") String unit,
            @ToolParam(description = "true if this is always on hand (permanent staple); false if just for this week") boolean staple) {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            var item = new PantryItem();
            item.setHouseholdId(hh.getId());
            item.setIngredientName(name);
            item.setQuantity(quantity > 0 ? BigDecimal.valueOf(quantity) : null);
            item.setUnit(unit != null && !unit.isBlank() ? unit : null);
            item.setStaple(staple);
            pantryService.save(item);

            return String.format("Added %s to pantry%s. It will be removed from the shopping list.",
                    name, staple ? " as a permanent staple" : "");
        } catch (Exception e) {
            log.warn("addPantryItem error: {}", e.getMessage());
            return "Could not add to pantry: " + e.getMessage();
        }
    }

    /**
     * Adds an extra item to the shopping list (not derived from the plan).
     * Answers "add 200g extra cheese to the list".
     */
    @Tool(description = "Add an extra item to the shopping list that is not part of the active meal plan. Use when the user says 'add X to the list', 'also pick up Y', or 'add Xg of Z'. The item appears in the shopping list under the appropriate aisle (or 'Extras' if unknown).")
    public String addExtraToShoppingList(
            @ToolParam(description = "Ingredient name (e.g. 'mozzarella', 'orange juice')") String name,
            @ToolParam(description = "Quantity (e.g. 200). Use 0 if not specified.") double quantity,
            @ToolParam(description = "Unit (e.g. 'g', 'ml', 'piece'). Use empty string if not specified.") String unit) {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            var extra = new ExtraShoppingItem();
            extra.setHouseholdId(hh.getId());
            extra.setIngredientName(name);
            extra.setQuantity(quantity);
            extra.setUnit(unit != null && !unit.isBlank() ? unit : null);
            extraShoppingItemRepository.save(extra);

            return String.format("Added %s%s to the shopping list.",
                    name,
                    quantity > 0 ? " (" + quantity + (unit != null && !unit.isBlank() ? unit : "") + ")" : "");
        } catch (Exception e) {
            log.warn("addExtraToShoppingList error: {}", e.getMessage());
            return "Could not add to shopping list: " + e.getMessage();
        }
    }

    // ─────────────────────── UC-006 tools ────────────────────────────────────

    /**
     * Evaluates whether a detour to a second store this week is worth it (UC-006).
     * Grounds the verdict in real shopping-list data from ShoppingService.
     */
    @Tool(description = "Evaluate whether a detour to a second store this week is worth it. Returns the savings, the detour minutes, and a recommended verdict grounded in real shopping-list data.")
    public String evaluateDetour(
            @ToolParam(description = "Store id (e.g. 'lidl', 'prima', 'local-market')") String storeId) {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            DetourVerdict verdict = detourEvaluator.evaluate(hh.getId(), storeId);

            if (verdict.verdict() == DetourVerdict.Verdict.INSUFFICIENT_DATA) {
                return "INSUFFICIENT_DATA: " + verdict.reasoning();
            }

            // Format items list
            String itemsStr;
            if (verdict.itemsWorthSwitching().isEmpty()) {
                itemsStr = "none";
            } else {
                itemsStr = verdict.itemsWorthSwitching().stream()
                        .map(i -> i.ingredientName() + " (save €" + i.savingsPerItem().toPlainString() + ")")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("none");
            }

            return String.format(
                    "%s would save €%s across %d item(s) (%s). The detour adds %d minute(s). "
                    + "Verdict: %s. %s",
                    verdict.storeName(),
                    verdict.totalSavings().toPlainString(),
                    verdict.itemsWorthSwitching().size(),
                    itemsStr,
                    verdict.detourMinutes(),
                    verdict.verdict(),
                    verdict.reasoning());
        } catch (Exception e) {
            log.warn("evaluateDetour error: {}", e.getMessage());
            return "Could not evaluate detour: " + e.getMessage();
        }
    }

    /**
     * Suggests plan-level meal swaps to achieve savings without a second store stop (UC-006).
     * Does NOT auto-apply — the model presents suggestions; the user confirms.
     */
    @Tool(description = "Suggest plan-level meal swaps that would keep all shopping at one store, achieving similar savings without the detour. Use AFTER evaluateDetour when the user says they want the savings but not the second stop.")
    public String suggestPlanSwapForSavings(
            @ToolParam(description = "Store the user wants to avoid (e.g. 'lidl')") String storeToAvoid) {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            var suggestions = planSwapSuggester.suggestSwapsToAvoidStore(hh.getId(), storeToAvoid);

            if (suggestions.isEmpty()) {
                return "No beneficial meal swaps found to avoid " + storeToAvoid
                        + " — the current plan already minimises that store's impact.";
            }

            var lines = new ArrayList<String>();
            lines.add("Plan-level swaps to avoid " + storeToAvoid + ":");
            for (var s : suggestions) {
                var currentRecipe = recipeCatalog.findById(s.currentRecipeRef()).orElse(null);
                var suggestedRecipe = recipeCatalog.findById(s.suggestedRecipeRef()).orElse(null);
                String currentName = currentRecipe != null ? currentRecipe.getName() : s.currentRecipeRef();
                String suggestedName = suggestedRecipe != null ? suggestedRecipe.getName() : s.suggestedRecipeRef();
                lines.add(String.format("  Meal #%d: %s → %s — %s (saves approx. €%s)",
                        s.mealId(), currentName, suggestedName,
                        s.reason(), s.estimatedSavings().toPlainString()));
            }
            lines.add("To apply any of these, confirm with the meal id and I will call swapMealOnDay.");

            return String.join("\n", lines);
        } catch (Exception e) {
            log.warn("suggestPlanSwapForSavings error: {}", e.getMessage());
            return "Could not suggest swaps: " + e.getMessage();
        }
    }

    /**
     * Returns a structured summary explaining the size of the shopping list.
     * Intended to answer "why is the list so long this week?"
     */
    @Tool(description = "Return a structured summary explaining the shopping list size: total item count, which recipes contribute the most ingredients, and how many items were subtracted by pantry staples. Use this when the user asks 'why is the list so long?' — paraphrase the result, do NOT fabricate counts.")
    public String explainListSize() {
        try {
            var hh = householdService.findHousehold().orElse(null);
            if (hh == null) return "No household found.";

            // UC-010: use viewed plan if one is selected
            Long planId = resolveViewedPlanId(hh.getId());
            var contributions = planId != null
                    ? shoppingService.collectPlanIngredientsForPlan(planId)
                    : shoppingService.collectPlanIngredients(hh.getId());
            if (contributions.isEmpty()) return "No active plan found — shopping list is empty.";

            // Total ingredients (before pantry subtraction)
            int totalBeforeSubtraction = contributions.values().stream()
                    .mapToInt(java.util.List::size).sum();

            // Per-recipe contribution count
            var recipeContribCount = new java.util.TreeMap<String, Integer>();
            for (var contribList : contributions.values()) {
                for (var c : contribList) {
                    recipeContribCount.merge(c.recipeRef(), 1, Integer::sum);
                }
            }

            // Pantry subtracted
            var pantry = pantryService.findByHousehold(hh.getId());
            long stapleCount = pantry.stream().filter(PantryItem::isStaple).count();
            long subtractedCount = 0;
            for (var p : pantry) {
                String key = p.getIngredientName() == null ? "" : p.getIngredientName().toLowerCase().trim();
                if (contributions.containsKey(key)) subtractedCount++;
            }

            // Build top-contributors list (up to 5 recipes, descending)
            var topContribs = new ArrayList<>(recipeContribCount.entrySet());
            topContribs.sort((a, b) -> b.getValue() - a.getValue());
            var lines = new java.util.ArrayList<String>();
            lines.add("Total ingredients from plan: " + totalBeforeSubtraction);
            lines.add("Items subtracted by pantry/staples: " + subtractedCount + " (of which " + stapleCount + " are permanent staples)");
            lines.add("Net items on the list: " + (totalBeforeSubtraction - subtractedCount));
            lines.add("Top recipe contributors:");
            for (int i = 0; i < Math.min(5, topContribs.size()); i++) {
                var e = topContribs.get(i);
                var recipe = recipeCatalog.findById(e.getKey()).orElse(null);
                String recipeName = recipe != null ? recipe.getName() : e.getKey();
                lines.add("  " + recipeName + ": " + e.getValue() + " ingredients");
            }

            return String.join("\n", lines);
        } catch (Exception e) {
            log.warn("explainListSize error: {}", e.getMessage());
            return "Could not explain list size: " + e.getMessage();
        }
    }
}
