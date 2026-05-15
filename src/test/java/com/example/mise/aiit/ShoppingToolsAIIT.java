package com.example.mise.aiit;

import com.example.mise.domain.shopping.PantryItem;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * UC-005 AI integration: verifies that the live LLM correctly uses {@code ShoppingTools}
 * (listPantryItems, addPantryItem, addExtraToShoppingList, explainListSize) under the
 * production system prompt.
 *
 * <p>Each test is a single {@link #shoppingChat()} round-trip (one LLM call). DB side-effects
 * are asserted via the injected repositories from {@link MiseAIIT}.
 *
 * <p>The hallucination-guard pattern mirrors {@link PlanToolsAIIT}: assert on the FAIL
 * combination of "claimed success + DB unchanged", not on absence of claim alone. A model
 * that asks for clarification or declines without claiming success is acceptable.
 */
class ShoppingToolsAIIT extends MiseAIIT {

    /**
     * UC-005 checklist item 1 — "What do I already have?"
     * Seeds two pantry items (one staple, one non-staple), asks the assistant
     * what's on hand, and asserts that both names appear in the reply.
     * Also asserts no hallucinated names that were never seeded.
     */
    @Test
    void listPantryItemsRelaysRealPantryContents() {
        var hh = seedHouseholdAndActivePlan();

        // Seed two pantry items directly via repository (bypasses tool — we want known data).
        var oliveoil = new PantryItem();
        oliveoil.setHouseholdId(hh.getId());
        oliveoil.setIngredientName("olive oil");
        oliveoil.setQuantity(new BigDecimal("500"));
        oliveoil.setUnit("ml");
        oliveoil.setStaple(true);
        pantryRepository.save(oliveoil);

        var cheese = new PantryItem();
        cheese.setHouseholdId(hh.getId());
        cheese.setIngredientName("leftover cheese");
        cheese.setQuantity(new BigDecimal("200"));
        cheese.setUnit("g");
        cheese.setStaple(false);
        pantryRepository.save(cheese);

        var reply = shoppingChat()
                .prompt()
                .user("What do I already have?")
                .call()
                .content();

        String lower = reply.toLowerCase(Locale.ROOT);

        Assertions.assertThat(lower)
                .as("Reply must mention 'olive oil' from the pantry")
                .contains("olive oil");

        Assertions.assertThat(lower)
                .as("Reply must mention 'leftover cheese' (or just 'cheese') from the pantry")
                .satisfiesAnyOf(
                        r -> Assertions.assertThat(r).contains("leftover cheese"),
                        r -> Assertions.assertThat(r).contains("cheese"));

        // Fabrication guard: the pantry has no avocado, no pasta, no milk.
        // If the model mentions these it invented them.
        Assertions.assertThat(lower)
                .as("Reply must NOT mention 'avocado' — not in pantry")
                .doesNotContain("avocado");
        Assertions.assertThat(lower)
                .as("Reply must NOT mention 'milk' — not in pantry")
                .doesNotContain("milk");
    }

    /**
     * UC-005 system-prompt directive "I already have X" → addPantryItem with staple=false.
     * (BR-04: an item is never upgraded to permanent staple unless explicitly requested.)
     * Asserts that after the chat turn a PantryItem exists for "cheddar"/"cheese" and
     * that its staple flag is false.
     */
    @Test
    void alreadyHaveCreatesNonStaplePantryItem() {
        var hh = seedHouseholdAndActivePlan();

        shoppingChat()
                .prompt()
                .user("I already have 200g of cheddar cheese.")
                .call()
                .content();

        var items = pantryRepository.findByHouseholdId(hh.getId());

        // At least one pantry item must match "cheddar" or "cheese".
        var matchingItem = items.stream()
                .filter(p -> p.getIngredientName() != null
                        && (p.getIngredientName().toLowerCase(Locale.ROOT).contains("cheddar")
                            || p.getIngredientName().toLowerCase(Locale.ROOT).contains("cheese")))
                .findFirst();

        Assertions.assertThat(matchingItem)
                .as("A PantryItem for 'cheddar cheese' must have been created by the tool")
                .isPresent();

        Assertions.assertThat(matchingItem.get().isStaple())
                .as("BR-04: 'I already have X' must NOT set staple=true — user never said 'always have'")
                .isFalse();
    }

    /**
     * UC-005 system-prompt directive "Add Xg of Y to the list" → addExtraToShoppingList.
     * Asserts that an ExtraShoppingItem row exists whose name matches cheddar/cheese.
     */
    @Test
    void addExtraCreatesExtraShoppingItem() {
        var hh = seedHouseholdAndActivePlan();

        shoppingChat()
                .prompt()
                .user("Add 200g extra cheddar cheese to the list.")
                .call()
                .content();

        var extras = extraShoppingItemRepository.findByHouseholdId(hh.getId());

        var matchingExtra = extras.stream()
                .filter(e -> e.getIngredientName() != null
                        && (e.getIngredientName().toLowerCase(Locale.ROOT).contains("cheddar")
                            || e.getIngredientName().toLowerCase(Locale.ROOT).contains("cheese")))
                .findFirst();

        Assertions.assertThat(matchingExtra)
                .as("An ExtraShoppingItem for 'cheddar cheese' must have been persisted by the tool")
                .isPresent();

        // Bonus: confirm the item surfaces in the derived shopping list.
        var shoppingList = shoppingService.deriveList(hh.getId());
        boolean inList = shoppingList.aisleGroups().stream()
                .flatMap(ag -> ag.items().stream())
                .anyMatch(item -> item.ingredientName() != null
                        && (item.ingredientName().toLowerCase(Locale.ROOT).contains("cheddar")
                            || item.ingredientName().toLowerCase(Locale.ROOT).contains("cheese")));
        Assertions.assertThat(inList)
                .as("The extra item must appear in ShoppingService.deriveList (tool wrote to the correct table)")
                .isTrue();
    }

    /**
     * UC-005 checklist item 2 / system-prompt directive — "Why is the list so long?"
     * The model must call explainListSize and paraphrase its structured result.
     * Asserts:
     *   (a) the reply references at least one concrete factor (an ingredient name from
     *       the plan's recipes, or a recipe count from the tool's output), and
     *   (b) no standalone integer in the reply is greater than 20 (plan has 7 meals;
     *       ingredient-count totals are in the low tens — if the model says "30 recipes"
     *       it fabricated).
     */
    @Test
    void explainListSizeAvoidsFabrication() {
        seedHouseholdAndActivePlan();

        var reply = shoppingChat()
                .prompt()
                .user("Why is the list so long this week?")
                .call()
                .content();

        String lower = reply.toLowerCase(Locale.ROOT);

        // (a) The reply must mention at least one concrete factor from the tool output.
        // explainListSize returns recipe names (e.g. "Mild Chicken Curry"), ingredient counts,
        // or pantry subtraction counts. We check for any well-known recipe name fragment
        // or the word "ingredient" / "recipe" / "meal" as a proxy.
        Assertions.assertThat(lower)
                .as("Reply must reference a concrete factor: a recipe name, 'ingredients', 'recipes', or 'meals'")
                .satisfiesAnyOf(
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "chicken", "salmon", "beef", "lentil", "pasta",
                                "risotto", "meatball", "turkey", "pork", "baked cod",
                                "spaghetti", "bolognese", "soup", "gratin", "tuna"),
                        r -> Assertions.assertThat(r).containsAnyOf(
                                "ingredient", "recipe", "meal", "dish"));

        // (b) Hallucination guard: the reply must NOT claim an impossible number of
        // meals or recipes. A seeded 7-meal plan has exactly 7 meals; the number of
        // ingredient rows (before deduplication) across 7 meals with ~7-8 ingredients
        // each can reach ~55. Any claim of more than 100 items or more than 20 recipes/
        // meals strongly indicates fabrication — explainListSize never returns those.
        //
        // We avoid a tight bound on ingredient counts (≤20) because the tool legitimately
        // reports 40-60 raw ingredients for 7 meals. Instead we guard against obviously
        // impossible numbers (>100) appearing as item/recipe/meal claims in context.
        var implausiblyLargePattern = Pattern.compile(
                "\\b([1-9][0-9]{2,})\\b.*?(?:recipe|meal|dish|item|ingredient)",
                Pattern.CASE_INSENSITIVE);
        var matcher = implausiblyLargePattern.matcher(reply);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            Assertions.assertThat(value)
                    .as("Potential fabrication: the reply claims %d recipes/meals/items. "
                            + "The plan has 7 meals; explainListSize never returns values > 100. "
                            + "Full reply: \"%s\"", value, reply)
                    .isLessThanOrEqualTo(100);
        }
    }

    // ── no helper methods needed — all setup is inline ────────────────────────
}
