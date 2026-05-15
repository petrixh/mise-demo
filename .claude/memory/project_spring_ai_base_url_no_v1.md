---
name: project-spring-ai-base-url-no-v1
description: "Spring AI's spring.ai.openai.base-url expects the host:port WITHOUT a trailing /v1 — it appends /v1/chat/completions internally. Including /v1 produces /v1/v1/chat/completions which most OpenAI-compatible servers handle as a non-error 200 with empty body, silently breaking everything downstream."
metadata: 
  node_type: memory
  type: project
  originSessionId: 465b0e19-fa5f-4bc9-82e1-ea2b6e965dff
---

`spring.ai.openai.base-url` (and the `MISE_MODEL_BASE_URL` env var that overrides it in `application-ai-it.properties`) must be the **bare host:port URL with no path** — e.g. `http://192.168.1.123:1234`, not `http://192.168.1.123:1234/v1`.

Spring AI internally appends `/v1/chat/completions`. If you include `/v1` yourself, the resulting URL becomes `/v1/v1/chat/completions`.

**Why this is hard to debug:** LM Studio (and many other OpenAI-compatible servers) handle the duplicated-`/v1` URL by returning HTTP 200 with an empty/non-conforming body and logging an internal warning. Spring AI's `OpenAiChatModel` swallows the response and emits `WARN: No choices returned for prompt: …`, which surfaces in the AIIT tests as `reply` being `null` and the assertion failure `Cannot invoke "String.toLowerCase(...)" because "reply" is null`. The symptoms look like model failure but are pure config.

**How to apply:** When pointing AIIT (or any Spring AI usage in this project) at an LM Studio / vLLM / llama.cpp-server endpoint, set the env var WITHOUT the `/v1` suffix:

```bash
export MISE_MODEL_BASE_URL=http://host:port      # ✓ correct
export MISE_MODEL_BASE_URL=http://host:port/v1   # ✗ produces /v1/v1/...
```

If the model loaded and a direct `curl http://host:port/v1/chat/completions` works but AIIT reports `reply: null`, check the LM Studio (or server) logs for `POST /v1/v1/chat/completions` first — it's almost always this.
