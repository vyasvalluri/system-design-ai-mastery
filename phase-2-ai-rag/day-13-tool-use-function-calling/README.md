# Day 13 — Tool Use + Function Calling

**Phase 2: AI Integration + RAG | AESP Component: Agent Tool Registry**

---

## 1. The Analogy — Giving the Support Rep a Phone, Not Just a Textbook

Days 9–12 gave our new hire knowledge: how to talk (LLM fundamentals), how to follow instructions (prompt engineering), and how to look things up in the company manual (RAG). But a support rep who can only *talk* is still stuck when a customer says "please refund my last order" — reading about refunds isn't the same as being able to actually issue one.

Tool use is what happens when you also hand that rep a phone with speed-dials: one button hits the billing system's refund API, another checks order status, another looks up account details. The rep still decides *when* to pick up the phone and *which* button to press — but now they can actually act in the world, not just describe what should happen.

Function calling is the mechanism that lets an LLM say "I need to press the refund button, with these exact arguments" in a structured way your code can safely execute — instead of the model trying to *pretend* it did something by generating plausible-sounding text.

---

## 2. The Concept

### The core loop

Tool use isn't the model directly calling your code — it's a structured negotiation:

1. You give the model a list of tool **schemas** (name, description, parameters as JSON Schema).
2. The model, mid-response, decides a tool is needed and emits a **tool_use** block: tool name + arguments, as structured JSON — not prose.
3. Your application code intercepts that, validates the arguments, and actually executes the real function (calls the API, queries the DB).
4. You send the **tool result** back to the model as a new message.
5. The model incorporates that result into its next response — either calling another tool, or producing a final answer.

This is the **ReAct-style loop** (Reason → Act → Observe) in miniature — a preview of Day 17's full agent architectures, but constrained to a single tool round-trip here.

### Why structured schemas matter

The model never executes anything itself. It only ever produces *intent* as structured data. This is the entire safety boundary: your code is always the one deciding whether to actually run the refund, and can add validation, permission checks, or human confirmation before doing so.

```json
{
  "name": "issue_refund",
  "description": "Issues a refund for a given order. Use only after confirming the order is eligible.",
  "input_schema": {
    "type": "object",
    "properties": {
      "order_id": { "type": "string" },
      "amount_cents": { "type": "integer", "minimum": 1 },
      "reason": { "type": "string", "enum": ["damaged", "wrong_item", "customer_request", "other"] }
    },
    "required": ["order_id", "amount_cents", "reason"]
  }
}
```

Good schema design directly affects reliability: vague parameter names or missing `enum` constraints lead the model to guess or hallucinate values.

### Parallel vs. sequential tool calls

Modern models can request multiple independent tool calls in a single turn (e.g., "check order status" AND "check refund eligibility" simultaneously) when the calls don't depend on each other's results — cutting round-trip latency. Sequential calls are required when one tool's output feeds the next tool's input (e.g., look up order → then decide whether to refund it).

### The trust boundary — never skip validation

A tool call is still just an LLM's structured guess. Production systems must:

