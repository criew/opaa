import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import SourceFootnotes from './SourceFootnotes'
import { buildCitationIndex } from './citations'
import type { CitationIndex } from './citations'
import type { SourceReference } from '../../types/api'

// #739/#780/#781 review (Wichtig 1): SourceFootnotes no longer calls `openDocumentContent`
// itself - it receives MessageBubble's single `useDocumentPreview()` instance as props
// (openDocument/error/clearError), shared with SourceEvidenceDrawer (see MessageBubble.tsx and
// useDocumentPreview.test.ts for the hook's own preview/download-branching behaviour). This file
// tests SourceFootnotes as the presentational component it now is.
const mockOpenDocument = vi.fn(async () => undefined)
const mockClearError = vi.fn()

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

function renderFootnotes(
  citations: CitationIndex,
  overrides: Partial<{
    messageId: string
    error: string | null
  }> = {},
) {
  return renderWithProviders(
    <SourceFootnotes
      messageId={overrides.messageId ?? 'm1'}
      citations={citations}
      openDocument={mockOpenDocument}
      error={overrides.error ?? null}
      clearError={mockClearError}
    />,
  )
}

describe('SourceFootnotes', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows every row while the block stays within three documents', () => {
    renderFootnotes(indexWithDocs(3))

    expect(screen.getByText('datei-0.md')).toBeVisible()
    expect(screen.getByText('datei-2.md')).toBeVisible()
    expect(screen.queryByText(/weitere Dokumente/)).not.toBeInTheDocument()
  })

  it('folds everything beyond three documents behind a quiet toggle (mockup 1a)', () => {
    renderFootnotes(indexWithDocs(9))

    expect(screen.getByText('datei-2.md')).toBeVisible()
    expect(screen.queryByText('datei-3.md')).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '6 weitere Dokumente mit 6 Stellen anzeigen' }),
    ).toBeInTheDocument()
  })

  it('expands the folded rows on click', async () => {
    const user = userEvent.setup()
    renderFootnotes(indexWithDocs(5))

    await user.click(
      screen.getByRole('button', { name: '2 weitere Dokumente mit 2 Stellen anzeigen' }),
    )

    expect(await screen.findByText('datei-4.md')).toBeVisible()
    expect(
      screen.getByRole('button', { name: '2 weitere Dokumente mit 2 Stellen ausblenden' }),
    ).toBeInTheDocument()
  })

  it('uses singular wording for a single folded document', () => {
    renderFootnotes(indexWithDocs(4))

    expect(
      screen.getByRole('button', { name: '1 weiteres Dokument mit 1 Stelle anzeigen' }),
    ).toBeInTheDocument()
  })

  describe('"Im Dokument öffnen"', () => {
    it('calls openDocument with the documentId and file name for a local (UPLOAD/FILESYSTEM) original', async () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | dienstanweisung.pdf】', [
        source('dienstanweisung.pdf', true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      ])
      renderFootnotes(citations)
      const user = userEvent.setup()

      await user.click(screen.getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(mockOpenDocument).toHaveBeenCalledWith('doc-1', 'dienstanweisung.pdf')
    })

    it('calls openDocument for an HTTP_DIRECTORY document too, keeping sourceUrl as a secondary "Quelle" link (#747)', async () => {
      // #747: the content endpoint now proxies HTTP_DIRECTORY/RSS_FEED server-side from their own
      // stored source URL - the primary action is the same button as for a local original,
      // sourceUrl stays visible as secondary information (a tooltip carrying the raw address).
      const citations = buildCitationIndex('Satz【source: doc-1#0 | dienstanweisung.pdf】', [
        source('dienstanweisung.pdf', true, {
          documentId: 'doc-1',
          sourceType: 'HTTP_DIRECTORY',
          sourceUrl: 'https://example.gov/verzeichnis/dienstanweisung.pdf',
        }),
      ])
      renderFootnotes(citations)
      const user = userEvent.setup()

      await user.click(screen.getByRole('button', { name: 'Im Dokument öffnen' }))

      expect(mockOpenDocument).toHaveBeenCalledWith('doc-1', 'dienstanweisung.pdf')

      const sourceLink = screen.getByRole('link', {
        name: 'Quelle: https://example.gov/verzeichnis/dienstanweisung.pdf',
      })
      expect(sourceLink).toHaveAttribute(
        'href',
        'https://example.gov/verzeichnis/dienstanweisung.pdf',
      )
      expect(sourceLink).toHaveAttribute('target', '_blank')
      expect(sourceLink).toHaveAttribute('rel', expect.stringContaining('noopener'))
    })

    it('hides the action for a synthetic entry with no documentId at all', () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | dienstanweisung.pdf】', [
        source('dienstanweisung.pdf', true),
      ])
      renderFootnotes(citations)

      expect(screen.queryByRole('button', { name: 'Im Dokument öffnen' })).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: 'Quelle' })).not.toBeInTheDocument()
    })

    it('does not call openDocument for a synthetic entry with no documentId, even if clicked via keyboard', () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | dienstanweisung.pdf】', [
        source('dienstanweisung.pdf', true),
      ])
      renderFootnotes(citations)

      expect(mockOpenDocument).not.toHaveBeenCalled()
    })

    it('shows the German error message passed in via the error prop', () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | dienstanweisung.pdf】', [
        source('dienstanweisung.pdf', true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      ])
      renderFootnotes(citations, { error: 'Das Originaldokument wurde nicht gefunden.' })

      expect(screen.getByText('Das Originaldokument wurde nicht gefunden.')).toBeInTheDocument()
    })

    it('calls clearError when the error alert is dismissed', async () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | dienstanweisung.pdf】', [
        source('dienstanweisung.pdf', true, {
          documentId: 'doc-1',
          sourceType: 'UPLOAD',
        }),
      ])
      renderFootnotes(citations, { error: 'Das Originaldokument wurde nicht gefunden.' })
      const user = userEvent.setup()

      // #784: the deDE MUI locale translates Alert's default close button label.
      await user.click(screen.getByRole('button', { name: 'Schließen' }))

      expect(mockClearError).toHaveBeenCalledTimes(1)
    })
  })
})
