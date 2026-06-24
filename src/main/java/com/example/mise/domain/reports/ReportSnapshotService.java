package com.example.mise.domain.reports;

import com.example.mise.capabilities.recipes.Recipe;
import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.capabilities.recipes.RecipeIngredient;
import com.example.mise.capabilities.pricing.PriceCatalog;
import com.example.mise.domain.plan.Meal;
import com.example.mise.domain.plan.MealCostCalculator;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.Plan;
import com.example.mise.domain.plan.PlanRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;

/**
 * UC-012: owns the denormalized, read-only <b>reporting schema</b> that the AI
 * queries via {@link com.example.mise.ai.MiseDatabaseProvider}. Plain H2 tables
 * (no JPA entities) rebuilt from the domain model.
 *
 * <p>Freshness model: every AI query calls {@link #rebuildIfDirty()}, which
 * compares a cheap fingerprint of the source tables (row counts + max
 * {@code last_edited_at}) against the last build — every PlanService mutation
 * moves one of those, so the snapshot is rebuilt exactly when the data changed,
 * with no coupling into the mutators. Demo-scale data (a handful of plans)
 * makes a full rebuild cheap, so there is no incremental path.
 */
@Service
public class ReportSnapshotService {

    /**
     * The DDL the AI sees verbatim via {@code DatabaseProvider.getSchema()}.
     * Kept as the single source of truth — {@link #ensureTables()} executes
     * exactly these statements.
     */
    public static final String SCHEMA_DDL = """
            -- One row per meal across all ACTIVE and HISTORICAL plans,
            -- joined with recipe metadata and the current price catalog.
            CREATE TABLE IF NOT EXISTS meal_history (
              meal_id            BIGINT       PRIMARY KEY,
              plan_id            BIGINT       NOT NULL,
              week_start_date    DATE         NOT NULL,  -- Monday
              meal_date          DATE         NOT NULL,
              day_of_week        VARCHAR(9)   NOT NULL,  -- 'Monday'..'Sunday'
              recipe_id          VARCHAR(64)  NOT NULL,
              recipe_name        VARCHAR(200) NOT NULL,
              category_tags      VARCHAR(255),           -- comma-separated, e.g. 'vegetarian,quick'
              cuisine            VARCHAR(64),
              servings           INT          NOT NULL,
              prep_minutes       INT          NOT NULL,
              kcal_per_serving   INT,
              est_cost_eur       DECIMAL(8,2) NOT NULL,  -- meal total at current catalog prices
              status             VARCHAR(16)  NOT NULL,  -- 'PLANNED','EDITED','COOKED','SKIPPED'
              pinned             BOOLEAN      NOT NULL,
              edited_by_ai       BOOLEAN      NOT NULL,
              last_edited_at     TIMESTAMP
            );

            -- Per-meal ingredient cost bucketed into the five canonical
            -- categories: 'Protein','Produce','Pantry','Dairy','Other'.
            CREATE TABLE IF NOT EXISTS meal_category_cost (
              meal_id            BIGINT       NOT NULL,
              week_start_date    DATE         NOT NULL,
              category           VARCHAR(32)  NOT NULL,
              cost_eur           DECIMAL(8,2) NOT NULL
            );

            -- One row per plan with weekly rollups. Default scope: ACTIVE + HISTORICAL only.
            CREATE TABLE IF NOT EXISTS weekly_kpi (
              plan_id            BIGINT       PRIMARY KEY,
              week_start_date    DATE         NOT NULL,
              plan_status        VARCHAR(16)  NOT NULL,  -- 'ACTIVE','HISTORICAL'
              total_cost_eur     DECIMAL(8,2) NOT NULL,
              total_prep_minutes INT          NOT NULL,
              avg_kcal           INT,
              veg_meal_count     INT          NOT NULL,
              edited_meal_count  INT          NOT NULL
            );

            -- UC-011/UC-012 BR-11: forward-looking variants that ALSO include future PLANNED
            -- weeks. Same columns as their base tables. Query these ONLY for explicit
            -- forward-looking questions, otherwise use meal_history / weekly_kpi.
            CREATE TABLE IF NOT EXISTS meal_history_with_planned (
              meal_id            BIGINT       PRIMARY KEY,
              plan_id            BIGINT       NOT NULL,
              week_start_date    DATE         NOT NULL,
              meal_date          DATE         NOT NULL,
              day_of_week        VARCHAR(9)   NOT NULL,
              recipe_id          VARCHAR(64)  NOT NULL,
              recipe_name        VARCHAR(200) NOT NULL,
              category_tags      VARCHAR(255),
              cuisine            VARCHAR(64),
              servings           INT          NOT NULL,
              prep_minutes       INT          NOT NULL,
              kcal_per_serving   INT,
              est_cost_eur       DECIMAL(8,2) NOT NULL,
              status             VARCHAR(16)  NOT NULL,
              pinned             BOOLEAN      NOT NULL,
              edited_by_ai       BOOLEAN      NOT NULL,
              last_edited_at     TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS weekly_kpi_with_planned (
              plan_id            BIGINT       PRIMARY KEY,
              week_start_date    DATE         NOT NULL,
              plan_status        VARCHAR(16)  NOT NULL,  -- 'ACTIVE','HISTORICAL','PLANNED'
              total_cost_eur     DECIMAL(8,2) NOT NULL,
              total_prep_minutes INT          NOT NULL,
              avg_kcal           INT,
              veg_meal_count     INT          NOT NULL,
              edited_meal_count  INT          NOT NULL
            );

            -- One row per recorded edit (for "kept after AI edit" analyses).
            CREATE TABLE IF NOT EXISTS meal_edit_history (
              edit_id            BIGINT       PRIMARY KEY,
              meal_id            BIGINT       NOT NULL,
              changed_at         TIMESTAMP    NOT NULL,
              changed_by         VARCHAR(8)   NOT NULL,  -- 'USER' or 'AI'
              previous_recipe_id VARCHAR(64),
              reason             VARCHAR(500)
            );
            """;

