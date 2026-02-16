# LLM Integration

## Motivation

The quality of OPAA's answers depends not just on finding the right documents, but on how those documents are used to generate responses. Different organizations have different requirements:

- Some want to use OpenAI's latest models for maximum capability
- Some need open-source models for privacy and cost
- Some require specific model versions for compliance
- Some want to switch providers without rewriting code

This feature ensures OPAA is model-agnostic and fully configurable at deployment time.

---

## Overview

OPAA's LLM integration provides:

1. **Model Flexibility** — Support for multiple LLM providers and models
2. **Configuration at Deployment** — Choose models via environment variables, no code changes
3. **Provider Abstraction** — Swap providers without application changes
4. **Advanced Capabilities** — Streaming responses, function calling, embeddings
5. **Cost & Performance Optimization** — Use different models for different tasks

---

## Supported LLM Providers

### OpenAI-Compatible APIs

Any provider implementing the OpenAI API standard is supported:

**Primary Providers:**
- **OpenAI** (GPT-4, GPT-3.5-turbo)
- **Azure OpenAI** (managed OpenAI in Azure)
- **Anthropic Claude** (via Claude API)
- **Open-source via OpenAI-compatible servers:**
  - Ollama (local)
  - LM Studio (local)
  - Text Generation WebUI (local)
  - vLLM (self-hosted)
  - LocalAI (local)

**Why OpenAI-Compatible?**
- De-facto standard for LLM APIs
- Same interface across many providers
- Minimal abstraction layer
- Easy for developers to understand

### Configuration Pattern

All LLM providers configured identically:

```
LLM_PROVIDER: "openai"           # or "anthropic", "azure", "custom"
LLM_API_KEY: "${OPENAI_API_KEY}"
LLM_API_BASE: "https://api.openai.com/v1"  # can be any OpenAI-compatible endpoint
LLM_MODEL: "gpt-4"
```

### Model Selection Criteria

Organizations should choose based on:

| Factor | Consideration |
|--------|---------------|
| **Capability** | GPT-4 > GPT-3.5 > open-source models; choose based on answer quality needs |
| **Cost** | Open-source/Llama cheaper; GPT-4 more expensive; embedding models cheapest |
| **Privacy** | Local models best; on-premises vLLM good; cloud providers if data sharing OK |
| **Speed** | GPT-3.5 < 2s; Ollama varies by hardware; must meet SLA |
| **Compliance** | Some industries require specific models or on-premises only |

---

## Response Generation

### Answer Generation Pipeline

When user asks a question:

