import { act, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../mocks/server'
import { renderWithProviders } from '../../../test/test-utils'
import type { DormantSourceConnection } from '../../../types/api'
import DormantSourceConnectionsSection from './DormantSourceConnectionsSection'

const DORMANT = '/api/v1/admin/source-connections/dormant'

function serve(entries: DormantSourceConnection[]) {
  server.use(http.get(DORMANT, () => HttpResponse.json(entries)))
}

describe('DormantSourceConnectionsSection (#2169)', () => {
  it('shows nothing while no source connection rests', async () => {
    let asked = false
    server.use(
      http.get(DORMANT, () => {
        asked = true
        return HttpResponse.json([])
      }),
    )
    const { container } = renderWithProviders(<DormantSourceConnectionsSection />, {
      withRouter: true,
    })

    await waitFor(() => expect(asked).toBe(true))
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)))
    expect(screen.queryByText('Ruhende Quellverbindungen')).not.toBeInTheDocument()
    expect(container).not.toHaveTextContent('Quellverbindung')
  })

  it('lists every resting source connection with its reason, end and who answers for it', async () => {
    serve([
      {
        libraryId: 'lib-1',
        libraryName: 'Bauamt',
        profileId: 'p-1',
        profileName: 'Dropbox Bauamt',
        accountLabel: 'svc@bauamt.example',
        reason: 'EXPIRED',
        endedCause: 'PROVIDER_REJECTED',
        endedAt: '2026-10-01T08:00:00Z',
        responsible: { type: 'GROUP', id: 'g-1', name: 'Bauamt Verwaltung' },
      },
      {
        libraryId: 'lib-2',
        libraryName: 'Archiv',
        profileId: null,
        profileName: null,
        accountLabel: null,
        reason: 'ACCESS_REMOVED',
        endedCause: 'PROFILE_DELETED',
        endedAt: '2026-09-30T08:00:00Z',
        responsible: null,
      },
    ])
    renderWithProviders(<DormantSourceConnectionsSection />, { withRouter: true })

    expect(
      await screen.findByRole('heading', { name: 'Ruhende Quellverbindungen' }),
    ).toBeInTheDocument()
    const bauamt = screen.getByRole('row', { name: /Bauamt/ })
    expect(within(bauamt).getByRole('link', { name: 'Bauamt' })).toHaveAttribute(
      'href',
      '/libraries/lib-1?tab=quelle',
    )
    expect(bauamt).toHaveTextContent('Dropbox Bauamt')
    expect(bauamt).toHaveTextContent('svc@bauamt.example')
    expect(bauamt).toHaveTextContent('Abgelaufen')
    expect(bauamt).toHaveTextContent('Vom Anbieter abgelehnt')
    expect(bauamt).toHaveTextContent('Gruppe „Bauamt Verwaltung“')

    const archiv = screen.getByRole('row', { name: /Archiv/ })
    expect(archiv).toHaveTextContent('Zugang entfernt')
    expect(archiv).toHaveTextContent('Zugang gelöscht')
  })

  it('names a failed load instead of hiding it', async () => {
    server.use(
      http.get(DORMANT, () =>
        HttpResponse.json(
          { error: 'Keine Berechtigung', status: 403, timestamp: '2026-10-05T00:00:00Z' },
          { status: 403 },
        ),
      ),
    )
    renderWithProviders(<DormantSourceConnectionsSection />, { withRouter: true })

    expect(await screen.findByRole('alert')).toHaveTextContent('Keine Berechtigung')
  })
})
