# Day 14 — Guardrails + Reliability: Interview Questions

1. What's the difference between input guardrails and output guardrails, and why do you need both?
2. How would you detect that an LLM's answer is hallucinated rather than grounded in retrieved context?
3. Why do you need idempotency keys on tool calls specifically in a system with retry logic?
4. Walk through what a circuit breaker does differently than a simple retry loop, and why you'd want both.
5. A support agent's response looks fluent and confident but is actually wrong. Why is this a harder problem than the model just saying "I don't know"?
6. How would you decide what counts as a "high-risk action" that needs human-in-the-loop approval versus one the agent can execute autonomously?
7. How would you design a fallback chain across primary model → backup model → cached response → human escalation, and what should trigger each step down?
8. What's the risk of validating LLM output against a schema only on the happy path, and never testing malformed/adversarial outputs?
9. How would you tune timeout values differently for a real-time chat response versus an async batch job, and why?
10. What should go into an audit log for guardrail decisions, and how does that support a compliance review for an enterprise multi-tenant product?
11. How does defense-in-depth apply here — what's the risk of relying on a single guardrail layer instead of several?
12. If your groundedness checker itself has false positives (blocking correct answers) or false negatives (letting hallucinations through), how would you measure and tune that trade-off in production?
