package com.example.mise.config;

import com.example.mise.ai.CancellableLLMProvider;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import com.vaadin.flow.component.ai.provider.SpringAILLMProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * Wires Spring AI's {@link ChatModel} (autowired from {@code spring-ai-starter-model-openai},
 * see {@code application.properties}) into a Vaadin {@link LLMProvider}.
 *
 * <p>A new provider is built per consumer (prototype) so each
 * {@code AIOrchestrator} can manage its own history without contention.
 *
 * <p>UC-013: the {@link SpringAILLMProvider} is wrapped in a {@link CancellableLLMProvider}
 * so the in-flight response stream can be cancelled (the orchestrator exposes no stop API).
 * The concrete return type keeps the cancel handle reachable; it still satisfies the
 * {@link LLMProvider} injection point used by {@code AIOrchestrator}.
 */
@Configuration
public class AIConfig {

    @Bean
    @Scope("prototype")
    public CancellableLLMProvider llmProvider(@Qualifier("openAiChatModel") ChatModel chatModel) {
        return new CancellableLLMProvider(new SpringAILLMProvider(chatModel));
    }
}
