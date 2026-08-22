import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import SourceFootnotes from './SourceFootnotes'
import { buildCitationIndex } from './citations'
import type { SourceReference } from '../../types/api'

// #739: the "Im Dokument öffnen" action delegates to this shared module (see its own tests for
// the Blob-fetch/preview-vs-download behaviour, and LibraryDetailPage.test.tsx for #738's original
// wiring of this mock pattern).
const { mockOpenDocumentContent, mockOpenExternalSourceUrl } = vi.hoisted(() => ({
  mockOpenDocumentContent: vi.fn(async () => undefined),
  mockOpenExternalSourceUrl: vi.fn(),
}))
vi.mock('../../utils/documentContent', () => ({
  openDocumentContent: mockOpenDocumentContent,
  openExternalSourceUrl: mockOpenExternalSourceUrl,
}))

function source(
  fileName: string,
  cited = true,
  extra: Partial<SourceReference> = {},
): SourceReference {
  return {
    fileName,
    relevanceScore: 0.9,
    matchCount: 1,
    cited,
    indexedAt: null,
    citationValid: true,
    ...extra,
  }
}

/** An answer citing `count` distinct documents once each. */
function indexWithDocs(count: number) {
  const content = Array.from(
    { length: count },
    (_, i) => `Satz【source: doc-${i}#0 | datei-${i}.md】`,
  ).join(' ')
  const sources = Array.from({ length: count }, (_, i) => source(`datei-${i}.md`))
  return buildCitationIndex(content, sources)
}

describe('SourceFootnotes', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows every row while the block stays within three documents', () => {
    renderWithProviders(<SourceFootnotes messageId="m1" citations={indexWithDocs(3)} />)

    expect(screen.getByText('datei-0.md')).toBeVisible()
    expect(screen.getByText('datei-2.md')).toBeVisible()
    expect(screen.queryByText(/weitere Dokumente/)).not.toBeInTheDocument()
  })

  it('folds everything beyond three documents behind a quiet toggle (mockup 1a)', () => {
    renderWithProviders(<SourceFootnotes messageId="m2" citations={indexWithDocs(9)} />)

    expect(screen.getByText('datei-2.md')).toBeVisible()
    expect(screen.queryByText('datei-3.md')).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '6 weitere Dokumente mit 6 Stellen anzeigen' }),
    ).toBeInTheDocument()
  })

  it('expands the folded rows on click', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SourceFootnotes messageId="m3" citations={indexWithDocs(5)} />)

    await user.click(
      screen.getByRole('button', { name: '2 weitere Dokumente mit 2 Stellen anzeigen' }),
    )

    expect(await screen.findByText('datei-4.md')).toBeVisible()
    expect(
      screen.getByRole('button', { name: '2 weitere Dokumente mit 2 Stellen ausblenden' }),
    ).toBeInTheDocument()
  })

  it('uses singular wording for a single folded document', () => {
    renderWithProviders(<SourceFootnotes messageId="m4" citations={indexWithDocs(4)} />)

    expect(
      screen.getByRole('button', { name: '1 weiteres Dokument mit 1 Stelle anzeigen' }),
    ).toBeInTheDocument()
  })

  describe('"Im Dokument öffnen"', () => {
    it('fetches and opens a local original (UPLOAD/FILESYSTEM) via the download endpoint', async () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | dienstanweisung.pdf】', [
        source('dienstanweisung.pdf', true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      ])
      renderWithProviders(<SourceFootnotes messageId="m5" citations={citations} />)
      const user = userEvent.setup()

      await user.click(screen.getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(mockOpenDocumentContent).toHaveBeenCalledWith('doc-1', 'dienstanweisung.pdf')
      expect(mockOpenExternalSourceUrl).not.toHaveBeenCalled()
    })

    it('renders a real link (not a button) to sourceUrl for an HTTP_DIRECTORY document (#745 review)', () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | dienstanweisung.pdf】', [
        source('dienstanweisung.pdf', true, {
          documentId: 'doc-1',
          sourceType: 'HTTP_DIRECTORY',
          sourceUrl: 'https://example.gov/verzeichnis/dienstanweisung.pdf',
        }),
      ])
      renderWithProviders(<SourceFootnotes messageId="m6" citations={citations} />)

      // A real <a href> instead of component="button" - middle-click, "open in new tab" and
      // "copy link address" only work on an actual link.
      const link = screen.getByRole('link', { name: 'Im Dokument öffnen' })
      expect(link).toHaveAttribute('href', 'https://example.gov/verzeichnis/dienstanweisung.pdf')
      expect(link).toHaveAttribute('target', '_blank')
      expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
      expect(mockOpenExternalSourceUrl).not.toHaveBeenCalled()
      expect(mockOpenDocumentContent).not.toHaveBeenCalled()
    })

    it('hides the action for a synthetic entry with neither a documentId nor a source URL', () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | dienstanweisung.pdf】', [
        source('dienstanweisung.pdf', true),
      ])
      renderWithProviders(<SourceFootnotes messageId="m7" citations={citations} />)

      expect(screen.queryByRole('button', { name: 'Im Dokument öffnen' })).not.toBeInTheDocument()
    })

    it('shows a German error message when opening the original fails (e.g. 404)', async () => {
      mockOpenDocumentContent.mockRejectedValueOnce(
        new Error('Das Originaldokument wurde nicht gefunden.'),
      )
      const citations = buildCitationIndex('Satz【source: doc-1#0 | dienstanweisung.pdf】', [
        source('dienstanweisung.pdf', true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      ])
      renderWithProviders(<SourceFootnotes messageId="m8" citations={citations} />)
      const user = userEvent.setup()

      await user.click(screen.getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(
        await screen.findByText('Das Originaldokument wurde nicht gefunden.'),
      ).toBeInTheDocument()
    })
  })
})
