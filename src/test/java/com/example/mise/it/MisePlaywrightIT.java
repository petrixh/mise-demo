package com.example.mise.it;

import com.example.mise.it.support.TestAiConfig;
import com.example.mise.it.support.TestChatModel;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.vaadin.addons.dramafinder.AbstractBasePlaywrightIT;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(TestAiConfig.class)
public abstract class MisePlaywrightIT extends AbstractBasePlaywrightIT {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestChatModel chatModel;

    @Override
    public String getUrl() {
        return "http://localhost:" + port;
    }

    @BeforeEach
    void resetChatModelBetweenTests() {
        chatModel.reset();
    }
}
