package com.example.mise.config;

import org.h2.server.web.JakartaWebServlet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4.0 dropped the bundled H2 console auto-configuration, so the
 * `spring.h2.console.*` properties no longer register a servlet on their own.
 * This config registers {@link JakartaWebServlet} at {@code spring.h2.console.path}
 * with higher specificity than Vaadin's catch-all {@code /*} servlet, so the
 * console is reachable while the app is running.
 *
 * <p>Demo only: do not ship this in production.
 */
@Configuration
public class H2ConsoleConfig {

    @Bean
    public ServletRegistrationBean<JakartaWebServlet> h2ConsoleServlet(
            @Value("${spring.h2.console.path:/h2-console}") String path,
            @Value("${spring.h2.console.settings.web-allow-others:false}") boolean webAllowOthers) {

        var servlet = new JakartaWebServlet();
        var bean = new ServletRegistrationBean<>(servlet, path + "/*");
        bean.setName("h2-console");
        bean.setLoadOnStartup(1);
        bean.addInitParameter("webAllowOthers", String.valueOf(webAllowOthers));
        return bean;
    }
}
