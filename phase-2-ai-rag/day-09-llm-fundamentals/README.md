# Day 9 — LLM Fundamentals: Tokens, Context Windows, Temperature

> Learning approach: **Analogy first → Concept → Java code → Node.js code → Interview Q&A**
> AESP context: Every AI feature in AESP — ticket summarization, suggested replies, agent
> copilot — sits on top of an LLM call. Before touching RAG or agents, you need to know
> what's actually happening inside that call: how text becomes tokens, why the context
> window is a hard wall, and how temperature controls whether the model is a careful
> clerk or a creative writer.

---

## The postage-stamp analogy

| Concept | Analogy | Why it matters in AESP |
|---|---|---|
| Tokens | Postage — you pay per stamp, not per letter | Every word costs money and counts against limits |
| Context window | The size of the envelope | Ticket history + KB articles + prompt must all fit inside |
| Temperature | How improvisational the writer is | Ticket summaries need low temp; brainstorming needs high |

---

## Tokens — the real unit of everything

A token isn't a word. It's a chunk of text a model was trained to recognize as one piece —
roughly ¾ of a word in English. "Unbelievable" might split into `Un`, `believ`, `able`.
Short common words like "the" are one token; rare words, code, and non-English text
fragment into more tokens than you'd expect.

**Why this matters practically:** you're billed per token (input AND output), and the
context window is measured in tokens, not characters or words. A 2,000-word ticket
thread is roughly 2,600–3,000 tokens, not 2,000.

**Rule of thumb:** 1 token ≈ 4 characters ≈ ¾ of a word in English. Code and non-English
text run higher.

### Node.js — counting tokens before you send

```javascript
import { encoding_for_model } from 'tiktoken';

function estimateTokens(text, model = 'gpt-4') {
  const enc = encoding_for_model(model);
  const tokens = enc.encode(text);
  enc.free(); // tiktoken uses WASM — must free manually
  return tokens.length;
}

// AESP: check BEFORE calling the LLM, not after paying for it
function buildSummaryPrompt(ticketThread) {
  const prompt = `Summarize this support ticket in 2 sentences:\n\n${ticketThread}`;
  const tokenCount = estimateTokens(prompt);

  if (tokenCount > 6000) {
    // Truncate oldest comments first — keep most recent context
    throw new Error(`Prompt too large: ${tokenCount} tokens. Truncate thread first.`);
  }
  return prompt;
}
```

### Java — token-aware prompt construction

```java
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;

public class PromptBuilder {

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENCODING = REGISTRY.getEncodingForModel(ModelType.GPT_4);

    public static int estimateTokens(String text) {
        return ENCODING.encode(text).size();
    }

    // AESP: budget tokens across ticket thread + KB articles + system prompt
    public String buildSummaryPrompt(String ticketThread) {
        String prompt = "Summarize this support ticket in 2 sentences:\n\n" + ticketThread;
        int tokenCount = estimateTokens(prompt);

        if (tokenCount > 6000) {
            throw new IllegalArgumentException(
                "Prompt too large: " + tokenCount + " tokens. Truncate thread first.");
        }
        return prompt;
    }
}
```

**Senior-engineer habit:** never build a prompt without knowing its token cost first.
Silent truncation by the API is worse than a controlled truncation you designed.

---

## Context window — the envelope has a fixed size

The context window is the total tokens the model can "see" in one call — system prompt +
conversation history + retrieved documents + the model's own output, all combined. Go over
it and the call fails, or older content silently falls off the front.

**The AESP failure mode:** an agent working a long-running ticket accumulates 40 comments,
3 KB articles pulled in for context, and a growing conversation with the AI copilot. If you
naively concatenate everything into every call, you blow the window by ticket #15 and the
call errors out mid-shift.

**Three real strategies, not just "buy a bigger window":**

1. **Sliding window** — keep only the last N turns of conversation, drop the rest
2. **Summarization** — periodically compress old turns into a running summary, replace
   the raw text with the summary
3. **Retrieval instead of inclusion** — don't paste the whole KB into context; retrieve
   only the 3 most relevant chunks (this is where Day 11 — embeddings — comes in)

### Node.js — sliding window with summarization fallback

```javascript
const MAX_CONTEXT_TOKENS = 8000;
const RESERVED_FOR_OUTPUT = 1000;

async function buildConversationContext(ticketId, newMessage) {
  const history = await db.messages.findByTicket(ticketId);
  const budget = MAX_CONTEXT_TOKENS - RESERVED_FOR_OUTPUT;

  let included = [];
  let tokensUsed = estimateTokens(newMessage);

  // Walk backward from most recent — most recent context matters most
  for (let i = history.length - 1; i >= 0; i--) {
    const msgTokens = estimateTokens(history[i].content);
    if (tokensUsed + msgTokens > budget) {
      // Older messages don't fit — summarize what's left instead of dropping silently
      const summary = await summarizeOlderMessages(history.slice(0, i + 1));
      included.unshift({ role: 'system', content: `Earlier context: ${summary}` });
      break;
    }
    included.unshift(history[i]);
    tokensUsed += msgTokens;
  }

  return [...included, { role: 'user', content: newMessage }];
}
```

### Java — same pattern, Spring service

