import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { IndexingRunResponse } from '@/shared/api/types'
import { renderWithProviders, waitForDialogClosed } from '../../../test/test-utils'
import LibraryRunList from './LibraryRunList'

function run(id: string, overrides: Partial<IndexingRunResponse> = {}): IndexingRunResponse {
  return {
    id,
    status: 'COMPLETED',
    triggeredBy: 'SCHEDULED',
    runMode: 'INCREMENTAL',
    documentCount: 10,
    totalDocuments: 10,
    documentsSkipped: 0,
    documentsFailed: 0,
    documentsIndexedTotal: 10,
    message: null,
    failureCategory: null,
    incomplete: false,
    startedAt: '2026-10-04T10:00:00Z',
    completedAt: '2026-10-04T10:05:00Z',
    events: [],
    eventsTruncatedCount: 0,
    ...overrides,
  } as IndexingRunResponse
}

const failed = (id: string) =>
  run(id, { status: 'FAILED', message: 'Quelle nicht erreichbar', completedAt: null })

function renderList(runs: IndexingRunResponse[], props: { signsIn?: boolean } = {}) {
  return renderWithProviders(
    <LibraryRunList runs={runs} showMode={false} running={null} quotaRemedy="" {...props} />,
    { withRouter: true, initialRoute: '/libraries/library-1' },
  )
}

function rows() {
  return within(screen.getByRole('list', { name: 'Läufe' })).getAllByRole('button')
}

async function openRun(user: ReturnType<typeof userEvent.setup>, word: string) {
  const row = screen.getByRole('button', { name: new RegExp(`^${word}`) })
  await user.click(row)
  expect(row).toHaveAttribute('aria-expanded', 'true')
  return document.getElementById(row.getAttribute('aria-controls') ?? '')!
}

describe('LibraryRunList', () => {
  describe('„Nur mit Fehlern“', () => {
    it('offers no filter below five runs', () => {
      renderList([failed('r1'), run('r2'), run('r3'), run('r4')])

      expect(screen.queryByRole('group', { name: 'Läufe filtern' })).not.toBeInTheDocument()
    })

    it('offers no filter when it would not shorten the list', () => {
      const { unmount } = renderList(['r1', 'r2', 'r3', 'r4', 'r5'].map((id) => run(id)))
      expect(screen.queryByRole('group', { name: 'Läufe filtern' })).not.toBeInTheDocument()
      unmount()

      renderList(['r1', 'r2', 'r3', 'r4', 'r5'].map(failed))
      expect(screen.queryByRole('group', { name: 'Läufe filtern' })).not.toBeInTheDocument()
    })

    it('filters a list of five runs to the runs with a problem and back', async () => {
      const user = userEvent.setup()
      renderList([
        failed('r1'),
        run('r2'),
        run('r3', { documentsFailed: 2 }),
        run('r4', { status: 'RUNNING', completedAt: null }),
        run('r5'),
      ])

      const filter = screen.getByRole('group', { name: 'Läufe filtern' })
      expect(within(filter).getByRole('button', { name: 'Alle' })).toHaveAttribute(
        'aria-pressed',
        'true',
      )
      expect(rows()).toHaveLength(5)

      await user.click(within(filter).getByRole('button', { name: 'Nur mit Fehlern (2)' }))
      expect(rows().map((row) => row.textContent)).toEqual([
        expect.stringMatching(/^Fehlgeschlagen/),
        expect.stringMatching(/^Teilweise fehlgeschlagen/),
      ])

      await user.click(within(filter).getByRole('button', { name: 'Alle' }))
      expect(rows()).toHaveLength(5)
    })
  })

  describe('„Verbindung ändern“', () => {
    it.each([
      ['CREDENTIALS_REJECTED', 'Anmeldung abgelehnt'],
      ['EXPIRED', 'Zustimmung abgelaufen'],
    ] as const)(
      'leads a run failed at the connection (%s) to the row Verbindung',
      async (failureCategory, word) => {
        const user = userEvent.setup()
        renderList([run('r1', { status: 'FAILED', failureCategory })])

        const detail = await openRun(user, word)
        const link = within(detail).getByRole('link', { name: 'Verbindung ändern' })
        const href = new URL(link.getAttribute('href') ?? '', 'http://localhost')
        expect(href.searchParams.get('tab')).toBe('quelle')
        expect(href.hash).toBe('#verbindung')
      },
    )

    it('leads a source without sign-in that is not reachable to the row Verbindung', async () => {
      const user = userEvent.setup()
      renderList([failed('r1')], { signsIn: false })

      const detail = await openRun(user, 'Nicht erreichbar')
      expect(within(detail).getByRole('link', { name: 'Verbindung ändern' })).toBeInTheDocument()
    })

    it('offers no change of connection for a run that failed for another reason', async () => {
      const user = userEvent.setup()
      renderList([failed('r1')])

      const detail = await openRun(user, 'Fehlgeschlagen')
      expect(within(detail).queryByRole('link', { name: 'Verbindung ändern' })).toBeNull()
      expect(detail).toHaveTextContent('Quelle nicht erreichbar')
    })
  })

  it('closes the dialog of „alle n anzeigen“ through „Schließen“', async () => {
    const user = userEvent.setup()
    const events = Array.from({ length: 6 }, (_, index) => ({
      category: 'REJECTED' as const,
      reference: `Ablage/Akte ${index + 1}.pdf`,
      message: 'Datei konnte nicht gelesen werden',
    }))
    renderList([run('r1', { documentsFailed: 6, events })])

    const detail = await openRun(user, 'Teilweise fehlgeschlagen')
    await user.click(within(detail).getByRole('button', { name: 'alle 6 anzeigen' }))
    const dialog = await screen.findByRole('dialog', { name: /· 6$/ })
    expect(within(dialog).getByText('Ablage/Akte 6.pdf')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Schließen' }))
    await waitForDialogClosed()
  })
})
