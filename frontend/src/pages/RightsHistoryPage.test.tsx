import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import RightsHistoryPage from './RightsHistoryPage'
import { useAuthStore } from '../stores/authStore'
import type { AccessAsOfPage } from '../types/api'
import type { SystemRole } from '../types/auth'

const { mockGetAccessAsOf } = vi.hoisted(() => ({ mockGetAccessAsOf: vi.fn() }))

vi.mock('../services/rightsHistoryApi', () => ({ getAccessAsOf: mockGetAccessAsOf }))

const answer: AccessAsOfPage = {
  objectType: 'KNOWLEDGE_LIBRARY',
  objectId: '2f7b1a2e-0000-4000-8000-000000000001',
  objectName: 'Personalvorgänge',
  from: '2026-03-01T00:00:00Z',
  to: '2026-03-31T00:00:00Z',
  retentionCutoff: '2023-04-01T00:00:00Z',
  beyondRetention: false,
  sourcesNotCovered: ['SYSTEM_ROLE', 'OWNERSHIP', 'CAPABILITY', 'ACCOUNT_STATE'],
  entries: [
    {
      basis: 'GROUP_GRANT',
      userId: 'user-1',
      userName: 'Frau Sommer',
      groupId: 'group-1',
      groupName: 'Referat 50',
      assetRole: 'VIEWER',
      validFrom: '2026-03-02T00:00:00Z',
      validTo: null,
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
}

function signInAs(systemRole: SystemRole): void {
  useAuthStore.setState({
    user: { id: 'me', email: null, displayName: 'Revision', systemRole },
    isAuthenticated: true,
  })
}

async function fillAndSubmit(): Promise<void> {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Kennung des Objekts'), 'library-1')
  await user.type(screen.getByLabelText('Von'), '2026-03-01T00:00')
  await user.type(screen.getByLabelText('Bis'), '2026-03-31T00:00')
  await user.type(screen.getByLabelText('Anlass'), 'Beschwerde 4711')
  await user.click(screen.getByRole('button', { name: 'Auskunft erstellen' }))
}

describe('RightsHistoryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('nennt Person, Grundlage, Gruppe und Zeitraum eines Zugriffs', async () => {
    signInAs('AUDITOR')
    mockGetAccessAsOf.mockResolvedValue(answer)

    renderWithProviders(<RightsHistoryPage />)
    await fillAndSubmit()

    expect(await screen.findByText('Frau Sommer')).toBeInTheDocument()
    expect(screen.getByText('Freigabe an eine Gruppe')).toBeInTheDocument()
    expect(screen.getByText('Referat 50')).toBeInTheDocument()
    expect(screen.getByText('noch wirksam')).toBeInTheDocument()
  })

  /** #1833: eine Luecke vor der Aufbewahrungsgrenze ist kein Freispruch, und die Seite sagt das. */
  it('weist darauf hin, wenn der Zeitraum vor der Aufbewahrungsgrenze beginnt', async () => {
    signInAs('AUDITOR')
    mockGetAccessAsOf.mockResolvedValue({ ...answer, beyondRetention: true, entries: [] })

    renderWithProviders(<RightsHistoryPage />)
    await fillAndSubmit()

    expect(await screen.findByText(/nicht mehr nachgewiesen/)).toBeInTheDocument()
  })

  it('zeigt die Abweisung eines zu weiten Zeitraums als Fehler an', async () => {
    signInAs('AUDITOR')
    mockGetAccessAsOf.mockRejectedValue(new Error('Der Zeitraum ist zu weit gefasst'))

    renderWithProviders(<RightsHistoryPage />)
    await fillAndSubmit()

    expect(await screen.findByText(/zu weit gefasst/)).toBeInTheDocument()
  })

  /** Sollte (7): eine leere Liste darf nicht als "niemand hatte Zugriff" gelesen werden. */
  it('nennt die Rechtequellen, die die Auskunft noch nicht abdeckt', async () => {
    signInAs('AUDITOR')
    mockGetAccessAsOf.mockResolvedValue({ ...answer, entries: [], totalElements: 0 })

    renderWithProviders(<RightsHistoryPage />)
    await fillAndSubmit()

    expect(await screen.findByText(/Systemrolle/)).toBeInTheDocument()
    expect(screen.getByText(/kein Beleg dafür/)).toBeInTheDocument()
  })

  /** Sollte (8): Seite 2 muss erreichbar sein, sonst sieht eine gekürzte Liste vollständig aus. */
  it('blättert zur nächsten Seite', async () => {
    const user = userEvent.setup()
    signInAs('AUDITOR')
    mockGetAccessAsOf.mockResolvedValue({ ...answer, totalElements: 120, totalPages: 3 })

    renderWithProviders(<RightsHistoryPage />)
    await fillAndSubmit()

    expect(await screen.findByText('Seite 1 von 3')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(mockGetAccessAsOf).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
  })

  it('ist für ein Konto ohne die Revisionsrolle nicht bedienbar', () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<RightsHistoryPage />)

    expect(
      screen.getByText('Die Stichtagsauskunft ist der Revision vorbehalten.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Auskunft erstellen' })).not.toBeInTheDocument()
  })
})
