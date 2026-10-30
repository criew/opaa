// Minimal OpenAI-compatible stub for the E2E stack's chat/embedding calls, and a rerank endpoint.
//
// The E2E suite must never reach a real model (cost, non-determinism, network dependency in CI -
// see docs/decisions/0009-e2e-teststrategie.md, point 4, which deliberately left "local model
// provisioning" to a follow-up issue: #256). This stub closes that gap for scenarios that need a
// working retrieval pipeline (e.g. #424's upload/share/search flow) without depending on that
// follow-up: it speaks just enough of the OpenAI wire protocol for Spring AI's OpenAiApi client
// (POST /v1/embeddings, POST /v1/chat/completions) to work end-to-end against a fully offline,
// deterministic backend.
//
// Embeddings: every input gets the exact same fixed-dimension vector, mirroring
// backend/src/test/java/io/opaa/FakeEmbeddingModel.java (used for the same purpose in backend
// integration tests). This is deliberate, not a shortcut: with every chunk and every query
// embedding identical, cosine similarity is always 1.0, so retrieval outcomes in the E2E suite
// hinge entirely on the permission filter applied to the vector store query (see
// io.opaa.retrieval.scope.SearchScopeStage#libraryFilter) - never on whether the embedding model judged content
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
//
// Transcription model (installation target): GET <base>/transcription/models lists one speech
// model, which is all the transcription role's probe and the set-up assistant's discovery ask for.
// Only below /transcription - the chat and embedding steps rely on this stub listing no models.
//
// Gesprächsnotiz condensation (test(e2e) #1489): the same endpoint also serves
// io.opaa.chat.ChatNoteExtractionService, whose prompt is recognised by NOTE_PROMPT_MARKER below
// and answered with a fixed line per user message - see condensedNote().
import { createServer } from 'node:http'

const PORT = process.env.PORT ? Number(process.env.PORT) : 8089
const TRANSCRIPTION_MODEL = 'ai-stub-whisper'
const DIMENSIONS = process.env.EMBEDDING_DIMENSIONS
  ? Number(process.env.EMBEDDING_DIMENSIONS)
  : 1536

// --- Holding embeddings (two-instance target, e2e/multi-instance/) -----------------------------
//
// POST /control/embedding-hold {"marker": "...", "hold": true} holds back every embedding request
// whose input contains the marker until the same call with "hold": false, at most HOLD_LIMIT_MS.
// A scenario uses it to keep an indexing run or an upload of one instance in flight while it
// restarts the other one; requests without the marker (a start-up probe, other scenarios) pass.
const HOLD_LIMIT_MS = 150_000
let holdMarker = null
let releaseHeld = []

function holdsInput(inputs) {
  return (
    holdMarker !== null &&
    inputs.some((input) => typeof input === 'string' && input.includes(holdMarker))
  )
}

function waitForRelease() {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, HOLD_LIMIT_MS)
    releaseHeld.push(() => {
      clearTimeout(timer)
      resolve()
    })
  })
}

let heldCount = 0

const CITATION_PATTERN = /【source:\s*([a-zA-Z0-9-]+)#(\d+)\s*\|\s*(.+?)】/g

// io.opaa.query.AnswerGenerationService's SYSTEM_PROMPT includes one fixed, hardcoded citation as
// a worked example of the required format ("Beispiel: 【source: 3fa85f64-... | readme.md】") -
// present verbatim in every prompt regardless of whether any real context chunk was retrieved.
// Without excluding it by its fixed document id, a query with zero readable chunks (the exact
// case scenarios 4 and 5 in test(e2e) #424 rely on) would still look "cited" from this example
// alone, silently defeating the permission filter as far as this stub is concerned.
const EXAMPLE_CITATION_DOCUMENT_ID = '3fa85f64-5717-4562-b3fc-2c963f66afa6'

const NO_CONTEXT_ANSWER = 'Dazu liegen mir keine Informationen in den zugänglichen Dokumenten vor.'

// --- Gesprächsnotiz condensation (test(e2e) #1489) ---------------------------------------------
//
// io.opaa.chat.ChatNoteExtractionService sends one further prompt per finished turn, asking for
// the durable Angaben of the user message. Without a rule of its own the stub would answer it with
// its one fixed no-context sentence above, which ChatNoteExtraction.parse() happily turns into a
// note point - every turn of every chat in this suite would then carry one, and "kein Punkt, keine
// Schaltfläche" could not be tested at all. The rules below make the condensation a pure function
// of the user message instead: a message carrying one of the phrases produces exactly that point,
// every other message produces the sentinel.
//
// Coupled to two fixed strings of the production prompt (ChatNoteExtraction.PROMPT_TEMPLATE). A
// change to either fails loudly rather than silently: unrecognised, the prompt falls through to
// the citation echo above, whose answer becomes a note point on every single turn.
const NOTE_PROMPT_MARKER = 'Aus der folgenden Nachricht einer Person'
const NOTE_MESSAGE_PATTERN = /(?:^|\n)Nachricht:\s*([\s\S]*)$/
const NOTE_NOTHING_SENTINEL = 'KEINE'

