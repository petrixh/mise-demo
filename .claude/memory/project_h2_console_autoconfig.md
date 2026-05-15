---
name: project-h2-console-autoconfig
description: Spring Boot 4.0 dropped H2ConsoleAutoConfiguration — the H2 web servlet must be registered explicitly
metadata: 
  node_type: memory
  type: project
  originSessionId: 82ca33d1-3d0b-4549-bfc3-e449acd0c634
---

Spring Boot 4.0 no longer ships `H2ConsoleAutoConfiguration`, so setting `spring.h2.console.enabled=true` / `spring.h2.console.path=...` in `application.properties` is inert — nothing registers a servlet, and Vaadin's catch-all `/*` then serves the SPA shell for every `/h2-console/*` URL (including `login.jsp`, `style.css`, etc.).

**Why:** Hit this on app/mise-prep 2026-05-13 while wiring the H2 console for the Mise demo. Curl saw HTTP 200 but the response body was the Vaadin SPA, not H2's login form, which made the misconfiguration look like a Vaadin routing problem rather than a missing servlet.

**How to apply:** In any Spring Boot 4.x project that needs the H2 console, add a `@Configuration` class registering `org.h2.server.web.JakartaWebServlet` via `ServletRegistrationBean<JakartaWebServlet>` at `${spring.h2.console.path}/*`. See `src/main/java/com/example/mise/config/H2ConsoleConfig.java` for the pattern. The H2 dependency must NOT be `runtime` scope (the servlet class is needed at compile time).

Related: [[project-mise-stack-choices]] (when written) — Vaadin 25.2 alpha + Spring AI + JPA/H2 stack.