    /** Plain-language notes appended to the DDL for the model. */
    public static final String SCHEMA_NOTES = """
            NOTES:
            - SQL dialect is H2 (NOT MySQL/Postgres). Issue SELECT statements only.
            - Dates: to bucket by month use FORMATDATETIME(meal_date, 'yyyy-MM'); by year use
              FORMATDATETIME(meal_date, 'yyyy') or YEAR(meal_date); MONTH(meal_date) gives 1..12.
              The MySQL function DATE_FORMAT(...) does NOT exist in H2 and will error — never use it.
              Example: SELECT FORMATDATETIME(meal_date, 'yyyy-MM') AS month, COUNT(*) AS meals
              FROM meal_history GROUP BY FORMATDATETIME(meal_date, 'yyyy-MM') ORDER BY month.
            - Tables are pre-aggregated snapshots; values are real, never estimates beyond
              the current price catalog. Do not invent columns or values.
            - meal_history, meal_category_cost and weekly_kpi cover ACTIVE and HISTORICAL weeks
              only — future PLANNED weeks are excluded by default. For forward-looking questions
              ("what will next month cost?"), query meal_history_with_planned / weekly_kpi_with_planned,
              which also include PLANNED weeks (filter on status / plan_status if you want planned only).
            - Use meal_date for chronological ordering, not day_of_week.
            - Currency is EUR. kcal_per_serving may be NULL for recipes without macros.
            - H2 reserves VALUE, KEY, ORDER as keywords; alias accordingly.
            """;

    private final JdbcTemplate jdbc;
    private final PlanRepository planRepository;
    private final MealRepository mealRepository;
    private final MealEditRepository mealEditRepository;
    private final RecipeCatalog recipeCatalog;
    private final MealCostCalculator mealCostCalculator;
    private final PriceCatalog priceCatalog;

