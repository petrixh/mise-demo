---
name: spring-ai-base-url-v1-convention
description: Spring AI M5 flipped the base-url convention — MISE_MODEL_BASE_URL must now INCLUDE /v1
metadata:
  node_type: memory
  type: project
---

**Convention flipped with Spring AI 2.0.0-M5 (2026-06-10).** M5 replaced its own HTTP client with the official OpenAI Java SDK (`com.openai.*`), which appends `/chat/completions` directly to the base URL and ignores `spring.ai.openai.chat.completions-path`. So:

```
# Spring AI 2.0.0-M5+ (current)
export MISE_MODEL_BASE_URL=http://host:port/v1   # ✓ correct
export MISE_MODEL_BASE_URL=http://host:port      # ✗ 404 (NotFoundException: 404: null)

# Spring AI 2.0.0-M4 (historic — the old advice, now wrong)
# bare host:port was correct; adding /v1 produced /v1/v1/... and null replies
```

**Symptom of getting it wrong under M5:** `com.openai.errors.NotFoundException: 404: null` from `ChatCompletionServiceImpl.create` on every chat call.

**How to apply:** all committed defaults (`application.properties`, `application-ai-it.properties`), `application-local.properties(.example)`, and README already carry `/v1`. When rotating the endpoint in `application-local.properties`, keep the `/v1` suffix.
