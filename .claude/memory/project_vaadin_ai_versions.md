---
name: project-vaadin-ai-versions
description: Vaadin 25.2 AI components are pinned to Spring AI 2.0.0-M4; newer milestones break the SpringAILLMProvider
metadata: 
  node_type: memory
  type: project
  originSessionId: 82ca33d1-3d0b-4549-bfc3-e449acd0c634
---

Vaadin AI components 25.2.0-alpha5 (`com.vaadin:vaadin-ai-components-flow`) are compiled against **Spring AI 2.0.0-M4** specifically. The pom declares direct deps on `spring-ai-client-chat:2.0.0-M4` and `spring-ai-model:2.0.0-M4`.

Newer milestones (2.0.0-M5, M6) renamed `MessageChatMemoryAdvisor$Builder.conversationId(String)` and the call inside Vaadin's `SpringAILLMProvider.<init>` (line 109) fails at bean creation time with:

```
NoSuchMethodError: 'org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor$Builder
  org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor$Builder.conversationId(java.lang.String)'
```

**Why:** Hit this on app/mise-prep-2 (2026-05-13) while wiring the AI backbone for Mise. First tried Spring AI 1.0.0 (failed: Spring Boot 4 removed `RestClientAutoConfiguration` that 1.x references). Then 2.0.0-M6 (failed: API drift above). Pinning to 2.0.0-M4 worked.

**How to apply:**
- In pom.xml, set `<spring-ai.version>2.0.0-M4</spring-ai.version>` and import `spring-ai-bom` of that version.
- Spring AI 1.x is incompatible with Spring Boot 4 entirely — it references `org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration` which no longer exists.
- When Vaadin republishes their AI components against a newer milestone, bump in lockstep. Don't try to mix.

**One related quirk:** Vaadin's `AIOrchestrator` creates the assistant `ChatMessage` with `messageId = null` (verified in `streamResponseToMessage` bytecode). Any persistence layer that dedupes on messageId will silently drop the assistant turn. Sync by list index against `repository.count()` instead. See `ConversationService.syncFromOrchestrator` in this repo.

Related: [[project-h2-console-autoconfig]] (Spring Boot 4 also dropped the H2 console auto-config).
