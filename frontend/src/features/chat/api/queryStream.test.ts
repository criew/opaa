import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { responseStatus } from '../stores/chatRefusals'
import { STREAM_BROKEN_MESSAGE, streamQuery } from './queryStream'

const FINAL_RESPONSE = {
  answer: 'Die Frist beträgt einen Monat.',
  generatedByAi: true,
  sources: [],
  privateSourcesInContext: false,
  metadata: { model: 'gpt-4o', tokenCount: 3, durationMs: 1 },
  chatId: 'chat-1',
}

/** A stream answer whose body arrives in exactly these pieces of text. */
function streamOf(pieces: string[]): HttpResponse<ReadableStream<Uint8Array>> {
  const encoder = new TextEncoder()
  return new HttpResponse(
    new ReadableStream({
      start(controller) {
        for (const piece of pieces) controller.enqueue(encoder.encode(piece))
        controller.close()
      },
    }),
    { headers: { 'Content-Type': 'text/event-stream' } },
  )
}

function event(payload: Record<string, unknown>): string {
  return `event: ${String(payload.type)}\ndata: ${JSON.stringify(payload)}\n\n`
}

function ask(onProvisional: (text: string) => void = () => {}, signal?: AbortSignal) {
  return streamQuery('Wie lang ist die Frist?', 'chat-1', true, [], null, undefined, {
    onProvisional,
    signal,
  })
}

describe('streamQuery', () => {
  it('tells the provisional text as it grows and resolves to the final response', async () => {
    const body =
      event({ type: 'started' }) +
      event({ type: 'delta', text: 'Die Frist ' }) +
      ': ping\n\n' +
      event({ type: 'delta', text: 'beträgt' }) +
      event({ type: 'reset', text: 'Neu: ' }) +
      event({ type: 'delta', text: 'einen Monat.' }) +
      event({ type: 'final', response: FINAL_RESPONSE })
    // Cut at arbitrary places, the way the network delivers it - also inside a multi-byte letter.
    const bytes = new TextEncoder().encode(body)
    const cuts = [7, 40, 41, 95, 160]
    const pieces: Uint8Array[] = []
    let from = 0
    for (const cut of [...cuts, bytes.length]) {
      pieces.push(bytes.slice(from, cut))
      from = cut
    }
    server.use(
      http.post(
        '/api/v1/query/stream',
        () =>
          new HttpResponse(
            new ReadableStream({
              start(controller) {
                for (const piece of pieces) controller.enqueue(piece)
                controller.close()
              },
            }),
            { headers: { 'Content-Type': 'text/event-stream' } },
          ),
      ),
    )
    const seen: string[] = []

    const response = await ask((text) => seen.push(text))

    expect(seen).toEqual(['Die Frist ', 'Die Frist beträgt', 'Neu: ', 'Neu: einen Monat.'])
    expect(response.answer).toBe('Die Frist beträgt einen Monat.')
    expect(response.chatId).toBe('chat-1')
  })

  it('rejects an error event like the status it names, so that a refusal is recognised', async () => {
    server.use(
      http.post('/api/v1/query/stream', () =>
        streamOf([
          event({ type: 'started' }),
          event({ type: 'delta', text: 'Teil' }),
          event({
            type: 'error',
            error: { error: 'Der Space ist archiviert.', status: 409, timestamp: '2026-10-31' },
          }),
        ]),
      ),
    )

    const failure = await ask().catch((err: unknown) => err)

    expect(failure).toBeInstanceOf(Error)
    expect((failure as Error).message).toBe('Der Space ist archiviert.')
    expect(responseStatus(failure)).toBe(409)
  })

  it('rejects a refusal before the stream with its own status and message', async () => {
    server.use(
      http.post('/api/v1/query/stream', () =>
        HttpResponse.json(
          { error: 'KI-Dienst vorübergehend nicht verfügbar', status: 503, timestamp: 'x' },
          { status: 503 },
        ),
      ),
    )

    const failure = await ask().catch((err: unknown) => err)

    expect((failure as Error).message).toBe('KI-Dienst vorübergehend nicht verfügbar')
    expect(responseStatus(failure)).toBe(503)
  })

  it('asks over POST /api/v1/query where the backend has no stream yet', async () => {
    server.use(
      http.post('/api/v1/query/stream', () => new HttpResponse(null, { status: 404 })),
      http.post('/api/v1/query', () => HttpResponse.json(FINAL_RESPONSE)),
    )

    const response = await ask()

    expect(response.answer).toBe('Die Frist beträgt einen Monat.')
  })

  it('rejects a stream that ends without its closing event', async () => {
    server.use(
      http.post('/api/v1/query/stream', () =>
        streamOf([event({ type: 'started' }), event({ type: 'delta', text: 'Teil' })]),
      ),
    )

    await expect(ask()).rejects.toThrow(STREAM_BROKEN_MESSAGE)
  })

  it('stops reading once its signal is aborted', async () => {
    const encoder = new TextEncoder()
    server.use(
      http.post(
        '/api/v1/query/stream',
        () =>
          new HttpResponse(
            new ReadableStream({
              start(controller) {
                controller.enqueue(encoder.encode(event({ type: 'started' })))
                controller.enqueue(encoder.encode(event({ type: 'delta', text: 'Teil' })))
              },
            }),
            { headers: { 'Content-Type': 'text/event-stream' } },
          ),
      ),
    )
    const abort = new AbortController()

    const answer = ask(() => abort.abort(), abort.signal)

    await expect(answer).rejects.toBeDefined()
    expect(abort.signal.aborted).toBe(true)
  })
})
