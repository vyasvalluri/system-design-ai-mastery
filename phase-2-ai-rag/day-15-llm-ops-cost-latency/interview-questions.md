# Day 15 — LLM Ops: Cost + Latency: Interview Questions

1. Why doesn't an exact-match cache work well for LLM traffic, and what does semantic caching do differently?
2. What's the risk of setting a semantic cache's similarity threshold too loose, and how would you catch it?
3. How does model routing reduce cost without just degrading answer quality across the board?
4. What's the difference between reducing latency and reducing *perceived* latency, and where does streaming fit?
5. Why is token count from retrieved RAG context often the biggest hidden cost driver, and how would you reduce it?
6. How would you design observability so that "the AI bill is high" turns into an actionable next step?
7. How would you decide a cache entry's TTL for a support-answer semantic cache, and what happens if it's set too long?
8. Where would you place prompt caching in this pipeline, and what limits how much of the prompt is cacheable?
9. How do cost/latency optimizations interact with Day 14's guardrails — where's the tension, and how would you resolve it?
10. In a multi-tenant system, why does model routing need tenant context, not just query complexity signals?
11. What metrics would you put on a dashboard for an on-call engineer to catch a cost or latency regression before it becomes a billing surprise?
12. How would you A/B test whether a cheaper model tier is actually "good enough" for a given query category before routing traffic to it in production?
