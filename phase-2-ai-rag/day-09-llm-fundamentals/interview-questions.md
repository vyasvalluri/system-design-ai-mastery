# Day 9 — LLM Fundamentals — Interview Questions

> Extracted from `README.md` for standalone review/quizzing. See the full
> day README for the analogy, concept walkthrough, and code.

**Q: What is a token, and why doesn't "1 token = 1 word" work as a mental model?**
A token is a subword chunk from the model's vocabulary — roughly ¾ of an English word.
Common words are one token; rare words, code, and non-English text split into more
tokens than their word count suggests. Billing and context limits are both measured in
tokens, so word-count estimates under- or over-shoot cost and capacity.

**Q: What happens when you exceed the context window?**
Either the API call fails outright, or (depending on the client library) older content
silently gets truncated from the front — which can drop the system prompt or early
instructions without warning. Production systems should manage the window explicitly
rather than relying on the API to fail safely.

**Q: When would you use temperature 0 vs 0.8?**
0 for tasks with one correct answer — summarization, classification, extraction — where
reproducibility matters more than variety. 0.7–0.9 for tasks where diversity is useful,
like generating multiple draft replies for an agent to choose from, or synthetic test
data generation.

**Q: Why stream responses instead of waiting for the full completion?**
Perceived latency. A 3-second wait with no feedback feels broken to a user; the same
3 seconds shown as incrementally appearing text feels responsive. It's a UX
requirement for any user-facing AI feature, not a performance optimization.

**Q: How do you handle a growing conversation that will eventually exceed the context
window?**
Three real strategies: sliding window (drop oldest turns), summarization (compress old
turns into a running summary and replace the raw text), or retrieval (don't include full
history at all — retrieve only what's relevant per turn). Production systems usually
combine sliding window for recent turns with summarization for everything older.

---

*Next: [Day 10 — Prompt Engineering](../day-10-prompt-engineering/interview-questions.md)*
