# Architecture

> Technology stack and application structure. `pom.xml` is the source of truth for versions. Do not modify `pom.xml` without asking.

---

## 1. Technology Stack

- Vaadin Flow (Aura theme) — server-side Java UI
- Spring Boot — auto-configuration, embedded Tomcat
- Java
- Maven (wrapper included)
- Database: [e.g., PostgreSQL, H2]
- Testing: [e.g., JUnit 5, TestBench]

---

## 2. Application Structure

```
com.example.specdriven/
  Application.java              — Spring Boot entry point
  [feature-package]/
    [FeatureView].java          — Vaadin @Route view
    [FeatureService].java       — Business logic (Spring @Service)
    [FeatureRepository].java    — Data access (Spring Data)
```

- Application CSS: `src/main/resources/META-INF/resources/styles.css`
