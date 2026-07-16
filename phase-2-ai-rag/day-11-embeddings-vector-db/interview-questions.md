# Day 11 — Embeddings + Vector Databases: Interview Questions

1. Why can't you just use a regular SQL index for nearest-neighbor search over embeddings?
2. What's the difference between exact and approximate nearest neighbor search, and why does production RAG almost always use ANN?
3. How does chunk size affect retrieval quality, and how would you choose one for a support knowledge base?
4. How would you design multi-tenant isolation in a shared vector database?
5. What happens if you switch embedding models — can you just start using the new model on your existing vector index?
6. Explain how HNSW works at a high level, and why it's become the dominant ANN algorithm.
7. When would you choose pgvector over a dedicated vector database like Pinecone, and vice versa?
8. What's the difference between cosine similarity, Euclidean distance, and dot product, and when would the choice matter?
9. How would you evaluate whether your retrieval pipeline is actually returning relevant chunks, before ever involving the LLM?
10. Describe a failure mode where a retrieved chunk is "similar" but not actually useful, and how you'd guard against it in production.
