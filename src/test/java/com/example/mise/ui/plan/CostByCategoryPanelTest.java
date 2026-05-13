package com.example.mise.ui.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the aisle→category mapping logic in CostByCategoryPanel.
 */
class CostByCategoryPanelTest {

    @Test
    void aisleToCategory_meat() {
        assertThat(CostByCategoryPanel.aisleToCategory("meat")).isEqualTo("Protein");
    }

    @Test
    void aisleToCategory_fish() {
        assertThat(CostByCategoryPanel.aisleToCategory("fish")).isEqualTo("Protein");
    }

    @Test
    void aisleToCategory_produce() {
        assertThat(CostByCategoryPanel.aisleToCategory("produce")).isEqualTo("Produce");
    }

    @Test
    void aisleToCategory_vegetables() {
        assertThat(CostByCategoryPanel.aisleToCategory("vegetables")).isEqualTo("Produce");
    }

    @Test
    void aisleToCategory_dryGoods() {
        assertThat(CostByCategoryPanel.aisleToCategory("dry-goods")).isEqualTo("Pantry");
    }

    @Test
    void aisleToCategory_dairy() {
        assertThat(CostByCategoryPanel.aisleToCategory("dairy")).isEqualTo("Dairy");
    }

    @Test
    void aisleToCategory_eggs() {
        assertThat(CostByCategoryPanel.aisleToCategory("eggs")).isEqualTo("Dairy");
    }

    @Test
    void aisleToCategory_unknown() {
        assertThat(CostByCategoryPanel.aisleToCategory("spice")).isEqualTo("Other");
        assertThat(CostByCategoryPanel.aisleToCategory(null)).isEqualTo("Other");
        assertThat(CostByCategoryPanel.aisleToCategory("")).isEqualTo("Other");
    }

    @Test
    void aisleToCategory_canned() {
        assertThat(CostByCategoryPanel.aisleToCategory("canned")).isEqualTo("Pantry");
    }

    @Test
    void aisleToCategory_seafood() {
        assertThat(CostByCategoryPanel.aisleToCategory("seafood")).isEqualTo("Protein");
    }
}
