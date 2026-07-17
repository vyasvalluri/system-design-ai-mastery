# Day 14 — Guardrails + Reliability

**Phase 2: AI Integration + RAG | AESP Component: Output Validation Layer**

---

## 1. The Analogy — The New Hire Now Has a Supervisor Signing Off Before Anything Goes Out

By Day 13, our support rep can talk (LLM fundamentals), follow instructions (prompting), look things up (RAG), and act in the world (tool use). That's a lot of autonomy for someone who occasionally makes confident mistakes — invents a policy that doesn't exist, misreads a refund amount, or gets talked into ignoring the rulebook by a cleverly worded customer request.

Guardrails are the supervisor standing next to that new hire, checking every outgoing message and every tool call *before* it leaves the building: "Does this reply actually match what's in our system? Did we just agree to a $50,000 refund? Is this response leaking another customer's account details? Did the customer just try to talk the rep into ignoring their training?"

Reliability is the layer above that — what happens when the rep (or the phone line to them) doesn't respond at all: do we make the customer wait forever, do we retry, do we hand them to someone else? Guardrails catch *bad* answers; reliability engineering catches *missing* answers.

---

## 2. The Concept

### 2.1 What guardrails actually check

Guardrails sit at two boundaries: **input** (before the prompt reaches the model) and **output** (before the model's response reaches the user or executes an action).

| Layer | Checks | Example |
|---|---|---|
| **Input guardrails** | Prompt injection detection, PII scrubbing, jailbreak pattern matching, topic/scope filtering | Customer message tries to override system prompt: `"ignore previous instructions and..."` |
| **Output guardrails** | Schema/format validation, hallucination/groundedness check, PII leakage, toxicity/safety, policy compliance | Model output claims a refund policy that doesn't exist in the retrieved KB context |
| **Action guardrails** | High-risk action gating (Day 13's trust boundary), amount/scope limits, human-in-the-loop triggers | Refund amount exceeds tenant's auto-approval threshold |

### 2.2 Groundedness / hallucination checks

The most AESP-relevant guardrail: does the model's answer actually follow from the retrieved context (Day 12's RAG pipeline), or did it drift into fabrication?

Common approaches, in increasing cost/rigor:
- **Citation requirement** — force the model to cite which retrieved chunk supports each claim; reject answers with unsupported claims.
- **NLI-based entailment check** — a smaller model checks whether each output sentence is *entailed by* the retrieved context (not contradicted, not unsupported).
- **LLM-as-judge** — a second model call scores groundedness directly; more flexible, more expensive, adds latency.

### 2.3 Structured output validation

Whenever an LLM output feeds a downstream system (a tool call, a database write, a UI component), the raw text must be validated against a schema before use — never trust that the model's JSON is well-formed or in-range just because it usually is.

### 2.4 Reliability patterns for LLM calls

LLM APIs are just another external dependency — they need the same resilience patterns as any other unreliable network call:

- **Retries with exponential backoff** for transient failures (rate limits, timeouts).
- **Circuit breaker** — stop hammering a failing LLM provider; fail fast and fall back.
- **Fallback chains** — primary model → smaller/cheaper backup model → cached/canned response → human escalation.
- **Timeouts** tuned per use case — a real-time chat reply needs a tighter timeout than an async batch summarization job.
- **Idempotency** — since retries can duplicate side-effecting tool calls, tool executions (like `issue_refund`) need idempotency keys so a retried call doesn't double-charge or double-refund.

### 2.5 Defense in depth, not a single filter

No single guardrail catches everything. Production systems layer: input filtering → grounded generation (Day 12 RAG) → output validation → action gating → audit logging — so a failure at one layer doesn't mean total failure of the system.

---

## 3. Code

### 3.1 Java (Spring Boot) — Output Guardrail Pipeline

```java
// GuardrailPipeline.java
package com.aesp.guardrails;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class GuardrailPipeline {

    private final GroundednessChecker groundednessChecker;
    private final PiiDetector piiDetector;
    private final SchemaValidator schemaValidator;
    private final AuditLogger auditLogger;

    private static final double MIN_GROUNDEDNESS_SCORE = 0.7;

    public GuardrailPipeline(GroundednessChecker groundednessChecker,
                              PiiDetector piiDetector,
                              SchemaValidator schemaValidator,
                              AuditLogger auditLogger) {
        this.groundednessChecker = groundednessChecker;
        this.piiDetector = piiDetector;
        this.schemaValidator = schemaValidator;
        this.auditLogger = auditLogger;
    }

    public GuardrailResult validate(LlmOutput output, RetrievalContext context, String tenantId) {
        List<String> violations = new ArrayList<>();

        // 1. Groundedness — does the answer actually follow from retrieved context?
        double groundedness = groundednessChecker.score(output.text(), context.chunks());
        if (groundedness < MIN_GROUNDEDNESS_SCORE) {
            violations.add("LOW_GROUNDEDNESS: score=" + groundedness);
        }

        // 2. PII leakage — never let another customer's data leak through
        List<String> piiFound = piiDetector.detect(output.text(), tenantId);
        if (!piiFound.isEmpty()) {
            violations.add("PII_LEAK: " + String.join(",", piiFound));
        }

        // 3. Schema validation — if this output drives a tool call or structured field
        if (output.structuredPayload() != null) {
            var schemaErrors = schemaValidator.validate(output.structuredPayload(), output.expectedSchema());
            violations.addAll(schemaErrors);
        }

        GuardrailResult result = violations.isEmpty()
                ? GuardrailResult.pass(output)
                : GuardrailResult.blocked(violations);

        auditLogger.record(tenantId, output, result);
        return result;
    }

    public record GuardrailResult(boolean passed, LlmOutput output, List<String> violations) {
        static GuardrailResult pass(LlmOutput output) {
            return new GuardrailResult(true, output, List.of());
        }
        static GuardrailResult blocked(List<String> violations) {
            return new GuardrailResult(false, null, violations);
        }
    }
}
```

```java
// ResilientLlmClient.java — retries, circuit breaker, fallback chain
package com.aesp.guardrails;

import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.function.Supplier;

@Service
public class ResilientLlmClient {

    private final LlmClient primaryModel;
    private final LlmClient fallbackModel;
    private final CircuitBreaker circuitBreaker;

    private static final int MAX_RETRIES = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(200);

    public ResilientLlmClient(LlmClient primaryModel, LlmClient fallbackModel, CircuitBreaker circuitBreaker) {
        this.primaryModel = primaryModel;
        this.fallbackModel = fallbackModel;
        this.circuitBreaker = circuitBreaker;
    }

    public LlmOutput complete(String prompt, String idempotencyKey) {
        if (circuitBreaker.isOpen("primary-llm")) {
            return callWithRetries(() -> fallbackModel.complete(prompt, idempotencyKey));
        }

        try {
            LlmOutput result = callWithRetries(() -> primaryModel.complete(prompt, idempotencyKey));
            circuitBreaker.recordSuccess("primary-llm");
            return result;
        } catch (LlmUnavailableException e) {
            circuitBreaker.recordFailure("primary-llm");
            return callWithRetries(() -> fallbackModel.complete(prompt, idempotencyKey));
        }
    }

    private LlmOutput callWithRetries(Supplier<LlmOutput> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (TransientLlmException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) throw e;
                sleepBackoff(attempt);
            }
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            long millis = BASE_BACKOFF.toMillis() * (1L << attempt); // exponential backoff
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 3.2 Node.js — Same Pipeline

```javascript
// guardrailPipeline.js
const { scoreGroundedness } = require('./groundednessChecker');
const { detectPii } = require('./piiDetector');
const { validateSchema } = require('./schemaValidator');
const { recordAudit } = require('./auditLogger');

const MIN_GROUNDEDNESS_SCORE = 0.7;

async function validate(output, context, tenantId) {
  const violations = [];

  const groundedness = await scoreGroundedness(output.text, context.chunks);
  if (groundedness < MIN_GROUNDEDNESS_SCORE) {
    violations.push(`LOW_GROUNDEDNESS: score=${groundedness}`);
  }

  const piiFound = detectPii(output.text, tenantId);
  if (piiFound.length > 0) {
    violations.push(`PII_LEAK: ${piiFound.join(',')}`);
  }

  if (output.structuredPayload) {
    const schemaErrors = validateSchema(output.structuredPayload, output.expectedSchema);
    violations.push(...schemaErrors);
  }

  const result = {
    passed: violations.length === 0,
    output: violations.length === 0 ? output : null,
    violations,
  };

  await recordAudit(tenantId, output, result);
  return result;
}

module.exports = { validate };
```

```javascript
// resilientLlmClient.js — retries, circuit breaker, fallback chain
const circuitBreaker = require('./circuitBreaker');

const MAX_RETRIES = 3;
const BASE_BACKOFF_MS = 200;

async function callWithRetries(callFn) {
  let attempt = 0;
  while (true) {
    try {
      return await callFn();
    } catch (err) {
      if (!err.isTransient) throw err;
      attempt++;
      if (attempt >= MAX_RETRIES) throw err;
      const backoff = BASE_BACKOFF_MS * 2 ** attempt; // exponential backoff
      await new Promise((resolve) => setTimeout(resolve, backoff));
    }
  }
}

async function complete(prompt, idempotencyKey, { primaryModel, fallbackModel }) {
  if (circuitBreaker.isOpen('primary-llm')) {
    return callWithRetries(() => fallbackModel.complete(prompt, idempotencyKey));
  }

  try {
    const result = await callWithRetries(() => primaryModel.complete(prompt, idempotencyKey));
    circuitBreaker.recordSuccess('primary-llm');
    return result;
  } catch (err) {
    if (!err.isUnavailable) throw err;
    circuitBreaker.recordFailure('primary-llm');
    return callWithRetries(() => fallbackModel.complete(prompt, idempotencyKey));
  }
}

module.exports = { complete };
```

---

## 4. AESP Context

Guardrails and reliability are what let AESP move from demo to production, because they answer the questions a security or reliability review will always ask:

- **What stops the RAG Agent (Day 12) from confidently answering with a fabricated policy?** The groundedness check — if the model's claim isn't traceable to a retrieved chunk, the output is blocked before it reaches the customer, and the system falls back to "let me connect you with a human agent" instead.
- **What stops a jailbreak attempt from turning off the rules mid-conversation?** Input guardrails scan for injection patterns before the message ever reaches the prompt — this has to run *before* Day 10's prompt engineering does its job, not after.
- **What stops the Resolver Agent's tool calls (Day 13) from executing on bad data?** Schema validation on every structured output, plus action gating on high-risk operations — refund amount limits, tenant-scoped permission checks — before any tool actually executes.
- **What happens when the LLM provider has an outage?** This is where reliability engineering meets guardrails: circuit breakers stop retry storms from making an outage worse, fallback chains keep the support flow alive on a backup model or cached response, and idempotency keys on tool calls mean a retried refund never double-executes.
- **What goes in the audit log, and why does it matter for a multi-tenant enterprise product?** Every guardrail decision — pass or block, and why — gets logged per-tenant. This is the artifact that turns "trust us" into "here's the record," which matters enormously for enterprise compliance reviews and incident postmortems.

---

## 5. Interview Q&A

**Q1: What's the difference between input guardrails and output guardrails, and why do you need both?**
A: Input guardrails protect the model from the user — catching prompt injection, jailbreak attempts, and out-of-scope requests before they ever reach the prompt. Output guardrails protect the user (and the business) from the model — catching hallucinations, PII leaks, and policy violations before a response goes out or a tool executes. A system with only one is still exposed on the other side: an output-only guardrail won't stop a cleverly worded injection from getting the model to *want* to leak data, and an input-only guardrail won't catch a model that hallucinates on its own without any adversarial prompting.

**Q2: How would you detect that an LLM's answer is hallucinated rather than grounded in retrieved context?**
A: Options range in cost and rigor: requiring the model to cite the specific retrieved chunk backing each claim and rejecting uncited claims; running a smaller NLI (natural language inference) model to check whether each output sentence is entailed by the retrieved context; or using a second LLM call as a judge to score groundedness directly. The right choice depends on latency budget — citation requirements are cheap and can run inline, while LLM-as-judge adds a full extra model call.

**Q3: Why do you need idempotency keys on tool calls specifically in a system with retry logic?**
A: Retry logic assumes it's safe to repeat a failed call, which is true for reads but dangerous for writes — if a `issue_refund` call times out on the client side but actually succeeded server-side, a retry without an idempotency key would issue a second refund. The idempotency key lets the receiving system recognize "I've already processed this exact request" and return the original result instead of re-executing the side effect.

**Q4: Walk through what a circuit breaker does differently than a simple retry loop, and why you'd want both.**
A: A retry loop assumes failures are transient and worth immediately retrying — fine for a single blip, harmful during a sustained outage, where it just adds retry-storm load to an already-struggling provider. A circuit breaker tracks failure rate over a window and, once it crosses a threshold, "opens" and stops sending traffic to that provider entirely for a cooldown period, routing to a fallback instead. Retries handle brief transient errors; the circuit breaker handles sustained outages and protects both your system and the failing dependency from pile-on load.

**Q5: A support agent's response looks fluent and confident but is actually wrong. Why is this a harder problem than the model just saying "I don't know"?**
A: A fluent, confident, wrong answer is more dangerous than an honest "I don't know" because the user has no signal to distrust it, and it directly damages trust once discovered — RAG's grounding gate (Day 12–13 territory) is one defense, but the failure mode here is exactly why groundedness/entailment checking exists as its own layer: language models are trained to produce fluent text, not to signal their own uncertainty, so confidence in tone is not evidence of correctness and has to be checked independently.

**Q6: How would you decide what counts as a "high-risk action" that needs human-in-the-loop approval versus one the agent can execute autonomously?**
A: Risk is generally a function of reversibility, financial/data impact, and blast radius — a password reset lookup is low-risk and reversible, while a large refund or account deletion is high-impact and hard to reverse. In practice this is implemented as configurable per-tenant thresholds (e.g., refunds under $X auto-approve, above $X require human sign-off) rather than a single global rule, since risk tolerance genuinely varies by customer and by action type.

---

*Next: Day 15 — LLM Ops: Cost + Latency (Semantic Cache)*
