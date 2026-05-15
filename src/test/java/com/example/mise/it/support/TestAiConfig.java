package com.example.mise.it.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the autoconfigured OpenAI {@link ChatModel} bean (named {@code openAiChatModel}, picked
 * up by {@code AIConfig#llmProvider} via {@code @Qualifier}) with a deterministic {@link TestChatModel}.
 *
 * <p>Requires {@code spring.main.allow-bean-definition-overriding=true} in
 * {@code application-it.properties} so this bean definition wins over the autoconfig.
 */
@TestConfiguration
public class TestAiConfig {

    @Bean(name = "openAiChatModel")
    @Primary
    public TestChatModel openAiChatModel() {
        return new TestChatModel();
    }
}
