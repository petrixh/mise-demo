package com.example.mise.ui.plan;

import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * UC-002 meal grid: one row per Mon–Sun day.
 * Renders meal name, meta line, tag pills, pin / status icon buttons.
 * Uses custom Div rows (not Vaadin Grid) for the flexible multi-column row structure.
 */
public class MealGrid extends Div {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("EEE");
    /** Threshold for "edited" pill: 60 seconds. */
    private static final long EDITED_THRESHOLD_SECONDS = 60;

    public MealGrid(Plan plan,
                    PlanService planService,
                    RecipeCatalog recipeCatalog,
                    MealCostCalculator costCalculator,
                    Consumer<Long> onPinToggle,
                    Consumer<Long> onMarkCooked,
                    Consumer<Long> onMarkSkipped) {
        addClassName("mise-meal-grid");

        // Build a date-to-meal map for fast lookup
        List<Meal> meals = planService.findMeals(plan.getId());
        Map<LocalDate, Meal> byDate = meals.stream()
                .collect(Collectors.toMap(Meal::getDate, m -> m));

        // Always render Mon–Sun for the plan week
        LocalDate monday = plan.getWeekStartDate();
        for (int d = 0; d < 7; d++) {
            LocalDate date = monday.plusDays(d);
            Meal meal = byDate.get(date);
            add(buildRow(date, meal, recipeCatalog, costCalculator, onPinToggle, onMarkCooked, onMarkSkipped));
        }
    }

    private Div buildRow(LocalDate date,
                         Meal meal,
                         RecipeCatalog recipeCatalog,
                         MealCostCalculator costCalculator,
                         Consumer<Long> onPinToggle,
                         Consumer<Long> onMarkCooked,
                         Consumer<Long> onMarkSkipped) {
        var row = new Div();
        row.addClassName("mise-meal-row");

        // Day chip
        var dayChip = new Span(date.format(DAY_FMT).toUpperCase());
        dayChip.addClassName("mise-day-chip");
        row.add(dayChip);

        if (meal == null) {
            // Empty slot
            var placeholder = new Div();
            placeholder.addClassName("mise-meal-info");
            var emptyName = new Paragraph("—");
            emptyName.addClassName("mise-meal-name");
            emptyName.addClassName("mise-meal-empty");
            var emptyHint = new Paragraph("ask Mise to fill");
            emptyHint.addClassName("mise-meal-meta");
            emptyHint.addClassName("mise-meal-empty");
            placeholder.add(emptyName, emptyHint);
            row.add(placeholder);
            return row;
        }

        // Check "edited" state (BR-04: within 60s of AI edit)
        boolean isEdited = meal.getLastEditedBy() == Meal.Editor.AI
                && meal.getLastEditedAt() != null
                && Instant.now().minusSeconds(EDITED_THRESHOLD_SECONDS)
                        .isBefore(meal.getLastEditedAt());
        if (isEdited) row.addClassName("edited");

        // Recipe lookup
        Recipe recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
        String recipeName = recipe != null ? recipe.getName() : meal.getRecipeRef();

        // Meal info column
        var info = new Div();
        info.addClassName("mise-meal-info");

        var name = new Paragraph(recipeName);
        name.addClassName("mise-meal-name");

        // Meta: prep · cost · kcal
        String meta = buildMeta(meal, recipe, costCalculator);
        var metaP = new Paragraph(meta);
        metaP.addClassName("mise-meal-meta");

        info.add(name, metaP);
        row.add(info);

        // Tags
        var tags = new Div();
        tags.addClassName("mise-meal-tags");

        if (recipe != null && recipe.getCategoryTags() != null) {
            if (recipe.getCategoryTags().contains("vegetarian") || recipe.getCategoryTags().contains("vegan")) {
                tags.add(pill("veg", "mise-tag-veg"));
            }
            boolean hasFish = recipe.getIngredients() != null && recipe.getIngredients().stream()
                    .anyMatch(i -> "fish".equalsIgnoreCase(i.getAisle())
                            || i.getName().toLowerCase().contains("fish")
                            || i.getName().toLowerCase().contains("salmon")
                            || i.getName().toLowerCase().contains("cod")
                            || i.getName().toLowerCase().contains("tuna")
                            || i.getName().toLowerCase().contains("shrimp")
                            || i.getName().toLowerCase().contains("prawn"));
            if (hasFish) tags.add(pill("fish", "mise-tag-fish"));
        }
        if (isEdited) tags.add(pill("edited", "mise-tag-edited"));

        row.add(tags);

        // Row action buttons (pin / cooked / skipped)
        var actions = new Div();
        actions.addClassName("mise-row-actions");

        var pinIcon = meal.isPinned() ? VaadinIcon.PIN.create() : VaadinIcon.PIN_POST.create();
        var pinBtn = new Button(pinIcon);
        pinBtn.setThemeName("tertiary icon small");
        if (meal.isPinned()) pinBtn.getElement().setAttribute("title", "Unpin meal");
        else pinBtn.getElement().setAttribute("title", "Pin meal");
        pinBtn.addClickListener(e -> {
            if (onPinToggle != null) onPinToggle.accept(meal.getId());
        });

        var cookedBtn = new Button((com.vaadin.flow.component.Component) VaadinIcon.CHECK.create());
        cookedBtn.setThemeName("tertiary icon small");
        cookedBtn.getElement().setAttribute("title", "Mark cooked");
        cookedBtn.addClickListener(e -> {
            if (onMarkCooked != null) onMarkCooked.accept(meal.getId());
        });

        var skipBtn = new Button((com.vaadin.flow.component.Component) VaadinIcon.CLOSE_SMALL.create());
        skipBtn.setThemeName("tertiary icon small");
        skipBtn.getElement().setAttribute("title", "Mark skipped");
        skipBtn.addClickListener(e -> {
            if (onMarkSkipped != null) onMarkSkipped.accept(meal.getId());
        });

        actions.add(pinBtn, cookedBtn, skipBtn);
        row.add(actions);

        return row;
    }

    private String buildMeta(Meal meal, Recipe recipe, MealCostCalculator costCalculator) {
        var parts = new StringBuilder();
        if (recipe != null) {
            parts.append(recipe.getPrepMinutes()).append("m");

            // Live cost from PriceCatalog; fall back to YAML estimatedCost only when 0
            BigDecimal liveCost = costCalculator.costFor(meal);
            if (liveCost.compareTo(BigDecimal.ZERO) > 0) {
                parts.append(" · €").append(String.format("%.2f", liveCost.doubleValue()));
            } else if (recipe.getEstimatedCost() != null) {
                parts.append(" · €").append(String.format("%.2f", recipe.getEstimatedCost()));
            }

            if (recipe.getMacros() != null && recipe.getMacros().getKcal() > 0) {
                int servings = meal.getServings() > 0 ? meal.getServings() : 1;
                int defServ = recipe.getDefaultServings() > 0 ? recipe.getDefaultServings() : 1;
                int kcal = recipe.getMacros().getKcal() * servings / defServ;
                parts.append(" · ").append(kcal).append(" kcal");
            }
        }
        return parts.length() > 0 ? parts.toString() : "—";
    }

    private Span pill(String text, String cssClass) {
        var s = new Span(text);
        s.addClassNames("mise-tag", cssClass);
        return s;
    }
}
