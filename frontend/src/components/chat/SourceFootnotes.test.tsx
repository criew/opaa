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

  // #1164: a source whose retrieved chunk carried mail_* metadata shows a summary line at the
  // Fundstelle; a non-mail source (indexWithDocs' plain fixtures) shows none.
  describe('mail Kopfdaten summary', () => {
    it('shows sender, date and Betreff for a source with mail metadata', () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | anfrage.eml】', [
        source('anfrage.eml', true, {
          documentId: 'doc-1',
          mailFrom: 'mueller@stadt.de',
          mailDate: '2026-03-14T09:15:00Z',
          mailSubject: 'Bebauungsplan Nord',
        }),
      ])

      renderFootnotes(citations)

      expect(screen.getByTestId('source-mail-summary')).toHaveTextContent(
        'Mail von mueller@stadt.de, 14.03.2026 — Bebauungsplan Nord',
      )
    })

    it('shows no summary line for a source without mail metadata', () => {
      renderFootnotes(indexWithDocs(1))

      expect(screen.queryByTestId('source-mail-summary')).not.toBeInTheDocument()
    })

    // #1164 review: mailTo alone satisfies formatMailSummary's presence guard (a mail source
    // with only a recipient is still a mail source), but mailTo itself never feeds a rendered
    // segment (a distribution list is long and not useful for identifying the passage) - with
    // no from/date/subject to build a segment from, the line still renders nothing at all.
    it('renders no summary for a mailTo-only source, same as a non-mail source', () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | rundschreiben.eml】', [
        source('rundschreiben.eml', true, {
          documentId: 'doc-1',
          mailTo: 'verteiler@stadt.de',
        }),
      ])

      renderFootnotes(citations)

      expect(screen.queryByTestId('source-mail-summary')).not.toBeInTheDocument()
    })
  })

  // #1066 (metadata-schema.md, Wirkstelle 3; Maintainer-Beschluss 04.09.2026): the Beleg renders
  // the generic metadata list without field knowledge; an empty field is not in the list and so
  // never renders, a derived value is marked, a year-only date arrives already as "2024".
  describe('metadata line', () => {
    it('renders every entry by its display value, in list order, with an accessible description', () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | 2026-03-12_da.pdf】', [
        source('2026-03-12_da.pdf', true, {
          documentId: 'doc-1',
          metadata: [
            {
              fieldKey: 'title',
              label: 'Titel',
              value: 'Dienstanweisung IT-Nutzung',
              displayValue: 'Dienstanweisung IT-Nutzung',
              origin: 'DETERMINISTIC',
            },
            {
              fieldKey: 'document_type',
              label: 'Dokumentart',
              value: 'DIENSTANWEISUNG',
              displayValue: 'Dienstanweisung',
              origin: 'DETERMINISTIC',
            },
            {
              fieldKey: 'document_date',
              label: 'Datum/Stand',
              value: '2026-03-12',
              displayValue: '12.03.2026',
              origin: 'DETERMINISTIC',
              datePrecision: 'DAY',
            },
          ],
        }),
      ])

      renderFootnotes(citations)

      expect(screen.getByText('2026-03-12_da.pdf')).toBeVisible()
      const line = screen.getByTestId('source-metadata')
      expect(line).toHaveTextContent('Dienstanweisung IT-Nutzung · Dienstanweisung · 12.03.2026')
      expect(line).toHaveAccessibleName(
        'Titel: Dienstanweisung IT-Nutzung, Dokumentart: Dienstanweisung, Datum/Stand: 12.03.2026',
      )
    })

    it('renders only the entries the backend sent and marks a derived value', () => {
      const citations = buildCitationIndex('Satz【source: doc-1#0 | satzung.md】', [
        source('satzung.md', true, {
          documentId: 'doc-1',
          metadata: [
            {
              fieldKey: 'document_date',
              label: 'Datum/Stand',
              value: '2024-01-01',
              displayValue: '2024',
              origin: 'DERIVED',
              datePrecision: 'YEAR',
            },
          ],
        }),
      ])

      renderFootnotes(citations)

      expect(screen.getByTestId('source-metadata')).toHaveTextContent('2024 (abgeleitet)')
      expect(screen.getByTestId('source-metadata')).not.toHaveTextContent('01.01.2024')
      expect(screen.queryByText(/ohne Angabe/)).not.toBeInTheDocument()
    })

    // #1070: a hit the Leerwert rule kept under an active filter is marked "ohne Angabe" - and
    // only such a hit; a matched source and a source of an unfiltered answer carry no mark.
    it('marks a hit kept without a value for the filtered field as "ohne Angabe"', () => {
      const citations = buildCitationIndex(
        'Satz【source: doc-1#0 | ohne-art.pdf】 und 【source: doc-2#0 | vermerk.pdf】',
        [
          source('ohne-art.pdf', true, { documentId: 'doc-1', metadataFilterMatch: 'NO_VALUE' }),
          source('vermerk.pdf', true, { documentId: 'doc-2', metadataFilterMatch: 'MATCHED' }),
        ],
      )

      renderFootnotes(citations)

      const marks = screen.getAllByTestId('source-filter-match')
      expect(marks).toHaveLength(1)
      expect(marks[0]).toHaveTextContent('ohne Angabe')
      expect(marks[0]).toHaveAccessibleName('Metadatenfilter: ohne Angabe im gefilterten Feld')
    })

    it('shows no line at all for a document without metadata', () => {
      renderFootnotes(indexWithDocs(1))

      expect(screen.queryByTestId('source-metadata')).not.toBeInTheDocument()
      expect(screen.queryByTestId('source-filter-match')).not.toBeInTheDocument()
    })
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
