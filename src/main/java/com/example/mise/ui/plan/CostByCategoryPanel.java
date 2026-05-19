package com.example.mise.ui.plan;

import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.insights.Insight;
import com.example.mise.domain.insights.InsightService;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanService;
import com.example.mise.ui.shared.CategoryColors;
import com.example.mise.ui.shared.MiseChart;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.charts.model.*;
import com.vaadin.flow.component.charts.model.style.SolidColor;
import com.vaadin.flow.component.charts.model.style.Style;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Cost-by-category panel: horizontal bars with category colors.
 * Categories derived from {@code RecipeIngredient.aisle}.
 *
 * Aisle → category mapping:
 *   meat / fish / seafood / poultry   → Protein  (#7F77DD)
 *   produce / vegetables / fruit      → Produce  (#1D9E75)
 *   dry-goods / pantry / canned / oil → Pantry   (#D85A30)
 *   dairy / eggs / cheese             → Dairy    (#D4537E)
 *   anything else                     → Other    (#B4B2A9)
 */
public class CostByCategoryPanel extends Div {

    public CostByCategoryPanel(Plan plan,
                                PlanService planService,
                                RecipeCatalog recipeCatalog,
                                PriceCatalog priceCatalog,
                                InsightService insightService,
                                Long householdId,
                                Consumer<String> onSubmitChat) {
        addClassName("mise-category-panel");
        getElement().setAttribute("data-testid", "cost-by-category-panel");

        var title = new Paragraph("COST BY CATEGORY");
        title.addClassName("mise-category-panel-title");
        add(title);

        // Accumulate costs per category
        Map<String, Double> costs = new LinkedHashMap<>();
        for (String cat : CategoryColors.ORDER) costs.put(cat, 0.0);

        List<Meal> meals = planService.findMeals(plan.getId());
        for (var meal : meals) {
            var recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
            if (recipe == null || recipe.getIngredients() == null) continue;
            for (var ing : recipe.getIngredients()) {
                if (ing.isOptional()) continue;
                String category = CategoryColors.aisleToCategory(ing.getAisle());
                double price = priceCatalog.findPrice(ing.getName()).orElse(0.0);
                costs.merge(category, price, Double::sum);
            }
        }

        // If everything is zero (no price data), show a note
        boolean noPriceData = costs.values().stream().allMatch(v -> v <= 0);
        if (noPriceData) {
            var note = new Paragraph("No price data available.");
            note.addClassName("mise-plan-no-data-note");
            add(note);
        } else {
            add(buildCategoryChart(costs));
        }

        // AI insights section — two stacked lines under the cost bars:
        //   1) Local cost insight (mockup §"Salmon Friday accounts for 35% of protein cost…"),
        //      computed from the cost-by-category totals: picks the dominant category and the
        //      meal that contributes the most to it.
        //   2) The cross-view Mise insight from InsightService (formerly rendered as a top
        //      banner). Dismissable + actionable via the chat — same data flow as the banner,
        //      just rendered inline so the Plan view has one coherent "AI insights" area
        //      instead of a top banner plus a sidebar note.
        // TODO(UC-008): replace (1) with a model-generated insight via InsightService once a
        // Plan-specific generator lands; today (1) uses simple arithmetic.
        if (!noPriceData) {
            String insightText = buildInsightText(meals, costs, recipeCatalog, priceCatalog);
            if (insightText != null) {
                add(buildLocalInsight(insightText));
            }
        }

        if (insightService != null && householdId != null) {
            try {
                insightService.currentInsight(householdId).ifPresent(insight ->
                        add(buildMiseInsight(insight, insightService, onSubmitChat)));
            } catch (Exception ignored) {
                // Never break the sidebar for insight rendering.
            }
        }
    }

    private Div buildLocalInsight(String text) {
        var box = new Div();
        box.addClassName("mise-ai-insight");
        box.getElement().setAttribute("data-testid", "plan-ai-insight");

        var icon = VaadinIcon.INFO_CIRCLE_O.create();
        icon.addClassName("mise-ai-insight-icon");

        var body = new Span(text);
        box.add(icon, body);
        return box;
    }

    /**
     * Renders the cross-view "Mise insight" from {@link InsightService} as a second line
     * in the AI insights section. Matches the local-insight visual but adds inline
     * "Act on it" + dismiss controls — the same affordances the top banner used to carry.
     */
    private Div buildMiseInsight(Insight insight, InsightService insightService,
                                 Consumer<String> onSubmitChat) {
        var box = new Div();
        box.addClassName("mise-ai-insight");
        box.addClassName("mise-ai-insight-actionable");
        box.getElement().setAttribute("data-testid", "insight-banner");

        var icon = VaadinIcon.LIGHTBULB.create();
        icon.addClassName("mise-ai-insight-icon");

        var body = new Span(insight.getBody());
        body.addClassName("mise-ai-insight-body");
        body.getElement().setAttribute("data-testid", "insight-banner-body");

        String actPhrase = deriveActPhrase(insight.getBody());
        var actBtn = new Button("Act on it");
        actBtn.addClassName("mise-ai-insight-act");
        actBtn.getElement().setAttribute("data-testid", "insight-banner-act");
        actBtn.getElement().setAttribute("aria-label", "Act on this insight");
        actBtn.addClickListener(e -> {
            if (onSubmitChat != null) onSubmitChat.accept(actPhrase);
        });

        var dismissBtn = new Button("×");
        dismissBtn.addClassName("mise-ai-insight-dismiss");
        dismissBtn.getElement().setAttribute("data-testid", "insight-banner-dismiss");
        dismissBtn.getElement().setAttribute("aria-label", "Dismiss insight");
        dismissBtn.addClickListener(e -> {
            try {
                insightService.dismiss(insight.getId());
            } catch (Exception ignored) {
                // banner disappears regardless
            }
            box.setVisible(false);
        });

        box.add(icon, body, actBtn, dismissBtn);
        return box;
    }

    /**
     * Mirrors MainLayout's previous deriveActPhrase: pre-fills a plan-lock for the
     * canonical "vegetarian dinners" insight, otherwise passes the body verbatim
     * to the model.
     */
    private static String deriveActPhrase(String body) {
        if (body != null && body.toLowerCase().contains("vegetarian")) {
            return "lock in 3 vegetarian dinners this week";
        }
        return body;
    }

    /**
     * Builds the insight line shown beneath the cost-by-category bars.
     * Identifies the dominant category and the single meal contributing the most cost
     * to it, formats it as "<MealName> on <Day> accounts for <pct>% of <category> cost."
     * Returns null when there's not enough signal (no priced meals, ties only, etc.).
     */
    private static final DateTimeFormatter INSIGHT_DAY_FMT = DateTimeFormatter.ofPattern("EEEE");

    private String buildInsightText(List<Meal> meals, Map<String, Double> categoryTotals,
                                    RecipeCatalog recipeCatalog, PriceCatalog priceCatalog) {
        String topCategory = null;
        double topCategoryCost = 0;
        for (var e : categoryTotals.entrySet()) {
            if (e.getValue() > topCategoryCost) {
                topCategory = e.getKey();
                topCategoryCost = e.getValue();
            }
        }
        if (topCategory == null || topCategoryCost <= 0) return null;

        Meal topMeal = null;
        double topMealCost = 0;
        for (var meal : meals) {
            var recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
            if (recipe == null || recipe.getIngredients() == null) continue;
            double mealCatCost = 0;
            for (var ing : recipe.getIngredients()) {
                if (ing.isOptional()) continue;
                if (topCategory.equals(CategoryColors.aisleToCategory(ing.getAisle()))) {
                    mealCatCost += priceCatalog.findPrice(ing.getName()).orElse(0.0);
                }
            }
            if (mealCatCost > topMealCost) {
                topMealCost = mealCatCost;
                topMeal = meal;
            }
        }
        if (topMeal == null || topMealCost <= 0) return null;

        var recipe = recipeCatalog.findById(topMeal.getRecipeRef()).orElse(null);
        String mealName = recipe != null ? recipe.getName() : topMeal.getRecipeRef();
        String day = topMeal.getDate().format(INSIGHT_DAY_FMT);
        int pct = (int) Math.round((topMealCost / topCategoryCost) * 100.0);

        return String.format("%s on %s accounts for %d%% of %s cost. Ask Mise for a cheaper swap.",
                mealName, day, pct, topCategory.toLowerCase());
    }

    private MiseChart buildCategoryChart(Map<String, Double> costs) {
        List<String> activeCats = CategoryColors.ORDER.stream()
                .filter(cat -> costs.getOrDefault(cat, 0.0) > 0)
                .toList();

        MiseChart chart = new MiseChart(ChartType.BAR);
        Configuration conf = chart.getConfiguration();
        conf.setTitle("");

        // Three-column row layout matching the mockup:
        //   left gutter:  category name (X-axis label, right-aligned)
        //   middle:       thin bar (pointWidth = 8px)
        //   right gutter: €value (data label, outside the bar's right end)
        conf.getChart().setMarginTop(4);
        conf.getChart().setMarginBottom(4);
        conf.getChart().setMarginLeft(64);   // fits "Protein"/"Produce" right-aligned
        conf.getChart().setMarginRight(52);  // fits "€XX.XX" with padding

        XAxis x = new XAxis();
        x.setCategories(activeCats.toArray(String[]::new));
        x.setLineWidth(0);
        x.setTickWidth(0);
        Labels xLabels = new Labels();
        Style xLabelStyle = new Style();
        xLabelStyle.setFontSize("10px");
        xLabelStyle.setColor(MiseChart.LABEL);
        xLabels.setStyle(xLabelStyle);
        x.setLabels(xLabels);
        conf.addxAxis(x);

        // Value axis: hide entirely — amounts are shown by the data labels.
        YAxis y = new YAxis();
        y.setTitle(new AxisTitle(""));
        Labels yLabels = new Labels();
        yLabels.setEnabled(false);
        y.setLabels(yLabels);
        y.setGridLineWidth(0);
        conf.addyAxis(y);

        conf.setLegend(new Legend(false));

        PlotOptionsBar barOpts = new PlotOptionsBar();
        barOpts.setBorderWidth(0);
        barOpts.setBorderRadius(2);
        barOpts.setPointWidth(8);          // thin bars per the mockup
        barOpts.setMinPointLength(6);      // tiny values still get a visible nub
        DataLabels dl = new DataLabels();
        dl.setEnabled(true);
        dl.setFormat("€{y:.2f}");
        dl.setInside(false);               // render past the right end of the bar
        dl.setCrop(false);
        dl.setOverflow("allow");
        Style dlStyle = new Style();
        dlStyle.setColor(MiseChart.LABEL); // matches the panel text tone
        dlStyle.setFontSize("10px");
        dl.setStyle(dlStyle);
        barOpts.setDataLabels(dl);
        conf.setPlotOptions(barOpts);

        DataSeries series = new DataSeries();
        for (String cat : activeCats) {
            DataSeriesItem item = new DataSeriesItem(cat, costs.get(cat));
            String hex = CategoryColors.HEX.get(cat);
            if (hex != null) item.setColor(new SolidColor(hex));
            series.add(item);
        }
        conf.addSeries(series);

        chart.applyTheme();
        chart.setWidthFull();
        // 24px per row: 8px bar centred with ~8px above/below for breathing room.
        chart.setHeight((activeCats.size() * 24 + 8) + "px");
        return chart;
    }

}
