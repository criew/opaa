import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../../test/test-utils'
import MessageBubble from './MessageBubble'
import { useAuthStore } from '@/shared/session/authStore'
import type { SourceReference } from '@/shared/api/types'

/** A Beleg as a chat stored it while metadata was on: title, Dokumentart, a mail detail, a mark. */
const storedSource: SourceReference = {
  fileName: '2026-03-12_da.pdf',
  relevanceScore: 1,
  matchCount: 1,
  cited: true,
  indexedAt: null,
  citationValid: true,
  privateSource: false,
  metadataFilterMatch: 'NO_VALUE',
  metadata: [
    {
      fieldKey: 'title',
      label: 'Titel',
      value: 'Dienstanweisung IT-Nutzung',
      displayValue: 'Dienstanweisung IT-Nutzung',
      origin: 'DETERMINISTIC',
      detailOnly: false,
    },
    {
      fieldKey: 'document_type',
      label: 'Dokumentart',
      value: 'DIENSTANWEISUNG',
      displayValue: 'Dienstanweisung',
      origin: 'DETERMINISTIC',
      detailOnly: false,
    },
    {
      fieldKey: 'fmt:mail_to',
      label: 'An',
      value: 'poststelle@example.org',
      displayValue: 'poststelle@example.org',
      origin: 'DETERMINISTIC',
      detailOnly: true,
    },
  ],
}

async function openDrawer() {
  const user = userEvent.setup()
  renderWithProviders(
    <MessageBubble
      message={{
        id: 'ev-flag',
        role: 'assistant',
        content: 'Beleg【source: a#0 | 2026-03-12_da.pdf】.',
        sources: [storedSource],
        timestamp: new Date('2026-08-21T09:00:00'),
      }}
    />,
  )
  await user.click(screen.getByRole('button', { name: 'Belege anzeigen' }))
  const drawer = await screen.findByRole('dialog', { name: 'Belege dieser Antwort' })
  return within(drawer).getByTestId('evidence-doc')
}

/** ADR-0044: with metadata switched off a Beleg carries no filter mark; the row never shows
 *  metadata values, with or without the switch (guidelines 5.8). */
describe('SourceEvidenceDrawer without metadata', () => {
  beforeEach(() => {
    useAuthStore.setState({ features: { metadata: false } })
  })

  afterEach(() => {
    useAuthStore.setState({ features: {} })
  })

  it('shows the file name only, without metadata values or the filter mark', async () => {
    const doc = await openDrawer()

    expect(within(doc).getByText('2026-03-12_da.pdf')).toBeVisible()
    expect(within(doc).queryByText(/Dienstanweisung/)).not.toBeInTheDocument()
    expect(within(doc).queryByText(/poststelle@example\.org/)).not.toBeInTheDocument()
    expect(within(doc).queryByTestId('source-filter-match')).not.toBeInTheDocument()
  })

  it('shows the filter mark again once metadata is on, still without metadata values', async () => {
    useAuthStore.setState({ features: { metadata: true } })
    const doc = await openDrawer()

    expect(within(doc).getByTestId('source-filter-match')).toHaveTextContent('ohne Angabe')
    expect(within(doc).queryByText(/Dienstanweisung/)).not.toBeInTheDocument()
    expect(within(doc).queryByText(/poststelle@example\.org/)).not.toBeInTheDocument()
  })
})
