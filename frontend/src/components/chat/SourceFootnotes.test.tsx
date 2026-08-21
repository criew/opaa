import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import SourceFootnotes from './SourceFootnotes'
import { buildCitationIndex } from './citations'
import type { SourceReference } from '../../types/api'

function source(fileName: string, cited = true): SourceReference {
  return {
    fileName,
    relevanceScore: 0.9,
    matchCount: 1,
    cited,
    indexedAt: null,
    citationValid: true,
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
})
