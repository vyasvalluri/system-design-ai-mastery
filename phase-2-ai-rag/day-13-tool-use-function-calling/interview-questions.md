# Day 13 — Tool Use + Function Calling: Interview Questions

1. Walk through what actually happens, end-to-end, when an LLM "calls a tool."
2. Why is JSON Schema for tool parameters important, beyond just documentation?
3. How would you prevent an agent from taking a high-risk action (like a large refund) fully autonomously?
4. What happens if a model keeps calling tools without ever reaching a final answer, and how do you defend against it?
5. When would you use parallel tool calls versus sequential ones?
6. How do you decide what should be logged as the "audit record" for an agent's actions?
7. What's the difference between the model hallucinating a tool call and the model calling a real tool with wrong arguments — and how do you defend against each?
8. How would you design tool descriptions to reduce the chance of the model over-using or under-using a given tool?
9. Describe how you'd test an agent's tool-use behavior before shipping a new tool to production.
10. How does tool use relate to the ReAct pattern, and what does today's single-round-trip tool call leave out compared to a full agent loop?
