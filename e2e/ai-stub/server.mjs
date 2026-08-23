// Minimal OpenAI/Ollama-compatible stub for the E2E stack's chat/embedding calls.
//
// The E2E suite must never reach a real model (cost, non-determinism, network dependency in CI -
// see docs/decisions/0009-e2e-teststrategie.md, point 4, which deliberately left "local model
// provisioning" to a follow-up issue: #256). This stub closes that gap for scenarios that need a
// working retrieval pipeline (e.g. #424's upload/share/search flow) without depending on that
// follow-up: it speaks just enough of the OpenAI wire protocol for Spring AI's OpenAiApi client
// (POST /v1/embeddings, POST /v1/chat/completions) to work end-to-end against a fully offline,
// deterministic backend.
//
// #773 reverted the backend's embedding function to Ollama's native API - this stub therefore also
// answers POST /api/embed (Ollama's own wire shape, {model, input: [...]} in, {model, embeddings:
// [[...], ...]} out) with the same fixed vector logic as the OpenAI-compatible /v1/embeddings
// handler below. Chat is unaffected (still POST /v1/chat/completions, spring.ai.model.chat stays
// "openai").
//
// Embeddings: every input gets the exact same fixed-dimension vector, mirroring
// backend/src/test/java/io/opaa/FakeEmbeddingModel.java (used for the same purpose in backend
// integration tests). This is deliberate, not a shortcut: with every chunk and every query
// embedding identical, cosine similarity is always 1.0, so retrieval outcomes in the E2E suite
// hinge entirely on the permission filter applied to the vector store query (see
// io.opaa.query.QueryService#libraryFilter) - never on whether the embedding model judged content
// "relevant". That is exactly the property #424's scenarios need to prove the ACL filter, not
// answer quality (which is Epic #224's job, out of scope here).
//
// Chat completions: the request's messages contain the RAG context assembled by
// io.opaa.query.AnswerGenerationService, with one inline citation marker
// (`【source: <document_id>#<chunk_index> | <file_name>】`, see io.opaa.query.CitationParser) per
// candidate chunk. The stub extracts every such marker from the incoming prompt (excluding the
// one fixed worked example baked into the system prompt itself, see
// EXAMPLE_CITATION_DOCUMENT_ID below - it is present even with zero real chunks) and echoes the
// rest back in the assistant's answer, so every retrieved chunk is deterministically "cited" -
// again handing control back to the permission filter (which chunks reach the prompt at all)
// rather than to any model judgement.
import { createServer } from 'node:http'

const PORT = process.env.PORT ? Number(process.env.PORT) : 8089
const DIMENSIONS = process.env.EMBEDDING_DIMENSIONS
  ? Number(process.env.EMBEDDING_DIMENSIONS)
  : 1536

const CITATION_PATTERN = /【source:\s*([a-zA-Z0-9-]+)#(\d+)\s*\|\s*(.+?)】/g

// io.opaa.query.AnswerGenerationService's SYSTEM_PROMPT includes one fixed, hardcoded citation as
// a worked example of the required format ("Example: 【source: 3fa85f64-... | readme.md】") -
// present verbatim in every prompt regardless of whether any real context chunk was retrieved.
// Without excluding it by its fixed document id, a query with zero readable chunks (the exact
// case scenarios 4 and 5 in test(e2e) #424 rely on) would still look "cited" from this example
// alone, silently defeating the permission filter as far as this stub is concerned.
const EXAMPLE_CITATION_DOCUMENT_ID = '3fa85f64-5717-4562-b3fc-2c963f66afa6'

const NO_CONTEXT_ANSWER = 'Dazu liegen mir keine Informationen in den zugänglichen Dokumenten vor.'

