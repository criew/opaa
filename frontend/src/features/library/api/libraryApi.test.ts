import { AxiosError } from 'axios'
import { describe, expect, it } from 'vitest'
import {
  countLibraryDocuments,
  getLibraryDocuments,
  mapDocumentContentError,
  parseContentDispositionFileName,
} from './libraryApi'

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

describe('libraryApi', () => {
  // getDocumentContent() itself cannot be exercised end-to-end here - a Blob response
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

    // A plain regex without the negative lookahead would match the filename*
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

    it('says that a library being erased opens no document any more (#2165)', async () => {
      const err = axiosErrorWithResponse(
        404,
        new Blob([
          JSON.stringify({ error: 'Die Bibliothek wird gelöscht', code: 'LIBRARY_BEING_ERASED' }),
        ]),
      )

      await expect(mapDocumentContentError(err)).rejects.toThrow(
        'Die Bibliothek wird gelöscht – ihre Dokumente lassen sich nicht mehr öffnen.',
      )
    })

    it('says that an original with a malware finding is locked', async () => {
      const err = axiosErrorWithResponse(
        404,
        new Blob([
          JSON.stringify({
            error: 'Die Datei ist gesperrt, weil die Virenprüfung ein Schadprogramm gefunden hat',
            code: 'MALWARE_FOUND',
          }),
        ]),
      )

      await expect(mapDocumentContentError(err)).rejects.toThrow(
        'Die Datei ist gesperrt, weil die Virenprüfung einen Befund hatte.',
      )
    })

    it('goes by the code, not by the wording of the message', async () => {
      const err = axiosErrorWithResponse(
        404,
        new Blob([JSON.stringify({ error: 'Die Bibliothek wird gelöscht' })]),
      )

      await expect(mapDocumentContentError(err)).rejects.toThrow(
        'Das Originaldokument wurde nicht gefunden.',
      )
    })

    // A non-404 failure arrives with responseType 'blob' applied to its body too, so the
    // ErrorResponse JSON must be read out of the Blob rather than off err.response.data directly -
    // otherwise normalizeError's generic English "HTTP <status>: ..." fallback would replace the
    // backend's German message.
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
  // Runs against the MSW handler, which reads the repeated parameter the way the backend does:
  // axios' default `status[]=` would arrive as no filter and list the whole root instead.
  describe('getLibraryDocuments with status', () => {
    const fileNames = (page: { items: { fileName: string }[] }) =>
      page.items.map((item) => item.fileName)

    it('lists only the documents in the given state, with their number', async () => {
      const page = await getLibraryDocuments('library-mine', { status: 'FAILED' })
      expect(fileNames(page)).toEqual(['vermerk.pptx'])
      expect(page.totalElements).toBe(1)
      expect(page.folders).toEqual([])
    })

    it('sends several states as a repeated parameter and lists their union', async () => {
      const page = await getLibraryDocuments('library-mine', { status: ['FAILED', 'PENDING'] })
      expect(fileNames(page).sort()).toEqual(['rundschreiben-03.docx', 'vermerk.pptx'])
      expect(page.totalElements).toBe(2)
    })

    it('counts with size 1 across the whole library', async () => {
      const page = await getLibraryDocuments('library-mine', { size: 1, status: 'INDEXED' })
      expect(page.items).toHaveLength(1)
      // attachments are rows of their own here, unlike the grouped folder listing
      expect(page.totalElements).toBe(4)
    })

    it('counts without advancing the simulated processing, which a list load does', async () => {
      for (let i = 0; i < 3; i++) {
        expect(await countLibraryDocuments('library-mine', 'PENDING')).toBe(1)
      }
      await getLibraryDocuments('library-mine')
      await getLibraryDocuments('library-mine')
      expect(await countLibraryDocuments('library-mine', 'PENDING')).toBe(0)
    })

    it('lists nothing for the maintenance filter with a status that is not INDEXED', async () => {
      const failed = await getLibraryDocuments('library-mine', {
        missingMetadataField: 'document_type',
        status: 'FAILED',
      })
      expect(failed.totalElements).toBe(0)
    })

    it('names the parent of an attachment on a flat page', async () => {
      const page = await getLibraryDocuments('library-mine', { status: 'INDEXED' })
      const attachment = page.items.find((item) => item.fileName === 'foerderbescheid.pdf')
      expect(attachment?.parentFileName).toBe('posteingang-foerderbescheid.eml')
    })

    it('leaves the listing unchanged without a status', async () => {
      const withNull = await getLibraryDocuments('library-mine', { status: null })
      const without = await getLibraryDocuments('library-mine')
      expect(withNull.totalElements).toBe(without.totalElements)
      expect(withNull.items.length).toBeGreaterThan(2)
    })
  })
})
