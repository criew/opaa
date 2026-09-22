import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
import MyGroupsSection from './MyGroupsSection'
import { useAuthStore } from '../../stores/authStore'
import { useGroupStore } from '../../stores/groupStore'
import type { Capability, GroupListResponse, GroupResponse } from '../../types/api'

const {
  mockGetMyStewardedGroups,
  mockGetGroup,
  mockSetGroupRelease,
  mockSetGroupProtection,
  mockAppointGroupSteward,
  mockDismissGroupSteward,
  mockGetMyCapabilities,
} = vi.hoisted(() => ({
  mockGetMyStewardedGroups: vi.fn(),
  mockGetGroup: vi.fn(),
  mockSetGroupRelease: vi.fn(),
  mockSetGroupProtection: vi.fn(),
  mockAppointGroupSteward: vi.fn(),
  mockDismissGroupSteward: vi.fn(),
  mockGetMyCapabilities: vi.fn(),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    getMyStewardedGroups: mockGetMyStewardedGroups,
    getGroup: mockGetGroup,
    setGroupRelease: mockSetGroupRelease,
    setGroupProtection: mockSetGroupProtection,
    appointGroupSteward: mockAppointGroupSteward,
    dismissGroupSteward: mockDismissGroupSteward,
    getMyCapabilities: mockGetMyCapabilities,
  }
})

const OWN_USER_ID = 'user-self'