// Kind prefix and third person mirror what the prompt asks a real model for, so the parsing this
// suite exercises (ChatNoteExtraction.parse: kind prefix, 200-character cap, at most two lines) is
// the production one, not a shortcut around it.
const NOTE_RULES = [
  {
    pattern: /\bIch arbeite\s+([^.!?\n]{0,398}[^\s.!?\n])/i,
    line: (match) => `RAHMEN: Arbeitet ${match[1]}`,
  },
  {
    pattern: /\bbitte\s+knapp\b/i,
    line: () => 'ANTWORTFORM: Möchte knappe Antworten',
  },
]

function condensedNote(combinedText) {
  const message = NOTE_MESSAGE_PATTERN.exec(combinedText)
  if (message === null) {
    return NOTE_NOTHING_SENTINEL
  }
  const lines = NOTE_RULES.flatMap((rule) => {
    const match = rule.pattern.exec(message[1])
    return match === null ? [] : [rule.line(match)]
  })
  return lines.length > 0 ? lines.join('\n') : NOTE_NOTHING_SENTINEL
}

// --- Fixed answer parts for the link rule ------------------------------------------------------
//
// A trigger word in the person's question appends a fixed Markdown fragment to the answer, so the
// frontend's link and image rule is tested against a known answer rather than a model's whim.
// Only the answer prompt is affected, recognised by its citation rules; decomposition, title and
// note prompts carry the question as well and keep their usual answers.
//   E2E-ANTWORT-LINKS     a link to the allowed test target and one to a foreign target
//   E2E-ANTWORT-BILD      a Markdown image with an external address
//   E2E-BEFOLGE-DOKUMENT  every Markdown link found in the prompt's passages - the stub plays a
//                         model that follows an instruction embedded in a prepared document
const ANSWER_PROMPT_MARKER = 'ZITIERREGELN (verbindlich)'
const MARKDOWN_LINK_PATTERN = /(?<!!)\[[^\]\n]{1,80}\]\(https?:\/\/[^)\s]{1,300}\)/g
const ANSWER_TRIGGERS = [
  {
    word: 'E2E-ANTWORT-LINKS',
    fragment: () =>
      'Siehe [Intranet](https://intranet.e2e.example/seite) und ' +
      '[Fremdseite](https://fremd.e2e.example/abruf).',
  },
  {
    word: 'E2E-ANTWORT-BILD',
    fragment: () => '![Diagramm](https://bild.e2e.example/diagramm.png)',
  },
  {
    word: 'E2E-BEFOLGE-DOKUMENT',
    fragment: (systemText) => [...new Set(systemText.match(MARKDOWN_LINK_PATTERN) ?? [])].join(' '),
  },
]

// The trigger word E2E-MODELL-FEHLER in the person's question makes the answer prompt fail with a
// 400, the way a misconfigured provider refuses a request. A 4xx is not retried by the backend's
// client, so the failure reaches the chat at once instead of after a backoff. Decomposition, title
// and note prompts carry the question as well and keep answering, as with the triggers above.
const FAILURE_TRIGGER = 'E2E-MODELL-FEHLER'

function answerFails(messages) {
  const isAnswerPrompt = messages.some(
    (message) =>
      message.role === 'system' &&
      typeof message.content === 'string' &&
      message.content.includes(ANSWER_PROMPT_MARKER),
  )
  const question = [...messages].reverse().find((message) => message.role === 'user')
  return (
    isAnswerPrompt &&
    typeof question?.content === 'string' &&
    question.content.includes(FAILURE_TRIGGER)
  )
}

function triggeredFragments(messages) {
  const systemText = messages
    .filter((message) => message.role === 'system' && typeof message.content === 'string')
    .map((message) => message.content)
    .join('\n')
  if (!systemText.includes(ANSWER_PROMPT_MARKER)) {
    return ''
  }
  const question = [...messages].reverse().find((message) => message.role === 'user')
  const questionText = typeof question?.content === 'string' ? question.content : ''
  const fragments = ANSWER_TRIGGERS.filter((trigger) => questionText.includes(trigger.word))
    .map((trigger) => trigger.fragment(systemText))
    .filter((fragment) => fragment !== '')
  return fragments.length > 0 ? `\n\n${fragments.join('\n\n')}` : ''
}