1. **Context Preparation:** Retrieved documents formatted with metadata
2. **Prompt Construction:** User question + documents + system instructions
3. **Model Invocation:** Call configured LLM
4. **Streaming:** Stream response back to user (don't wait for complete generation)
5. **Post-Processing:** Extract sources, format answer

### Prompt Structure

System sends to LLM:

```
System Prompt:
  "You are a helpful assistant for answering questions about our organization.
   Use the provided documents to answer. Always cite sources.
   If information is not in the documents, say so."

Context (Retrieved Documents):
  Document 1 (title, excerpt)
  Document 2 (title, excerpt)
  ...

User Question:
  "What's our policy on X?"

Task Instructions:
  "Answer using only the provided documents.
   Format answer as: [Direct Answer] Sources: [List sources]"
```

### Answer Format

LLM generates responses following the prompt:

```
Answer: "Based on our policy documents, X is allowed with the
following conditions:
1. Condition A
2. Condition B
3. Condition C

Additional context from recent updates..."

Sources:
- Company Policy on X (updated Jan 2024)
- Manager Handbook section 3.2
```

OPAA then:
- Parses the answer
- Links sources to actual documents
- Adds clickable document links
- Displays to user

---

## Model Configuration

### Temperature & Parameters

Each model use case can have custom settings:

```
GenerationSettings:
  model: "gpt-4"
  temperature: 0.5
  top_p: 0.9
  max_tokens: 1024
  frequency_penalty: 0.0
```

**Parameter Guidance:**
- **temperature:**
  - Low (0.1-0.3): More factual, less creative (good for QA)
  - High (0.7-0.9): More creative, less focused (good for brainstorming)
  - Recommended for OPAA: 0.3-0.5 (balance creativity and accuracy)

- **max_tokens:**
  - Limits response length
  - Recommended: 1024-2048 for detailed answers
  - Use shorter (512) for chat platforms

- **top_p:**
  - Controls diversity (0-1)
  - 0.9 is good default
  - Lower for more conservative responses

### Multi-Model Strategy

OPAA supports using different models for different tasks:

```
Model Selection:
  QA Generation: "gpt-4"           # Best quality
  Embeddings: "text-embedding-3-small"  # Cheap, fast
  Summarization: "gpt-3.5-turbo"   # Fast, good enough
  Classification: "gpt-3.5-turbo"  # Cost-effective
```

Benefits:
- Use expensive models only where needed
- Optimize cost vs. quality per task
- Faster responses where speed matters more

### Fallback Strategy

If primary model unavailable:

```
Primary: "gpt-4"
Fallback: "gpt-3.5-turbo"  # Slightly lower quality, always available
Fallback: "mistral-7b" (self-hosted)  # Last resort
```

If all fail, system:
- Returns retrieved documents without generation
- Shows user: "I found relevant documents but couldn't generate summary. See sources below."
- Logs failure for admin review

---

## Embedding Models

### Embedding Configuration

Separate from generation model:

```
Embedding Settings:
  model: "text-embedding-3-small"  # OpenAI
  dimension: 1536
  batch_size: 100
```

### Embedding Model Choices

Different organizations choose based on:
- **OpenAI Embeddings:** Best quality, cloud-based
- **Open-source:** All-MiniLM, ONNX models, local alternatives
- **Specialized:** Domain-specific embeddings for technical docs

**Important:** Embedding model choice affects search quality. Changing embedding model requires re-indexing all documents.

### Cross-Model Search

Advanced: Use different embedding and generation models:
- Embedding from Jina.ai (technical)
- Generation from Claude (quality)
- Better results for specialized documents

---

## Advanced LLM Features

### Streaming Responses

OPAA streams responses as they generate:
- User sees answer appearing character-by-character
- Better UX (feels faster, interactive)
- Can stop generation if answer goes off-track

### Function Calling

If LLM supports function calling (GPT-4, Claude):

```
LLM can call functions:
  get_document(id)     → retrieve full document
  search_more(query)   → do another search
  format_table(data)   → format data as table
```

OPAA can use this to:
- Automatically fetch full documents if needed
- Do multi-step reasoning
- Format complex answers

### Vision/Multimodal Support

If organization has visual documents:
- GPT-4-vision can analyze images/PDFs
- Can extract text from scanned documents
- Can answer questions about diagrams

---

## Cost Optimization

### Cost Drivers

OPAA costs depend on:
- **Embedding Model:** Cheapest (fractions of cent per 1000 tokens)
- **Generation Model:** Most expensive (dollars per 1000 tokens)
- **Query Volume:** More questions = higher cost

### Cost Reduction Strategies

1. **Use Cheaper Models Where Possible:**
   - Use GPT-3.5-turbo instead of GPT-4 (5x cheaper)
   - Use embeddings-small instead of large
   - Use local models (free after infrastructure cost)

2. **Implement Caching:**
   - Cache frequent questions
   - Cache document embeddings
   - Don't re-embed unchanged documents

3. **Hybrid Approach:**
   - Use local models for 80% of questions
   - Use GPT-4 only for complex queries
   - Automatic routing based on question complexity

4. **Batching:**
   - Batch embedding generation during off-hours
   - Batch question answering for report generation

**Typical Costs:**
- Small organization (100 queries/day): $50-200/month
- Large organization (10,000 queries/day): $5,000-20,000/month
- With local models: Infrastructure cost + electricity

---

## Safety & Responsible Use

### Jailbreak Prevention

The system is designed to prevent LLM jailbreaks:
- Strict system prompts limit model behavior
- Retrieved documents constrain answers to organizational knowledge
- User cannot directly manipulate model instructions
- System instructions locked (not changeable via chat)

### Hallucination Mitigation

OPAA inherently reduces hallucinations:
- All answers grounded in retrieved documents
- Model cannot invent facts not in sources
- Confidence scores shown (0 confidence = no sources)
- Users can verify claims in source documents

### Content Filtering

If organization requires:
- Profanity filtering
- PII redaction
- Sensitive information masking

These can be added as post-processing steps.

---

## Rate Limiting & Quotas

### Rate Limits

Configurable per user/workspace:

```
RateLimits:
  per_user: 100 queries/day
  per_team: 1000 queries/day
  global: 10000 queries/day
```

### Token Quotas

Can also quota by tokens (more granular):

```
TokenQuotas:
  per_user: 50,000 tokens/day
  per_team: 500,000 tokens/day
```

When exceeded:
- User sees: "Daily quota exceeded. Try again tomorrow."
- Admin notified
- Query still logged for audit

---

## Monitoring & Observability

### What Gets Logged

For each query, system logs:
- User ID
- Workspace
- Question (optional, can be disabled for privacy)
- Retrieved documents count
- Model used
- Generation tokens used
- Response time
- User feedback (if any)

### Metrics Dashboards

Admins can see:
- Top questions asked
- Model performance (answer quality)
- Cost breakdown by user/workspace
- API errors and failures
- Model latency distribution

### Cost Tracking

Detailed cost breakdown:
- Cost per query
- Cost per user
- Cost per model
- Trends over time

---

## Switching LLM Providers

### How to Switch

1. **Change Configuration:**
   ```
   OLD: LLM_API_KEY=sk-openai-xxx
   NEW: LLM_API_KEY=sk-claude-xxx
        LLM_MODEL="claude-3-sonnet"
   ```

2. **Restart Service** (or hot-reload)

3. **Test:** Ask a question, verify response quality

**That's it.** No code changes, no data migration, no re-indexing.

### Considerations

- Different models may have different output quality
- Different models have different speed/cost
- May want to test new model on subset of users first
- Embedding model can be changed independently (requires re-indexing)

---

## Integration Points

- **Data Indexing:** Uses embedding model to create document embeddings
- **User Frontends:** Receives generated answers, streams to users
- **Access Control:** Respects document permissions before answering
- **Deployment Infrastructure:** Manages API credentials, rate limiting

---

## Open Questions / Future Enhancements

- Should OPAA support finetuned models specific to organization?
- Should we implement automatic model selection based on question complexity?
- Should we support prompt engineering best practices (chain-of-thought, etc.)?
- Should we offer A/B testing (show different users different models)?
- Should cost optimization be automatic (pick cheapest model that works)?
- Should we support local model serving (CUDA, Apple Metal) natively?

---

## Success Metrics

- **Answer Quality:** % of answers rated helpful by users
- **Cost Efficiency:** Cost per query, cost per successful interaction
- **Latency:** P95 response time to user
- **Model Performance:** Error rates, hallucination rates
- **API Uptime:** % of successful API calls to LLM provider
- **User Adoption:** Growth in number of queries over time
