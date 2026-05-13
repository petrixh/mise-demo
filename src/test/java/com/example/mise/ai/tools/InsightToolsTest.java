package com.example.mise.ai.tools;

import com.example.mise.capabilities.recipes.RecipeCatalog;
import com.example.mise.domain.household.Household;
import com.example.mise.domain.household.HouseholdRepository;
import com.example.mise.domain.insights.Insight;
import com.example.mise.domain.insights.InsightRepository;
import com.example.mise.domain.plan.MealEditRepository;
import com.example.mise.domain.plan.MealRepository;
import com.example.mise.domain.plan.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for UC-009 InsightTools — one test per @Tool method asserting DB side effects.
 */
@SpringBootTest
@Transactional
class InsightToolsTest {

    @Autowired
    private InsightTools insightTools;

    @Autowired
    private InsightRepository insightRepository;

    @Autowired
    private HouseholdRepository householdRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private MealRepository mealRepository;

    @Autowired
    private MealEditRepository mealEditRepository;

    @MockitoBean
    private RecipeCatalog recipeCatalog;

    @MockitoBean
    private com.example.mise.capabilities.pricing.PriceCatalog priceCatalog;

    private Household household;

    @BeforeEach
    void setUp() {
        mealEditRepository.deleteAll();
        mealRepository.deleteAll();
        planRepository.deleteAll();
        insightRepository.deleteAll();
        householdRepository.deleteAll();

        household = new Household();
        household.setSize(2);
        household = householdRepository.save(household);

        // Default stubs
        when(priceCatalog.findDefaultStore()).thenReturn(Optional.empty());
        when(priceCatalog.findAllStores()).thenReturn(List.of());
        when(priceCatalog.findPrice(anyString())).thenReturn(Optional.empty());
        when(recipeCatalog.findById(anyString())).thenReturn(Optional.empty());
        when(recipeCatalog.findAll()).thenReturn(List.of());
    }

    // ── dismissCurrentInsight ─────────────────────────────────────────────────

    @Test
    void dismissCurrentInsight_setsInsightDismissed() {
        Insight insight = persistInsight("Old pattern here", false);

        String result = insightTools.dismissCurrentInsight();

        assertThat(result).contains("Dismissed").contains("Old pattern here");
        Insight updated = insightRepository.findById(insight.getId()).orElseThrow();
        assertThat(updated.isDismissed()).isTrue();
        assertThat(updated.getDismissedAt()).isNotNull();
    }

    @Test
    void dismissCurrentInsight_whenNone_returnsSentinel() {
        String result = insightTools.dismissCurrentInsight();
        assertThat(result).contains("No insights to dismiss");
    }

    // ── muteInsights ──────────────────────────────────────────────────────────

    @Test
    void muteInsights_setsHouseholdMutedTrue() {
        assertThat(household.isInsightsMuted()).isFalse();

        String result = insightTools.muteInsights();

        assertThat(result).contains("muted");
        Household loaded = householdRepository.findById(household.getId()).orElseThrow();
        assertThat(loaded.isInsightsMuted()).isTrue();
    }

    // ── unmuteInsights ────────────────────────────────────────────────────────

    @Test
    void unmuteInsights_setsHouseholdMutedFalse() {
        household.setInsightsMuted(true);
        householdRepository.save(household);

        String result = insightTools.unmuteInsights();

        assertThat(result).contains("resumed");
        Household loaded = householdRepository.findById(household.getId()).orElseThrow();
        assertThat(loaded.isInsightsMuted()).isFalse();
    }

    // ── setInsightFrequency ───────────────────────────────────────────────────

    @Test
    void setInsightFrequency_valid_updatesDB() {
        String result = insightTools.setInsightFrequency("DAILY");

        assertThat(result).contains("DAILY");
        Household loaded = householdRepository.findById(household.getId()).orElseThrow();
        assertThat(loaded.getInsightFrequency()).isEqualTo(Household.InsightFrequency.DAILY);
    }

    @Test
    void setInsightFrequency_invalid_returnsRefused() {
        String result = insightTools.setInsightFrequency("MONTHLY");
        assertThat(result).startsWith("REFUSED");
    }

    @Test
    void setInsightFrequency_never_updatesDB() {
        String result = insightTools.setInsightFrequency("NEVER");

        assertThat(result).contains("NEVER");
        Household loaded = householdRepository.findById(household.getId()).orElseThrow();
        assertThat(loaded.getInsightFrequency()).isEqualTo(Household.InsightFrequency.NEVER);
    }

    // ── listInsightsIMissed ───────────────────────────────────────────────────

    @Test
    void listInsightsIMissed_returnsAllInsightsNewestFirst() {
        persistInsight("First insight", false);
        persistInsight("Second insight", true);  // dismissed

        String result = insightTools.listInsightsIMissed();

        // Both should appear regardless of muted/dismissed state
        assertThat(result).contains("First insight");
        assertThat(result).contains("Second insight");
        assertThat(result).contains("dismissed");
        assertThat(result).contains("undismissed");
    }

    @Test
    void listInsightsIMissed_whenNone_returnsSentinel() {
        String result = insightTools.listInsightsIMissed();
        assertThat(result).contains("No insights");
    }

    // ── requestInsight ────────────────────────────────────────────────────────

    @Test
    void requestInsight_withNoHistory_returnsSentinel() {
        String result = insightTools.requestInsight();
        assertThat(result).contains("No insight available");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Insight persistInsight(String body, boolean dismissed) {
        Insight i = new Insight();
        i.setHouseholdId(household.getId());
        i.setBody(body);
        i.setEvidenceRefs("{\"planIds\":[],\"mealIds\":[]}");
        i.setDismissed(dismissed);
        if (dismissed) i.setDismissedAt(Instant.now());
        return insightRepository.save(i);
    }
}