// Every managed chat model in this suite points at this same
// stub, so a scenario that activates a *different* model and then asks a chat question cannot
// otherwise tell whether the answer actually came from the newly activated model or from a stale,
// cached client for the previously active one (io.opaa.llm.ActiveChatModelResolver's whole point,
// #758) - both would produce an identical-looking answer. Remembering the last request's own
// "model" field (the modelIdentifier OpenAiApi sends verbatim) and exposing it here lets that
// scenario assert on it directly instead of only on the answer's mere presence.
let lastChatModel = null

// --- Reranking (set-up assistant, ADR-0060) ----------------------------------------------------
//
// POST .../rerank scores every candidate in the order it was sent, in either request spelling
// io.opaa.llm.RerankClient negotiates: {model, query, documents} answers with "results", {query,
// texts} with a bare array. Under the prefix /reranker the stub also lists one model, so the
// assistant's rerank step can offer a choice; the chat address (no prefix) keeps answering the
// model list with 404, which the chat step relies on to show its input by hand.
const RERANK_PREFIX = '/reranker'
const RERANK_MODEL = 'stub-reranker'

function ranking(count) {
  return Array.from({ length: count }, (_, index) => ({ index, score: 1 / (index + 1) }))
}

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

    if (req.method === 'POST' && matchesEndpoint(req.url, '/control/embedding-hold')) {
      const body = JSON.parse(await readBody(req))
      if (body.hold === true && typeof body.marker === 'string' && body.marker !== '') {
        holdMarker = body.marker
        heldCount = 0
      } else {
        holdMarker = null
        const waiting = releaseHeld
        releaseHeld = []
        waiting.forEach((release) => release())
      }
      sendJson(res, 200, { marker: holdMarker, held: heldCount })
      return
    }

    if (req.method === 'GET' && matchesEndpoint(req.url, '/control/embedding-hold')) {
      sendJson(res, 200, { marker: holdMarker, held: heldCount, waiting: releaseHeld.length })
      return
    }

    if (req.method === 'GET' && matchesEndpoint(req.url, '/transcription/models')) {
      sendJson(res, 200, {
        object: 'list',
        data: [{ id: TRANSCRIPTION_MODEL, object: 'model', owned_by: 'ai-stub' }],
      })
      return
    }

    if (req.method === 'POST' && matchesEndpoint(req.url, '/embeddings')) {
      const body = JSON.parse(await readBody(req))
      const inputs = Array.isArray(body.input) ? body.input : [body.input]
      if (holdsInput(inputs)) {
        heldCount += 1
        await waitForRelease()
      }
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

    if (req.method === 'GET' && matchesEndpoint(req.url, `${RERANK_PREFIX}/models`)) {
      sendJson(res, 200, { object: 'list', data: [{ id: RERANK_MODEL, object: 'model' }] })
      return
    }

    if (req.method === 'POST' && matchesEndpoint(req.url, '/rerank')) {
      const body = JSON.parse(await readBody(req))
      if (Array.isArray(body.documents)) {
        const results = ranking(body.documents.length).map(({ index, score }) => ({
          index,
          relevance_score: score,
        }))
        sendJson(res, 200, { model: body.model ?? RERANK_MODEL, results })
      } else if (Array.isArray(body.texts)) {
        sendJson(res, 200, ranking(body.texts.length))
      } else {
        sendJson(res, 422, { error: 'documents or texts required' })
      }
      return
    }

    if (req.method === 'POST' && matchesEndpoint(req.url, '/chat/completions')) {
      const body = JSON.parse(await readBody(req))
      lastChatModel = typeof body.model === 'string' ? body.model : null
      const messages = Array.isArray(body.messages) ? body.messages : []
      if (answerFails(messages)) {
        sendJson(res, 400, {
          error: {
            message: 'The stub refuses this answer prompt.',
            type: 'invalid_request_error',
            code: null,
            param: null,
          },
        })
        return
      }
      const combinedText = messages
        .map((message) => (typeof message.content === 'string' ? message.content : ''))
        .join('\n')
      const citations = [...combinedText.matchAll(CITATION_PATTERN)]
        .filter((match) => match[1] !== EXAMPLE_CITATION_DOCUMENT_ID)
        .map((match) => match[0])
      const uniqueCitations = [...new Set(citations)]
      const answer =
        (uniqueCitations.length > 0
          ? `Antwort auf Basis der bereitgestellten Dokumente. ${uniqueCitations.join(' ')}`
          : NO_CONTEXT_ANSWER) + triggeredFragments(messages)
      const content = combinedText.includes(NOTE_PROMPT_MARKER)
        ? condensedNote(combinedText)
        : answer

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
