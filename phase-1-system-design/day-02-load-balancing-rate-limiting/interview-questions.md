# Day 2 — Load Balancing & Rate Limiting — Interview Questions

> Extracted from `README.md` for standalone review/quizzing. See the full
> day README for the analogy, concept walkthrough, and code.


**Q: "How would you design rate limiting for a distributed system with 10 servers?"**

> Per-server counters won't work — a user could send 100 requests to each of 10 servers (1000 total) and never trigger any limit. Use a centralized Redis store with a sliding window algorithm. All servers share the same Redis counter per user. For extreme scale, use Redis Cluster and hash user IDs to specific shards. Add a small local counter (Caffeine cache) to absorb burst traffic and reduce Redis round trips — sync to Redis every 100ms. Accept slight over-counting in exchange for latency reduction.

**Q: "What's the difference between a load balancer and an API gateway?"**

> A load balancer distributes traffic across identical server instances — it's dumb about what the request contains. An API gateway is a Layer 7 intelligent router that also handles auth, rate limiting, SSL termination, request transformation, and routing to different microservices based on the URL path. In practice, you use both: the API gateway sits in front for smart routing and cross-cutting concerns, then load balancers sit in front of each service cluster for horizontal scaling.

**Q: "How does consistent hashing work and why is it used?"**

> Classic modular hashing (`server = hash(key) % N`) breaks when you add or remove a server — every key remaps. Consistent hashing maps both servers and keys onto a ring (0–2³²). A key goes to the nearest server clockwise on the ring. Adding a server only remaps the keys between the new server and its predecessor — typically `1/N` of total keys, not all of them. Critical for distributed caches (Redis Cluster), CDNs, and load balancers with session affinity.

---
