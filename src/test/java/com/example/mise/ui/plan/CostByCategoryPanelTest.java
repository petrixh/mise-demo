package com.example.mise.ui.plan;

import com.example.mise.ui.shared.CategoryColors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the aisle→category mapping logic in CategoryColors.
 */
class CostByCategoryPanelTest {

    @Test
    void aisleToCategory_meat() {
        assertThat(CategoryColors.aisleToCategory("meat")).isEqualTo("Protein");
    }

    @Test
    void aisleToCategory_fish() {
        assertThat(CategoryColors.aisleToCategory("fish")).isEqualTo("Protein");
    }

    @Test
    void aisleToCategory_produce() {
        assertThat(CategoryColors.aisleToCategory("produce")).isEqualTo("Produce");
    }

    @Test
    void aisleToCategory_vegetables() {
        assertThat(CategoryColors.aisleToCategory("vegetables")).isEqualTo("Produce");
    }

    @Test
    void aisleToCategory_dryGoods() {
        assertThat(CategoryColors.aisleToCategory("dry-goods")).isEqualTo("Pantry");
    }

    @Test
    void aisleToCategory_dairy() {
        assertThat(CategoryColors.aisleToCategory("dairy")).isEqualTo("Dairy");
    }

    @Test
    void aisleToCategory_eggs() {
        assertThat(CategoryColors.aisleToCategory("eggs")).isEqualTo("Dairy");
    }

    @Test
    void aisleToCategory_unknown() {
        assertThat(CategoryColors.aisleToCategory("spice")).isEqualTo("Other");
        assertThat(CategoryColors.aisleToCategory(null)).isEqualTo("Other");
        assertThat(CategoryColors.aisleToCategory("")).isEqualTo("Other");
    }

    @Test
    void aisleToCategory_canned() {
        assertThat(CategoryColors.aisleToCategory("canned")).isEqualTo("Pantry");
    }

    @Test
    void aisleToCategory_seafood() {
        assertThat(CategoryColors.aisleToCategory("seafood")).isEqualTo("Protein");
    }
}
