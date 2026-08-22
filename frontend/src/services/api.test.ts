import { AxiosError } from 'axios'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../mocks/server'
import {
  createSpace,
  getHealth,
  mapDocumentContentError,
  normalizeError,
  parseContentDispositionFileName,
  sendQuery,
  updateSpaceDetails,
} from './api'

/** Minimal stand-in for the parts of AxiosResponse that normalizeError reads. */
function axiosErrorWithResponse(status: number, data: unknown): AxiosError {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status,
    statusText: '',
    headers: {},
    // AxiosResponse requires a config; not read by normalizeError, so an empty object suffices.
    config: {} as never,
    data,
  })
}

describe('api service', () => {
  describe('getHealth', () => {
    it('returns health status', async () => {
      const result = await getHealth()
      expect(result.status).toBe('UP')
    })
  })

  describe('sendQuery', () => {
    it('returns answer with sources', async () => {
      const result = await sendQuery('What is the architecture?')
      expect(result.answer).toBeDefined()
      expect(result.sources.length).toBeGreaterThanOrEqual(1)
      expect(result.metadata.model).toBe('gpt-4o')
    })
  })

  describe('updateSpaceDetails', () => {
    it('sends only name, description and visibility - no kind, ownerId or initialMembers', async () => {
      let capturedBody: unknown = null
      server.use(
        http.put('/api/v1/spaces/:spaceId', async ({ request }) => {
          capturedBody = await request.json()
          return HttpResponse.json({
            id: 'space-1',
            name: 'Renamed',
            isDefault: false,
            visibility: 'PRIVATE',
            ownerId: 'u1',
            memberCount: 1,
            roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
            members: [],
            createdAt: '2026-03-01T10:00:00Z',
            updatedAt: '2026-03-01T10:00:00Z',
          })
        }),
      )

      await updateSpaceDetails('space-1', 'Renamed', 'A new description')

      expect(capturedBody).toEqual({
        name: 'Renamed',
        description: 'A new description',
        visibility: undefined,
      })
    })

    // #272: visibility is merged rather than replaced server-side (SpaceUpdateRequest), but the
    // caller still sends the value it wants applied - this is the request body the UI produces
    // when the user actually changes the visibility.
    it('sends the chosen visibility when the caller changes it', async () => {
      let capturedBody: unknown = null
      server.use(
        http.put('/api/v1/spaces/:spaceId', async ({ request }) => {
          capturedBody = await request.json()
          return HttpResponse.json({
            id: 'space-1',
            name: 'Renamed',
            isDefault: false,
            visibility: 'OPEN',
            ownerId: 'u1',
            memberCount: 1,
            roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
            members: [],
            createdAt: '2026-03-01T10:00:00Z',
            updatedAt: '2026-03-01T10:00:00Z',
          })
        }),
      )

      await updateSpaceDetails('space-1', 'Renamed', 'A new description', 'OPEN')

      expect(capturedBody).toEqual({
        name: 'Renamed',
        description: 'A new description',
        visibility: 'OPEN',
      })
    })
  })

  describe('createSpace', () => {
    it('sends the chosen visibility in the create request body', async () => {
      let capturedBody: unknown = null
      server.use(
        http.post('/api/v1/spaces', async ({ request }) => {
          capturedBody = await request.json()
          return HttpResponse.json({
            id: 'space-new',
            name: 'New Space',
            isDefault: false,
            visibility: 'DISCOVERABLE',
            ownerId: 'u1',
            memberCount: 1,
            roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
            members: [],
            createdAt: '2026-03-01T10:00:00Z',
            updatedAt: '2026-03-01T10:00:00Z',
          })
        }),
      )

      await createSpace('New Space', 'A description', 'DISCOVERABLE')

      expect(capturedBody).toMatchObject({
        name: 'New Space',
        description: 'A description',
        visibility: 'DISCOVERABLE',
        initialMembers: [],
      })
    })

    it('sends libraryIds when provided (#686)', async () => {
      let capturedBody: unknown = null
      server.use(
        http.post('/api/v1/spaces', async ({ request }) => {
          capturedBody = await request.json()
          return HttpResponse.json({
            id: 'space-new',
            name: 'New Space',
            isDefault: false,
            visibility: 'PRIVATE',
            ownerId: 'u1',
            memberCount: 1,
            roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
            members: [],
            createdAt: '2026-03-01T10:00:00Z',
            updatedAt: '2026-03-01T10:00:00Z',
          })
        }),
      )

      await createSpace('New Space', 'A description', 'PRIVATE', ['lib-1', 'lib-2'])

      expect(capturedBody).toMatchObject({ libraryIds: ['lib-1', 'lib-2'] })
    })
  })

  // #738/#743: getDocumentContent() itself cannot be exercised end-to-end here - a Blob response
  // body (success or error) hangs against msw/node in this project's jsdom test environment (see
  // the comment on mapDocumentContentError in api.ts). Both pieces getDocumentContent is built from
  // are pure/independent of any HTTP call, though, and are tested directly instead: the
  // Content-Disposition parser against header strings, and the error mapping against a
  // constructed AxiosError carrying a plain Blob (which works fine in jsdom - only the MSW/XHR
  // bridge for a real network round-trip hangs).
  describe('parseContentDispositionFileName', () => {
    it('reads the plain filename parameter', () => {
      expect(parseContentDispositionFileName('inline; filename="dienstanweisung 2024.pdf"')).toBe(
        'dienstanweisung 2024.pdf',
      )
    })

    it('prefers the RFC 5987 filename* parameter over the plain filename parameter', () => {
      expect(
        parseContentDispositionFileName(
          'inline; filename="anlage.pdf"; filename*=UTF-8\'\'Anlage%20%C3%9C.pdf',
        ),
      ).toBe('Anlage Ü.pdf')
    })

    it('decodes percent-encoded Umlauts in the filename* parameter', () => {
      expect(
        parseContentDispositionFileName("attachment; filename*=UTF-8''Verm%C3%B6gen.docx"),
      ).toBe('Vermögen.docx')
    })

    it('falls back to the plain filename parameter when filename* cannot be decoded', () => {
      expect(
        parseContentDispositionFileName(
          'inline; filename="anlage.pdf"; filename*=UTF-8\'\'not%a-valid%-encoding',
        ),
      ).toBe('anlage.pdf')
    })

    // #743 (review): a plain regex without the negative lookahead would match the filename*
    // parameter's own "filename" prefix here, returning "*=UTF-8''Anlage%20%C3%9C.pdf" as the file
    // name instead of falling back to null.
    it('does not match the filename* parameter as a plain filename', () => {
      expect(parseContentDispositionFileName("inline; filename*=UTF-8''Anlage%20%C3%9C.pdf")).toBe(
        'Anlage Ü.pdf',
      )
    })

    it('returns null when Content-Disposition is missing', () => {
      expect(parseContentDispositionFileName(undefined)).toBeNull()
    })

    it('returns null when neither filename parameter is present', () => {
      expect(parseContentDispositionFileName('inline')).toBeNull()
    })
  })

  describe('mapDocumentContentError', () => {
    it('throws a German message for a 404 (missing/remote-only document)', async () => {
      const err = axiosErrorWithResponse(
        404,
        new Blob([JSON.stringify({ error: 'nicht gefunden' })]),
      )

      await expect(mapDocumentContentError(err)).rejects.toThrow(
        'Das Originaldokument wurde nicht gefunden. Es wurde möglicherweise verschoben oder gelöscht.',
      )
    })

    // #743 (review, nit 1): a non-404 failure arrives with responseType 'blob' applied to its body
    // too, so the ErrorResponse JSON must be read out of the Blob rather than off err.response.data
    // directly - the previous behaviour fell through to normalizeError's generic English
    // "HTTP <status>: ..." fallback here instead of the backend's German message.
    it('reads a German ErrorResponse out of a non-404 Blob error body', async () => {
      const err = axiosErrorWithResponse(
        403,
        new Blob([JSON.stringify({ error: 'Kein Zugriff auf dieses Dokument' })]),
      )

      await expect(mapDocumentContentError(err)).rejects.toThrow('Kein Zugriff auf dieses Dokument')
    })

    it('falls back to a generic German message when the Blob body is not a valid ErrorResponse', async () => {
      const err = axiosErrorWithResponse(500, new Blob(['<html>not json</html>']))

      await expect(mapDocumentContentError(err)).rejects.toThrow(
        'Das Originaldokument konnte nicht geladen werden.',
      )
    })

    it('falls back to normalizeError when there is no Blob response body at all', async () => {
      const err = axiosErrorWithResponse(500, undefined)

      await expect(mapDocumentContentError(err)).rejects.toThrow('HTTP 500')
    })
  })

  describe('normalizeError', () => {
    it('throws error message from valid ErrorResponse JSON', async () => {
      server.use(
        http.get('/api/health', () => {
          return HttpResponse.json(
            { error: 'Service unavailable', status: 503, timestamp: new Date().toISOString() },
            { status: 503 },
          )
        }),
      )

      await expect(getHealth()).rejects.toThrow('Service unavailable')
    })

    it('falls back to HTTP status when response is not JSON ErrorResponse', async () => {
      server.use(
        http.get('/api/health', () => {
          return new HttpResponse('<html>Bad Gateway</html>', {
            status: 502,
            headers: { 'Content-Type': 'text/html' },
          })
        }),
      )

      await expect(getHealth()).rejects.toThrow(/HTTP 502/)
    })

    // The four cases below cover normalizeError's context-scoped 413 handling directly with a
    // constructed AxiosError rather than a real upload request through uploadDocument(): a real
    // multipart POST carrying a File/Blob body hangs indefinitely against msw/node in this
    // project's jsdom test environment (reproduced independently of this change), so the
    // upload-only branch cannot be exercised end-to-end here. normalizeError is exported from
    // api.ts for exactly this purpose.

    it('translates a non-JSON 413 (e.g. the nginx reverse proxy HTML page) to a German message for an upload call', () => {
      const err = axiosErrorWithResponse(
        413,
        '<html><body>413 Request Entity Too Large</body></html>',
      )

      expect(() => normalizeError(err, 'upload')).toThrow(
        'Die Datei ist zu groß für den Upload. Bitte eine kleinere Datei wählen.',
      )
    })

    it('still surfaces the backend JSON ErrorResponse message for a 413 on an upload call', () => {
      const err = axiosErrorWithResponse(413, {
        error: 'Die Datei ist zu groß. Erlaubt sind höchstens 50 MB.',
        status: 413,
        timestamp: new Date().toISOString(),
      })

      expect(() => normalizeError(err, 'upload')).toThrow(
        'Die Datei ist zu groß. Erlaubt sind höchstens 50 MB.',
      )
    })

    it('does not translate a non-JSON 413 on a call without upload context - falls back to the generic HTTP message', () => {
      const err = axiosErrorWithResponse(
        413,
        '<html><body>413 Request Entity Too Large</body></html>',
      )

      expect(() => normalizeError(err)).toThrow(/HTTP 413/)
    })

    it('does not translate a non-JSON 413 on a real (non-upload) network call either', async () => {
      server.use(
        http.get('/api/health', () => {
          return new HttpResponse('<html><body>413 Request Entity Too Large</body></html>', {
            status: 413,
            headers: { 'Content-Type': 'text/html' },
          })
        }),
      )

      await expect(getHealth()).rejects.toThrow(/HTTP 413/)
    })

    it('falls back to error message on network error', async () => {
      server.use(
        http.get('/api/health', () => {
          return HttpResponse.error()
        }),
      )

      await expect(getHealth()).rejects.toThrow()
    })
  })
})