const group: GroupListResponse = {
  id: 'group-phoenix',
  name: 'Projektbeteiligte Phoenix',
  description: 'Ad hoc',
  kind: 'AD_HOC',
  externalId: null,
  origin: 'INTERNAL',
  provider: null,
  sourcePath: null,
  parentGroupId: null,
  memberCount: 1,
  dissolved: false,
  releasedForUse: false,
  protectedGroup: false,
  stewards: [
    {
      userId: OWN_USER_ID,
      displayName: 'Rita Sachbearbeitung',
      appointedAt: '2026-03-01T10:00:00Z',
    },
  ],
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const details: GroupResponse = {
  ...group,
  members: [{ userId: 'u1', displayName: 'Alice', createdAt: '2026-03-01T10:00:00Z' }],
}

function withGroups(groups: GroupListResponse[], capabilities: Capability[]) {
  mockGetMyStewardedGroups.mockResolvedValue(groups)
  mockGetGroup.mockImplementation(async (groupId: string) =>
    groupId === details.id ? details : { ...details, id: groupId },
  )
  mockGetMyCapabilities.mockResolvedValue(capabilities)
  useAuthStore.setState({
    user: {
      id: OWN_USER_ID,
      email: 'rita@opaa.local',
      displayName: 'Rita Sachbearbeitung',
      systemRole: 'USER',
    },
  })
}

describe('MyGroupsSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useGroupStore.getState().reset()
  })

  it('lädt die eigenen Verantwortlichkeiten und zeigt ihren Freigabezustand', async () => {
    withGroups([group], ['CREATE_INTERNAL_GROUP'])

    renderWithProviders(<MyGroupsSection />, { withRouter: true })

    expect(await screen.findByText('Projektbeteiligte Phoenix')).toBeInTheDocument()
    expect(screen.getByText('nicht freigegeben')).toBeInTheDocument()
    await waitFor(() => expect(mockGetMyStewardedGroups).toHaveBeenCalled())
  })

  it('erklärt das fehlende Anlegerecht, statt die Schaltfläche zu verstecken', async () => {
    withGroups([], ['CREATE_SPACE'])

    renderWithProviders(<MyGroupsSection />, { withRouter: true })

    expect(await screen.findByText(/Ihnen fehlt das Anlegerecht/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /neue gruppe/i })).toBeDisabled()
  })

  it('gibt die Gruppe zur Verwendung frei', async () => {
    withGroups([group], ['CREATE_INTERNAL_GROUP'])
    mockSetGroupRelease.mockResolvedValue(details)
    renderWithProviders(<MyGroupsSection />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))
    await user.click(await screen.findByRole('switch', { name: /zur verwendung freigeben/i }))

    await waitFor(() => expect(mockSetGroupRelease).toHaveBeenCalledWith('group-phoenix', true))
  })

  it('setzt das Schutzkennzeichen', async () => {
    withGroups([group], ['CREATE_INTERNAL_GROUP'])
    mockSetGroupProtection.mockResolvedValue(details)
    renderWithProviders(<MyGroupsSection />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))
    await user.click(
      await screen.findByRole('switch', { name: /als geschützte gruppe kennzeichnen/i }),
    )

    await waitFor(() => expect(mockSetGroupProtection).toHaveBeenCalledWith('group-phoenix', true))
  })

  /**
   * ADR-0036, Entscheidung 4: die Abgabe ist ein eigener Schritt - erst die Nachfolge, dann der
   * Rücktritt. Solange man allein verantwortlich ist, gibt es nichts abzugeben.
   */
  it('lässt die Verantwortung erst abgeben, wenn eine zweite Person benannt ist', async () => {
    withGroups([group], ['CREATE_INTERNAL_GROUP'])
    renderWithProviders(<MyGroupsSection />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))

    expect(await screen.findByRole('button', { name: /verantwortung abgeben/i })).toBeDisabled()
    expect(screen.getByText(/letzte verantwortliche person/i)).toBeInTheDocument()
  })

  it('gibt die Verantwortung nach Rückfrage ab, sobald eine Nachfolge benannt ist', async () => {
    const withSuccessor: GroupListResponse = {
      ...group,
      stewards: [
        ...group.stewards,
        { userId: 'user-next', displayName: 'Nachfolge', appointedAt: '2026-03-02T10:00:00Z' },
      ],
    }
    withGroups([withSuccessor], ['CREATE_INTERNAL_GROUP'])
    mockGetGroup.mockResolvedValue({ ...details, stewards: withSuccessor.stewards })
    mockDismissGroupSteward.mockResolvedValue(undefined)
    renderWithProviders(<MyGroupsSection />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))
    await user.click(await screen.findByRole('button', { name: /verantwortung abgeben/i }))
    await answerConfirm(
      user,
      'Verantwortung für „Projektbeteiligte Phoenix“ abgeben?',
      'Verantwortung abgeben',
    )

    await waitFor(() =>
      expect(mockDismissGroupSteward).toHaveBeenCalledWith('group-phoenix', OWN_USER_ID),
    )
  })

  /**
   * GroupService#dismissSteward erlaubt der Systemverwaltung genau das, was der letzten
   * verantwortlichen Person selbst verwehrt ist: ein ausscheidendes Konto muss lösbar sein
   * (group_stewards.user_id ist RESTRICT). Über die Oberfläche war dieser Ausgang unerreichbar.
   */
  it('lässt die Systemverwaltung die letzte verantwortliche Person entlassen', async () => {
    withGroups([group], ['CREATE_INTERNAL_GROUP'])
    useAuthStore.setState({
      user: {
        id: 'user-admin',
        email: 'admin@opaa.local',
        displayName: 'Systemverwaltung',
        systemRole: 'SYSTEM_ADMIN',
      },
    })
    renderWithProviders(<MyGroupsSection />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))

    expect(await screen.findByRole('button', { name: /entlassen/i })).toBeEnabled()
    expect(screen.getByText(/Als Systemverwaltung können Sie sie entlassen/)).toBeInTheDocument()
  })

  /**
   * Nach der eigenen Abgabe darf die Person die Gruppe nicht mehr lesen; ein Nachladen der Details
   * antwortet 404. Die gelungene Handlung darf dafür kein Fehlerband zeigen.
   */
  it('meldet keinen Fehler, wenn die Gruppe nach der eigenen Abgabe nicht mehr lesbar ist', async () => {
    const withSuccessor: GroupListResponse = {
      ...group,
      stewards: [
        ...group.stewards,
        { userId: 'user-next', displayName: 'Nachfolge', appointedAt: '2026-03-02T10:00:00Z' },
      ],
    }
    withGroups([withSuccessor], ['CREATE_INTERNAL_GROUP'])
    mockGetGroup.mockResolvedValueOnce({ ...details, stewards: withSuccessor.stewards })
    mockGetMyStewardedGroups.mockResolvedValueOnce([withSuccessor]).mockResolvedValue([])
    mockDismissGroupSteward.mockResolvedValue(undefined)
    mockGetGroup.mockRejectedValue(new Error('Gruppe nicht gefunden'))
    renderWithProviders(<MyGroupsSection />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))
    await user.click(await screen.findByRole('button', { name: /verantwortung abgeben/i }))
    await answerConfirm(
      user,
      'Verantwortung für „Projektbeteiligte Phoenix“ abgeben?',
      'Verantwortung abgeben',
    )

    expect(await screen.findByText(/für keine gruppe verantwortlich/i)).toBeInTheDocument()
    expect(screen.queryByText('Gruppe nicht gefunden')).not.toBeInTheDocument()
    expect(useGroupStore.getState().error).toBeNull()
  })

  it('zeigt einen leeren Zustand, wenn man für keine Gruppe verantwortlich ist', async () => {
    withGroups([], ['CREATE_INTERNAL_GROUP'])

    renderWithProviders(<MyGroupsSection />, { withRouter: true })

    expect(await screen.findByText(/für keine gruppe verantwortlich/i)).toBeInTheDocument()
  })
})