// test(e2e) #760, PR review finding 2: every managed chat model in this suite points at this same
// stub, so a scenario that activates a *different* model and then asks a chat question cannot
// otherwise tell whether the answer actually came from the newly activated model or from a stale,
// cached client for the previously active one (io.opaa.llm.ActiveChatModelResolver's whole point,
// #758) - both would produce an identical-looking answer. Remembering the last request's own
// "model" field (the modelIdentifier OpenAiApi sends verbatim) and exposing it here lets that
// scenario assert on it directly instead of only on the answer's mere presence.
let lastChatModel = null

function fakeEmbedding() {
  const vector = new Array(DIMENSIONS)
  for (let i = 0; i < DIMENSIONS; i++) {
    vector[i] = Math.sin(i * 0.01)
  }
  return vector
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    req.on('data', (chunk) => chunks.push(chunk))
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(payload),
  })
  res.end(payload)
}

// Spring AI's default OpenAiApi paths are version-prefixed ("/v1/embeddings",
// "/v1/chat/completions") relative to a base-url without "/v1"; a couple of existing backend
// config tests configure example base-urls that already include a trailing "/v1" (see
// OpenAiBaseUrlGuardTest, MixedProviderConfigurationTest), which would double it up. Neither
// convention has ever actually been exercised against a reachable server before this stub, so
// matching on the path suffix - not the full path - works no matter which one e2e.env ends up
// using.
function matchesEndpoint(url, name) {
  return url != null && url.split('?')[0].endsWith(name)
}

const server = createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && matchesEndpoint(req.url, '/health')) {
      sendJson(res, 200, { status: 'ok' })
      return
    }

    if (req.method === 'GET' && matchesEndpoint(req.url, '/last-chat-model')) {
      sendJson(res, 200, { model: lastChatModel })
      return
    }

    if (req.method === 'POST' && matchesEndpoint(req.url, '/embeddings')) {
      const body = JSON.parse(await readBody(req))
      const inputs = Array.isArray(body.input) ? body.input : [body.input]
      const data = inputs.map((_, index) => ({
        object: 'embedding',
        index,
        embedding: fakeEmbedding(),
      }))
      sendJson(res, 200, {
        object: 'list',
        data,
        model: body.model ?? 'stub-embedding',
        usage: { prompt_tokens: 0, total_tokens: 0 },
      })
      return
    }

    // #773: Ollama's native embedding endpoint - see this file's own header comment.
    if (req.method === 'POST' && matchesEndpoint(req.url, '/api/embed')) {
      const body = JSON.parse(await readBody(req))
      const inputs = Array.isArray(body.input) ? body.input : [body.input]
      sendJson(res, 200, {
        model: body.model ?? 'stub-embedding',
        embeddings: inputs.map(() => fakeEmbedding()),
      })
      return
    }

    if (req.method === 'POST' && matchesEndpoint(req.url, '/chat/completions')) {
      const body = JSON.parse(await readBody(req))
      lastChatModel = typeof body.model === 'string' ? body.model : null
      const messages = Array.isArray(body.messages) ? body.messages : []
      const combinedText = messages
        .map((message) => (typeof message.content === 'string' ? message.content : ''))
        .join('\n')
      const citations = [...combinedText.matchAll(CITATION_PATTERN)]
        .filter((match) => match[1] !== EXAMPLE_CITATION_DOCUMENT_ID)
        .map((match) => match[0])
      const uniqueCitations = [...new Set(citations)]
      const content =
        uniqueCitations.length > 0
          ? `Antwort auf Basis der bereitgestellten Dokumente. ${uniqueCitations.join(' ')}`
          : NO_CONTEXT_ANSWER

      sendJson(res, 200, {
        id: 'chatcmpl-stub',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: body.model ?? 'stub-chat',
        choices: [
          {
            index: 0,
            message: { role: 'assistant', content },
            finish_reason: 'stop',
            logprobs: null,
          },
        ],
        usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
      })
      return
    }

    sendJson(res, 404, { error: 'not found' })
  } catch (error) {
    sendJson(res, 500, { error: error instanceof Error ? error.message : 'internal error' })
  }
})

server.listen(PORT, () => {
  console.log(`AI stub listening on :${PORT}`)
})