- **Validate arguments** against the schema and business rules before execution (e.g., `amount_cents` can't exceed the original order total).
- **Gate consequential actions** behind confirmation or human-in-the-loop for anything irreversible or high-value (large refunds, account deletion).
- **Never let the model's tool_use block itself be the audit record** — log the actual executed action and its real result, since the model's stated intent and the system's actual effect must be traceable separately.

### Common failure modes

| Failure | Cause | Mitigation |
|---|---|---|
| Hallucinated tool call | Model invents a tool name/args not in the schema | Strict schema validation; reject and re-prompt |
| Wrong argument values | Ambiguous parameter description | Tighter descriptions, `enum` constraints, examples in the tool description |
| Infinite tool loop | Model keeps calling tools without converging | Max iteration cap on the agent loop |
| Over-eager tool use | Model calls a tool when it should just answer from context | Explicit instruction: "only call tools when necessary" |

---

## 3. Code

### Java (Spring Boot) — tool registry, dispatch, and the execution loop

```java
// ToolRegistry.java
@Component
public class ToolRegistry {

    private final Map<String, ToolHandler> handlers = new HashMap<>();

    public ToolRegistry(OrderService orderService, RefundService refundService) {
        handlers.put("get_order_status", args ->
                orderService.getStatus((String) args.get("order_id")));

        handlers.put("issue_refund", args -> {
            String orderId = (String) args.get("order_id");
            int amountCents = (Integer) args.get("amount_cents");
            String reason = (String) args.get("reason");

            // Business-rule validation — never trust the model's numbers blindly
            Order order = orderService.getOrder(orderId);
            if (amountCents > order.totalCents()) {
                throw new ToolExecutionException("Refund amount exceeds order total");
            }
            if (amountCents > 10_000) { // > $100 requires human confirmation
                return ToolResult.requiresConfirmation(
                        "Refund of $%.2f exceeds auto-approval limit".formatted(amountCents / 100.0));
            }
            return refundService.issueRefund(orderId, amountCents, reason);
        });
    }

    public ToolResult execute(String toolName, Map<String, Object> args) {
        ToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            throw new ToolExecutionException("Unknown tool: " + toolName);
        }
        return handler.execute(args); // logged separately as the real audit record
    }

    public interface ToolHandler {
        ToolResult execute(Map<String, Object> args);
    }
}
```

```java
// AgentLoopService.java
@Service
public class AgentLoopService {

    private static final int MAX_ITERATIONS = 5;
    private final AnthropicClient claudeClient;
    private final ToolRegistry toolRegistry;

    public AgentLoopService(AnthropicClient claudeClient, ToolRegistry toolRegistry) {
        this.claudeClient = claudeClient;
        this.toolRegistry = toolRegistry;
    }

    public String resolveTicket(String systemPrompt, List<Message> conversation) {
        List<Message> messages = new ArrayList<>(conversation);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            ClaudeResponse response = claudeClient.createMessage(systemPrompt, messages, toolRegistry.getSchemas());

            if (response.stopReason() == StopReason.END_TURN) {
                return response.textContent(); // model produced a final answer, no tool needed
            }

            if (response.stopReason() == StopReason.TOOL_USE) {
                messages.add(Message.assistant(response.contentBlocks()));

                List<ToolResultBlock> results = new ArrayList<>();
                for (ToolUseBlock toolUse : response.toolUseBlocks()) {
                    try {
                        ToolResult result = toolRegistry.execute(toolUse.name(), toolUse.input());
                        results.add(ToolResultBlock.success(toolUse.id(), result));
                    } catch (ToolExecutionException e) {
                        results.add(ToolResultBlock.error(toolUse.id(), e.getMessage()));
                    }
                }
                messages.add(Message.userToolResults(results));
            }
        }
        return "Escalating to a human agent — unable to resolve within tool-call budget.";
    }
}
```

### Node.js — defining tool schemas and running the round-trip with the Anthropic SDK

```javascript
// toolAgent.js
const Anthropic = require('@anthropic-ai/sdk');
const client = new Anthropic();

const tools = [
  {
    name: 'get_order_status',
    description: 'Fetch the current status and details of an order by ID.',
    input_schema: {
      type: 'object',
      properties: { order_id: { type: 'string' } },
      required: ['order_id'],
    },
  },
  {
    name: 'issue_refund',
    description: 'Issue a refund for an order. Only call after confirming eligibility with get_order_status.',
    input_schema: {
      type: 'object',
      properties: {
        order_id: { type: 'string' },
        amount_cents: { type: 'integer', minimum: 1 },
        reason: { type: 'string', enum: ['damaged', 'wrong_item', 'customer_request', 'other'] },
      },
      required: ['order_id', 'amount_cents', 'reason'],
    },
  },
];

const toolHandlers = {
  get_order_status: async ({ order_id }) => orderService.getStatus(order_id),
  issue_refund: async ({ order_id, amount_cents, reason }) => {
    const order = await orderService.getOrder(order_id);
    if (amount_cents > order.totalCents) {
      throw new Error('Refund amount exceeds order total');
    }
    return refundService.issueRefund(order_id, amount_cents, reason);
  },
};

async function runAgentLoop(systemPrompt, userMessage, maxIterations = 5) {
  const messages = [{ role: 'user', content: userMessage }];

  for (let i = 0; i < maxIterations; i++) {
    const response = await client.messages.create({
      model: 'claude-sonnet-4-6',
      max_tokens: 1024,
      system: systemPrompt,
      tools,
      messages,
    });

    if (response.stop_reason !== 'tool_use') {
      return response.content.find(b => b.type === 'text')?.text ?? '';
    }

    messages.push({ role: 'assistant', content: response.content });

    const toolResults = [];
    for (const block of response.content.filter(b => b.type === 'tool_use')) {
      try {
        const result = await toolHandlers[block.name](block.input);
        toolResults.push({ type: 'tool_result', tool_use_id: block.id, content: JSON.stringify(result) });
      } catch (err) {
        toolResults.push({ type: 'tool_result', tool_use_id: block.id, content: err.message, is_error: true });
      }
    }
    messages.push({ role: 'user', content: toolResults });
  }

  return 'Escalating to a human agent — unable to resolve within tool-call budget.';
}

module.exports = { runAgentLoop };
```

---

## 4. AESP Context

Tool use is what turns AESP's Resolver Agent from a chatbot into something that can actually close a ticket:

- **The Agent Tool Registry** is a central, versioned catalog of every tool an agent is allowed to call (`get_order_status`, `issue_refund`, `update_account_email`, etc.), each with a JSON Schema and an explicit permission tier — mirroring the same "prompts as versioned artifacts" discipline from Day 10.
- **Consequential actions are gated by dollar/impact thresholds**, not blanket human-in-the-loop for everything — small refunds auto-execute, large ones return a `requires_confirmation` result that routes to the Escalation Agent (Day 20) rather than executing blindly, balancing automation with risk.
- **Every executed tool call is logged as the ground truth**, separately from the model's stated reasoning — so if a customer disputes an action, AESP can show exactly what was executed and with what arguments, not just what the model "said" it did.
- **The max-iteration cap on the agent loop** is a direct defense against the classic failure mode of a model looping on tool calls without converging — AESP caps at 5 iterations before automatically escalating to a human, treating that as a safety valve rather than a bug to silently retry forever.
- **Parallel tool calls** are used for independent read-only lookups (e.g., checking order status and account tier at the same time) to cut latency, while write actions like `issue_refund` are always sequenced after their prerequisite reads complete and are validated.

---

## 5. Interview Q&A

**Q1: Walk through what actually happens, end-to-end, when an LLM "calls a tool."**
A: The model never executes anything directly — it emits a structured `tool_use` block naming the tool and its arguments as JSON matching the provided schema. Your application code intercepts that, validates it, executes the real function, and sends the result back to the model as a new message so it can continue reasoning or produce a final answer. The model only ever produces intent; your code is the trust boundary that decides whether to act on it.

**Q2: Why is JSON Schema for tool parameters important, beyond just documentation?**
A: It directly constrains what the model can produce — tight `enum` values and clear descriptions reduce hallucinated or malformed arguments, and it gives your code a contract to validate against before execution. Vague schemas are one of the most common causes of unreliable tool use in production.

**Q3: How would you prevent an agent from taking a high-risk action (like a large refund) fully autonomously?**
A: Add a business-rule check in the tool execution layer itself — not just in the prompt — that compares the requested action against a risk threshold (amount, account tier, action type) and returns a "requires confirmation" result instead of executing, routing to a human-in-the-loop step. Never rely solely on prompt instructions telling the model to "be careful," since that's not an enforceable boundary.

**Q4: What happens if a model keeps calling tools without ever reaching a final answer, and how do you defend against it?**
A: This is an infinite or near-infinite tool loop — the agent keeps reasoning-acting-observing without converging. Production systems cap the number of loop iterations and, on hitting the cap, fail gracefully (e.g., escalate to a human) rather than looping indefinitely and burning cost/latency.

**Q5: When would you use parallel tool calls versus sequential ones?**
A: Parallel when the calls are independent — their inputs don't depend on each other's results — like checking order status and account tier simultaneously, which reduces round-trip latency. Sequential is required when one tool's output is needed as input to decide the next call, such as looking up an order before deciding whether it's eligible for a refund.

---
