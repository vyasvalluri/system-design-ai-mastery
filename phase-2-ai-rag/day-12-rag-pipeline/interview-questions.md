# Day 12 — RAG Pipeline Deep Dive: Interview Questions

1. Walk through the stages of a production RAG pipeline, beyond just "embed and retrieve."
2. Why add a re-ranking step if the vector search already returns results sorted by similarity?
3. What is hybrid search and why would you use it over pure vector search?
4. What's "lost-in-the-middle" and how does it affect how you assemble RAG context?
5. How do you evaluate a RAG system, and why is that different from evaluating the LLM alone?
6. How would you handle query rewriting for a multi-turn conversation, where the latest message alone is ambiguous?
7. What's reciprocal rank fusion, and why not just average the dense and sparse scores directly?
8. How do you decide how many chunks (top-K) to retrieve, and how does that interact with re-ranking?
9. Describe a scenario where a RAG system should say "I don't know" instead of answering, and how you'd enforce that behavior.
10. How would you design a CI/regression gate for a RAG pipeline so that a change to chunking or re-ranking doesn't silently degrade answer quality?
