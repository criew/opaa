import { AxiosError } from 'axios'
import { describe, expect, it } from 'vitest'
import { chatPdfExportError } from './chatApi'

function blobError(status: number, body: string, type = 'application/json'): AxiosError {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status,
    statusText: '',
    headers: {},
    config: {} as never,
    data: new Blob([body], { type }),
  })
}

describe('chatPdfExportError', () => {
  it.each([
    [403, 'Sie sind kein Mitglied dieses Space mehr'],
    [404, 'Chat nicht gefunden'],
  ])('reads the German message of a %i refusal from the blob body', async (status, error) => {
    const err = blobError(status, JSON.stringify({ status, error }))

    const mapped = await chatPdfExportError(err)

    expect(mapped.message).toBe(error)
    expect(mapped.cause).toBe(err)
  })

  it('falls back to one generic message for a body that is no ErrorResponse', async () => {
    const mapped = await chatPdfExportError(blobError(502, '<html>Bad Gateway</html>', 'text/html'))

    expect(mapped.message).toBe('Der Chat konnte nicht als PDF exportiert werden.')
  })

  it('falls back to the generic message without a response at all', async () => {
    const mapped = await chatPdfExportError(new AxiosError('Network Error'))

    expect(mapped.message).toBe('Der Chat konnte nicht als PDF exportiert werden.')
  })
})
