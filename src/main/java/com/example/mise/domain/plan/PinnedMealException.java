package com.example.mise.domain.plan;

/**
 * Thrown when a tool-driven edit attempts to modify a pinned meal.
 * The message names the conflicting meal (date + recipeRef) so the LLM
 * can include it verbatim in its reply.
 */
public class PinnedMealException extends RuntimeException {

    public PinnedMealException(String message) {
        super(message);
    }
}
