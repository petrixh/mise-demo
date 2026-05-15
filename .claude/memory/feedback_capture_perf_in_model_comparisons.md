---
name: feedback-capture-perf-in-model-comparisons
description: "When comparing LLMs against each other, capture token-generation speed (tok/s) and time-to-first-token alongside quality metrics — perf is a first-class axis of the comparison, not an afterthought"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 465b0e19-fa5f-4bc9-82e1-ea2b6e965dff
---

When running a multi-model comparison (e.g. the local-model bake-off in `petrixh/mise-demo#4`), **include perf alongside quality**. Quality-only tables miss half the picture for demo / production decisions.

What to capture per model (when the serving stack exposes it — LM Studio does via the `stats` object on each `/v1/chat/completions` response):
- **Tokens/sec (generation)** — output throughput
- **Time to first token** — perceived responsiveness, especially for streaming UIs
- **Prompt tokens/sec** — input-processing throughput; matters when system prompts and tool defs are large

Run a **fixed perf probe 3× per model** (~1.5–2k-token context-shaped prompt → ~200-token response) and average across runs to smooth jitter. Use the same probe for every model so the numbers are comparable.

**Same-HW caveat:** perf numbers are only comparable for models hosted on the same hardware. Cross-host rows in a comparison table need an explicit "n/a (different HW)" perf cell to avoid misleading readers — e.g. an M3 Max-hosted 35B vs an M2 Pro-hosted 4B isn't apples-to-apples on tok/s.

**Why:** "Fast but dumb" and "smart but slow" both matter for the deployment decision. Picking the demo model is a perf-vs-quality tradeoff; picking the fine-tune base is mostly quality; picking the "runs on a laptop" punchline model is mostly perf. The comparison table should make all three calls easy.

**How to apply:** Default to including perf when planning any LLM bake-off / model-comparison workstream. Don't wait to be asked.