```java
@Service
public class ConversationContextBuilder {

    private static final int MAX_CONTEXT_TOKENS = 8000;
    private static final int RESERVED_FOR_OUTPUT = 1000;

    public List<Message> buildContext(String ticketId, String newMessage) {
        List<Message> history = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        int budget = MAX_CONTEXT_TOKENS - RESERVED_FOR_OUTPUT;

        Deque<Message> included = new ArrayDeque<>();
        int tokensUsed = PromptBuilder.estimateTokens(newMessage);

        for (int i = history.size() - 1; i >= 0; i--) {
            int msgTokens = PromptBuilder.estimateTokens(history.get(i).getContent());
            if (tokensUsed + msgTokens > budget) {
                String summary = summarizationService.summarize(history.subList(0, i + 1));
                included.addFirst(Message.system("Earlier context: " + summary));
                break;
            }
            included.addFirst(history.get(i));
            tokensUsed += msgTokens;
        }

        included.addLast(Message.user(newMessage));
        return new ArrayList<>(included);
    }
}
```

**Rule: always reserve budget for output tokens.** A common bug is filling the context
window entirely with input and leaving no room for the model to respond — the call
either fails or the response gets cut off mid-sentence.

---

## Temperature — how improvisational is the writer

Temperature controls randomness in token selection. At each step the model has a
probability distribution over the next possible token — temperature reshapes that
distribution before sampling.

- **Temperature 0** — always pick the highest-probability token. Deterministic (mostly),
  repeatable, safe.
- **Temperature 0.7–1.0** — sample more broadly. Creative, varied, occasionally surprising.
- **Temperature > 1.2** — noticeably erratic. Rarely useful in production.

**The AESP rule of thumb — match temperature to the job:**

| AESP task | Temperature | Why |
|---|---|---|
| Ticket summarization | 0–0.2 | You want the same summary every time, no invention |
| Classifying ticket priority | 0 | This is closer to classification than generation |
| Suggested reply drafts (agent picks one) | 0.6–0.8 | Some variety across 3 suggestions is useful |
| Internal brainstorming / synthetic test tickets | 0.9–1.1 | Diversity is the goal |

### Node.js — temperature per use case

```javascript
async function summarizeTicket(ticketText) {
  // Deterministic — same ticket should always produce the same summary
  return callLLM({
    messages: [{ role: 'user', content: `Summarize: ${ticketText}` }],
    temperature: 0.1,
    max_tokens: 150
  });
}

async function generateReplyOptions(ticketText) {
  // Some creative variance across the 3 drafts the agent will choose from
  const drafts = await Promise.all([0, 1, 2].map(() =>
    callLLM({
      messages: [{ role: 'user', content: `Draft a reply to: ${ticketText}` }],
      temperature: 0.7,
      max_tokens: 300
    })
  ));
  return drafts;
}
```

### Java — same distinction

```java
public class LlmTaskConfig {

    public LlmResponse summarizeTicket(String ticketText) {
        return llmClient.complete(LlmRequest.builder()
            .prompt("Summarize: " + ticketText)
            .temperature(0.1)   // deterministic — factual compression
            .maxTokens(150)
            .build());
    }

    public List<LlmResponse> generateReplyOptions(String ticketText) {
        return IntStream.range(0, 3)
            .mapToObj(i -> llmClient.complete(LlmRequest.builder()
                .prompt("Draft a reply to: " + ticketText)
                .temperature(0.7)   // variety across options is a feature here
                .maxTokens(300)
                .build()))
            .collect(Collectors.toList());
    }
}
```

**Common misconception to correct in an interview:** temperature 0 is not fully
deterministic across all providers due to floating-point non-determinism in batched GPU
inference. Close to deterministic, not guaranteed.

---

## Streaming — don't make the agent stare at a spinner

For anything user-facing (agent copilot, live ticket summaries), stream tokens as they're
generated instead of waiting for the full response. This is a UX requirement, not an
optimization — a 3-second wait feels broken; 3 seconds of streaming text feels fast.

### Node.js — streaming to the dashboard via SSE

```javascript
router.post('/tickets/:id/ai-summary', async (req, res) => {
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');

  const stream = await llmClient.stream({
    messages: [{ role: 'user', content: buildSummaryPrompt(req.params.id) }],
    temperature: 0.1
  });

  for await (const chunk of stream) {
    res.write(`data: ${JSON.stringify({ token: chunk.text })}\n\n`);
  }
  res.end();
});
```

### Java — streaming with Spring WebFlux

```java
@PostMapping(value = "/tickets/{id}/ai-summary", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> streamSummary(@PathVariable String id) {
    String prompt = promptBuilder.buildSummaryPrompt(ticketService.getThread(id));

    return llmClient.streamCompletion(prompt, 0.1)
        .map(chunk -> ServerSentEvent.builder(chunk.getText()).build());
}
```

---

## Decision rule — one question at a time

```
Does the output need to be reproducible / factual?
  YES → temperature 0-0.2
  NO (creative variety is the point) → temperature 0.6-1.0

Is the response shown live to a human?
  YES → stream tokens (SSE / WebSocket)
  NO (background batch job) → wait for full response, simpler code

Will the prompt grow unbounded (conversation, ticket thread)?
  YES → sliding window + summarization, budget for output tokens
  NO (single fixed-size input) → send as-is, no windowing logic needed
```

---

## Interview Q&A

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

*Next: [Day 10 — Prompt Engineering](../day-10-prompt-engineering/README.md)*
