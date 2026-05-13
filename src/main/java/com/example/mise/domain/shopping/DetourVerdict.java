package com.example.mise.domain.shopping;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of evaluating whether a detour to a second store is worth it (UC-006).
 *
 * @param storeId               the queried store id
 * @param storeName             the queried store display name
 * @param detourMinutes         minutes out of route for this store
 * @param totalSavings          total savings achievable by buying cheaper items at this store
 * @param itemsWorthSwitching   items that are cheaper at the queried store (name + per-item savings)
 * @param verdict               WORTH_IT, NOT_WORTH_IT, or INSUFFICIENT_DATA
 * @param reasoning             brief human-readable explanation of the verdict
 */
public record DetourVerdict(
        String storeId,
        String storeName,
        int detourMinutes,
        BigDecimal totalSavings,
        List<DetourItem> itemsWorthSwitching,
        Verdict verdict,
        String reasoning
) {

    public enum Verdict {
        WORTH_IT,
        NOT_WORTH_IT,
        INSUFFICIENT_DATA
    }

    /**
     * One item that is cheaper at the queried store.
     *
     * @param ingredientName  ingredient display name
     * @param savingsPerItem  how much cheaper this item is at the detour store
     */
    public record DetourItem(String ingredientName, BigDecimal savingsPerItem) {}
}