    /** Fingerprint of the source tables at the last build; null = never built. */
    private volatile String lastFingerprint;

    public ReportSnapshotService(JdbcTemplate jdbc,
                                 PlanRepository planRepository,
                                 MealRepository mealRepository,
                                 MealEditRepository mealEditRepository,
                                 RecipeCatalog recipeCatalog,
                                 MealCostCalculator mealCostCalculator,
                                 PriceCatalog priceCatalog) {
        this.jdbc = jdbc;
        this.planRepository = planRepository;
        this.mealRepository = mealRepository;
        this.mealEditRepository = mealEditRepository;
        this.recipeCatalog = recipeCatalog;
        this.mealCostCalculator = mealCostCalculator;
        this.priceCatalog = priceCatalog;
    }

    /** Rebuilds the snapshot if the source tables changed since the last build. */
    public synchronized void rebuildIfDirty() {
        String fingerprint = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM plan) || '/' || (SELECT COUNT(*) FROM meal)
                    || '/' || (SELECT COUNT(*) FROM meal_edit)
                    || '/' || COALESCE(CAST((SELECT MAX(last_edited_at) FROM meal) AS VARCHAR), '-')""",
                String.class);
        if (!fingerprint.equals(lastFingerprint)) {
            rebuild();
            lastFingerprint = fingerprint;
        }
    }

    /** Full rebuild: recreate-if-absent, clear, reinsert from the domain model. */
    @Transactional
    public void rebuild() {
        ensureTables();
        jdbc.update("DELETE FROM meal_history");
        jdbc.update("DELETE FROM meal_category_cost");
        jdbc.update("DELETE FROM weekly_kpi");
        jdbc.update("DELETE FROM meal_history_with_planned");
        jdbc.update("DELETE FROM weekly_kpi_with_planned");
        jdbc.update("DELETE FROM meal_edit_history");

        for (Plan plan : planRepository.findAll()) {
            List<Meal> meals = mealRepository.findByPlanId(plan.getId());
            // UC-012 BR-11: future PLANNED weeks go ONLY into the *_with_planned variants;
            // the default tables stay ACTIVE/HISTORICAL so past/current analysis is unaffected.
            boolean planned = plan.getStatus() == Plan.Status.PLANNED;

            insertWeeklyKpi(plan, meals, "weekly_kpi_with_planned");
            if (!planned) insertWeeklyKpi(plan, meals, "weekly_kpi");

            for (Meal meal : meals) {
                insertMealRow(plan, meal, "meal_history_with_planned");
                if (!planned) {
                    insertMealRow(plan, meal, "meal_history");
                    insertCategoryCosts(plan, meal);
                    for (var edit : mealEditRepository.findByMealIdOrderByChangedAtDesc(meal.getId())) {
                        jdbc.update("""
                                INSERT INTO meal_edit_history
                                  (edit_id, meal_id, changed_at, changed_by, previous_recipe_id, reason)
                                VALUES (?,?,?,?,?,?)""",
                                edit.getId(), meal.getId(), Timestamp.from(edit.getChangedAt()),
                                edit.getChangedBy() != null ? edit.getChangedBy().name() : "USER",
                                edit.getPreviousRecipeRef(), edit.getReason());
                    }
                }
            }
        }
    }

    private void ensureTables() {
        for (String stmt : SCHEMA_DDL.split(";")) {
            String sql = stmt.strip();
            if (!sql.isEmpty()) {
                jdbc.execute(sql);
            }
        }
    }

    private void insertMealRow(Plan plan, Meal meal, String table) {
        Recipe recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
        String name = recipe != null ? recipe.getName() : meal.getRecipeRef();
        String tags = recipe != null && recipe.getCategoryTags() != null
                ? String.join(",", recipe.getCategoryTags()) : null;
        Integer kcal = recipe != null && recipe.getMacros() != null
                ? recipe.getMacros().getKcal() : null;
        // table is an internal constant ("meal_history" / "meal_history_with_planned"), never user input.
        jdbc.update("INSERT INTO " + table + """
                  (meal_id, plan_id, week_start_date, meal_date, day_of_week, recipe_id,
                   recipe_name, category_tags, cuisine, servings, prep_minutes,
                   kcal_per_serving, est_cost_eur, status, pinned, edited_by_ai, last_edited_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                meal.getId(), plan.getId(),
                Date.valueOf(plan.getWeekStartDate()), Date.valueOf(meal.getDate()),
                capitalize(meal.getDate().getDayOfWeek().name()),
                meal.getRecipeRef(), name, tags,
                recipe != null ? recipe.getCuisine() : null,
                meal.getServings(),
                recipe != null ? recipe.getPrepMinutes() : 0,
                kcal,
                mealCostCalculator.costFor(meal),
                meal.getStatus().name(), meal.isPinned(),
                meal.getLastEditedBy() == Meal.Editor.AI,
                meal.getLastEditedAt() != null ? Timestamp.from(meal.getLastEditedAt()) : null);
    }

    /** Same ingredient-aisle bucketing the original UC-007 category chart used. */
    private void insertCategoryCosts(Plan plan, Meal meal) {
        Recipe recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
        if (recipe == null || recipe.getIngredients() == null) return;
        int mealServings = meal.getServings() > 0 ? meal.getServings() : 1;
        int defaultServings = recipe.getDefaultServings() > 0 ? recipe.getDefaultServings() : 1;
        double scale = (double) mealServings / defaultServings;

        var byCat = new java.util.LinkedHashMap<String, Double>();
        for (RecipeIngredient ing : recipe.getIngredients()) {
            if (ing.isOptional()) continue;
            double price = priceCatalog.findPrice(ing.getName()).orElse(0.0);
            byCat.merge(ReportService.normaliseAisle(ing.getAisle()),
                    ing.getQuantity() * price * scale, Double::sum);
        }
        byCat.forEach((cat, cost) -> {
            if (cost > 0) {
                jdbc.update("""
                        INSERT INTO meal_category_cost (meal_id, week_start_date, category, cost_eur)
                        VALUES (?,?,?,?)""",
                        meal.getId(), Date.valueOf(plan.getWeekStartDate()), cat,
                        BigDecimal.valueOf(cost).setScale(2, RoundingMode.HALF_UP));
            }
        });
    }

    private void insertWeeklyKpi(Plan plan, List<Meal> meals, String table) {
        BigDecimal totalCost = BigDecimal.ZERO;
        int totalPrep = 0, kcalSum = 0, kcalCount = 0, vegCount = 0, editedCount = 0;
        for (Meal meal : meals) {
            totalCost = totalCost.add(mealCostCalculator.costFor(meal));
            Recipe recipe = recipeCatalog.findById(meal.getRecipeRef()).orElse(null);
            if (recipe != null) {
                totalPrep += recipe.getPrepMinutes();
                if (recipe.getMacros() != null) {
                    kcalSum += recipe.getMacros().getKcal();
                    kcalCount++;
                }
                if (recipe.getCategoryTags() != null
                        && recipe.getCategoryTags().contains("vegetarian")) {
                    vegCount++;
                }
            }
            if (meal.getStatus() == Meal.Status.EDITED) editedCount++;
        }
        // table is an internal constant ("weekly_kpi" / "weekly_kpi_with_planned"), never user input.
        jdbc.update("INSERT INTO " + table + """
                  (plan_id, week_start_date, plan_status, total_cost_eur,
                   total_prep_minutes, avg_kcal, veg_meal_count, edited_meal_count)
                VALUES (?,?,?,?,?,?,?,?)""",
                plan.getId(), Date.valueOf(plan.getWeekStartDate()), plan.getStatus().name(),
                totalCost, totalPrep, kcalCount > 0 ? kcalSum / kcalCount : null,
                vegCount, editedCount);
    }

    private static String capitalize(String upper) {
        return upper.charAt(0) + upper.substring(1).toLowerCase();
    }
}
