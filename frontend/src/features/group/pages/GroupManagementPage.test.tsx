import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../../test/test-utils'
import GroupManagementPage from './GroupManagementPage'
import { useGroupStore } from '../stores/groupStore'
import { useAuthStore } from '@/shared/session/authStore'
import { useGroupAdminListStore } from '../stores/groupAdminListStore'
import { useNotificationStore } from '@/shared/stores/notificationStore'
import type { GroupListResponse, GroupResponse } from '@/shared/api/types'
import type { GroupListQuery } from '../api/groupAdminApi'

const {
  mockListGroupPage,
  mockListGroupMembers,
  mockFetchedDetails,
  listed,
  mockCreateGroup,
  mockUpdateGroup,
  mockSetGroupRelease,
  mockDeleteGroup,
  mockRemoveGroupMember,
  mockSetGroupProtection,
  mockSetGroupDeactivation,
  deactivationPreview,
} = vi.hoisted(() => ({
  mockListGroupPage: vi.fn(),
  mockListGroupMembers: vi.fn(),
  /** What the server knows per group; `getGroup` withholds the members from the administration. */
  mockFetchedDetails: {} as Record<string, GroupResponse>,
  /** The groups the server knows for the current request. */
  listed: { groups: [] as GroupListResponse[] },
  mockCreateGroup: vi.fn(async () => ({}) as GroupResponse),
  mockUpdateGroup: vi.fn(async () => ({}) as GroupResponse),
  mockSetGroupRelease: vi.fn(async () => ({}) as GroupResponse),
  mockDeleteGroup: vi.fn(async () => undefined),
  mockRemoveGroupMember: vi.fn(async () => undefined),
  mockSetGroupProtection: vi.fn(async () => ({}) as GroupResponse),
  mockSetGroupDeactivation: vi.fn(async () => ({}) as GroupResponse),
  /** What the server counts for the next confirmation of a deactivation. */
  deactivationPreview: { blockedReason: null as string | null },
}))

vi.mock('../api/groupAdminApi', async () => {
  const actual =
    await vi.importActual<typeof import('../api/groupAdminApi')>('../api/groupAdminApi')
  return {
    ...actual,
    listGroupPage: vi.fn(async (query: GroupListQuery) => {
      mockListGroupPage(query)
      const items = listed.groups.filter((group) => !query.origin || group.origin === query.origin)
      return { items, total: items.length, page: 0, size: 25 }
    }),
  }
})

vi.mock('../../succession/api/permissionTransferApi', async () => {
  const actual = await vi.importActual<typeof import('../../succession/api/permissionTransferApi')>(
    '../../succession/api/permissionTransferApi',
  )
  return { ...actual, getGroupEffects: vi.fn(async () => []) }
})

vi.mock('@/features/principal/api/userApi', async () => {
  const actual = await vi.importActual<typeof import('@/features/principal/api/userApi')>(
    '@/features/principal/api/userApi',
  )
  return {
    ...actual,
    getUsers: vi.fn(async () => []),
  }
})

vi.mock('../api/groupApi', async () => {
  const actual = await vi.importActual<typeof import('../api/groupApi')>('../api/groupApi')
  return {
    ...actual,
    getGroups: vi.fn(async () => listed.groups),
    getGroup: vi.fn(async (groupId: string) => {
      const details = mockFetchedDetails[groupId]
      return details && { ...details, members: null }
    }),
    listGroupMembers: vi.fn(async (groupId: string) => {
      mockListGroupMembers(groupId)
      return mockFetchedDetails[groupId]?.members ?? []
    }),
    createGroup: mockCreateGroup,
    updateGroup: mockUpdateGroup,
    setGroupRelease: mockSetGroupRelease,
    deleteGroup: mockDeleteGroup,
    removeGroupMember: mockRemoveGroupMember,
    setGroupProtection: mockSetGroupProtection,
    setGroupDeactivation: mockSetGroupDeactivation,
    getGroupDeactivationPreview: vi.fn(async (groupId: string) => ({
      groupId,
      deactivated: listed.groups.find((group) => group.id === groupId)?.deactivated ?? false,
      assetGrants: 3,
      grantedAssets: 2,
      spaceMemberships: 1,
      spaces: 1,
      capabilities: 0,
      activeMembers: 12,
      blockedReason: deactivationPreview.blockedReason,
    })),
  }
})

const adHocGroup: GroupListResponse = {
  id: 'group-phoenix',
  name: 'Projektbeteiligte Phoenix',
  description: 'Ad hoc',
  kind: 'AD_HOC',
  externalId: null,
  origin: 'INTERNAL',
  state: 'NOT_RELEASED',
  provider: null,
  sourcePath: null,
  parentGroupId: null,
  memberCount: 1,
  dissolved: false,
  deactivated: false,
  releasedForUse: false,
  protectedGroup: false,
  stewards: [{ userId: 'mock-user-id', displayName: 'Admin', appointedAt: '2026-03-01T10:00:00Z' }],
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const orgUnitGroup: GroupListResponse = {
  id: 'group-referat-50',
  name: 'Referat 50',
  description: 'Directory-synced',
  kind: 'ORG_UNIT',
  externalId: 'directory-guid',
  origin: 'PROVIDER',
  state: 'ACTIVE',
  provider: {
    id: 'oidc-provider-beschaeftigte',
    displayName: 'Verzeichnisdienst',
    external: false,
    enabled: true,
    groupMechanism: 'TOKEN',
  },
  sourcePath: '/Haus A/Referat 50',
  parentGroupId: null,
  memberCount: 1,
  dissolved: false,
  deactivated: false,
  releasedForUse: true,
  protectedGroup: false,
  stewards: [],
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const adHocDetails: GroupResponse = {
  ...adHocGroup,
  members: [{ userId: 'u1', displayName: 'Alice', createdAt: '2026-03-01T10:00:00Z' }],
}

const orgUnitDetails: GroupResponse = {
  ...orgUnitGroup,
  members: [{ userId: 'u2', displayName: 'Bob', createdAt: '2026-03-01T10:00:00Z' }],
}

/** jsdom has no matchMedia; the table renders only on a desktop viewport (guidelines 5.3). */
function desktopMatchMedia(query: string): MediaQueryList {
  return {
    matches: query.includes('min-width'),
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  } as unknown as MediaQueryList
}

function serve(groups: GroupListResponse[]) {
  listed.groups = groups
}

function renderPage() {
  return renderWithProviders(<GroupManagementPage />, { withRouter: true })
}

async function openRowMenu(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('button', { name: `Aktionen für „${name}“` }))
  return screen.findByRole('menu', { name: `Aktionen für „${name}“` })
}

describe('GroupManagementPage', () => {
  const originalMatchMedia = window.matchMedia
  beforeAll(() => {
    window.matchMedia = desktopMatchMedia
  })
  afterAll(() => {
    window.matchMedia = originalMatchMedia
  })

  beforeEach(() => {
    vi.clearAllMocks()
    for (const key of Object.keys(mockFetchedDetails)) delete mockFetchedDetails[key]
    useGroupStore.getState().reset()
    useGroupAdminListStore.getState().reset()
    useNotificationStore.getState().reset()
    deactivationPreview.blockedReason = null
  })

  // The groups as a table like the accounts - origin, members, state; a column of its own
  // „Art" does not exist, the info icon behind the provider explains the origin
  it('lists the groups as a table with origin, member count and state', async () => {
    serve([adHocGroup, orgUnitGroup])
    renderPage()

    const table = await screen.findByRole('table', { name: 'Gruppen' })
    const phoenix = within(table).getByText('Projektbeteiligte Phoenix').closest('tr')!
    expect(within(phoenix).getByText('Intern')).toBeInTheDocument()
    expect(within(phoenix).queryByRole('img', { name: /^Herkunft:/ })).not.toBeInTheDocument()
    expect(within(phoenix).getByText('Nicht freigegeben')).toBeInTheDocument()
    expect(
      within(phoenix).getByRole('img', { name: /^Grund: Noch nicht zur Verwendung/ }),
    ).toBeInTheDocument()
    const referat = within(table).getByText('Referat 50').closest('tr')!
    expect(within(referat).getByText('Verzeichnisdienst')).toBeInTheDocument()
    expect(
      within(referat).getByRole('img', {
        name: /^Herkunft: OPAA liest Gruppen und Mitglieder von „Verzeichnisdienst“ selbst aus/,
      }),
    ).toBeInTheDocument()
    expect(within(table).queryByRole('columnheader', { name: /^Art/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Art' })).not.toBeInTheDocument()
    expect(within(referat).getByText('/Haus A/Referat 50')).toBeInTheDocument()
    expect(within(referat).getByText('Aktiv')).toBeInTheDocument()
    expect(screen.getByText('2 Gruppen · Seite 1 von 1')).toBeInTheDocument()
    // the member list is never part of the table
    expect(mockListGroupMembers).not.toHaveBeenCalled()
  })

  // ADR-0036, Entscheidung 3: a waiting plan stays loud - its age is in the link itself,
  // the details in the popover; a hint box above the list is gone
  it('names a waiting directory plan in a warning link with its age', async () => {
    serve([adHocGroup])
    renderPage()
    const user = userEvent.setup()

    const hint = await screen.findByRole('button', {
      name: /^Verzeichnisplan wartet seit \d+ Tagen auf Entscheidung$/,
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await user.click(hint)
    const popup = await screen.findByRole('dialog', {
      name: 'Verzeichnisabgleich wartet auf Ihre Entscheidung',
    })
    expect(popup).toHaveTextContent(/bleibt der bisherige Stand in Kraft/)
    expect(popup).toHaveTextContent(/Mitgliedschaften würden entzogen/)
    expect(within(popup).getByRole('link', { name: 'Zum Verzeichnisabgleich' })).toHaveAttribute(
      'href',
      '/admin/directory-sync',
    )
  })

  it('shows an empty state when no group matches', async () => {
    serve([])
    renderPage()

    expect(
      await screen.findByText('Keine Gruppe entspricht den gewählten Filtern.'),
    ).toBeInTheDocument()
  })

  it('filters by origin and sorts on the server', async () => {
    serve([adHocGroup, orgUnitGroup])
    renderPage()
    const user = userEvent.setup()
    await screen.findByRole('table', { name: 'Gruppen' })

    await user.click(screen.getByRole('combobox', { name: 'Herkunft' }))
    await user.click(await screen.findByRole('option', { name: 'Intern' }))
    await waitFor(() =>
      expect(mockListGroupPage).toHaveBeenLastCalledWith(
        expect.objectContaining({ origin: 'INTERNAL', page: 0 }),
      ),
    )
    await waitFor(() => expect(screen.queryByText('Referat 50')).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: /Mitglieder/ }))
    await waitFor(() =>
      expect(mockListGroupPage).toHaveBeenLastCalledWith(
        expect.objectContaining({ sort: 'memberCount', direction: 'asc' }),
      ),
    )
  })

  it('edits name, description and release of an internal group in one save', async () => {
    serve([adHocGroup])
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Projektbeteiligte Phoenix')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
    const dialog = await screen.findByRole('dialog', {
      name: '„Projektbeteiligte Phoenix“ bearbeiten',
    })
    expect(within(dialog).getByRole('button', { name: 'Speichern' })).toBeDisabled()
    expect(within(dialog).getByText('Verantwortlich')).toBeInTheDocument()

    const name = within(dialog).getByLabelText('Name der Gruppe')
    await user.clear(name)
    await user.type(name, 'Phoenix Kernteam')
    await user.click(within(dialog).getByRole('switch', { name: 'Zur Verwendung freigegeben' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() =>
      expect(mockUpdateGroup).toHaveBeenCalledWith('group-phoenix', 'Phoenix Kernteam', 'Ad hoc'),
    )
    expect(mockSetGroupRelease).toHaveBeenCalledWith('group-phoenix', true)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  // ADR-0025, Entscheidung 4: the source maintains name and members; the explanation names
  // the actual source
  it('explains a provider group and offers only its protection', async () => {
    const tokenGroup: GroupListResponse = {
      ...orgUnitGroup,
      id: 'group-token-fachbereich',
      name: 'Fachbereich 3',
      kind: 'IDENTITY_PROVIDER',
    }
    serve([tokenGroup])
    renderPage()
    const user = userEvent.setup()

    const origin = await screen.findByRole('img', {
      name: /^Herkunft: OPAA erfährt die Mitglieder dieser Gruppe nur bei der Anmeldung/,
    })
    await user.hover(origin)
    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      /erst bei der nächsten Anmeldung dieser Person/,
    )
    await user.unhover(origin)

    const menu = await openRowMenu(user, 'Fachbereich 3')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/stammt aus dem Identitätsanbieter/)).toBeInTheDocument()
    expect(within(dialog).getByText('Verzeichnisdienst · /Haus A/Referat 50')).toBeInTheDocument()
    expect(within(dialog).queryByLabelText('Name der Gruppe')).not.toBeInTheDocument()
    expect(within(dialog).getByRole('switch', { name: 'Geschützte Gruppe' })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Speichern' })).toBeDisabled()
  })

  // ADR-0036, Entscheidung 9: the system administration decides about the protection - also for a
  // group from a provider whose name and members the source maintains
  it('protects a provider group from the edit dialog', async () => {
    serve([orgUnitGroup])
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Referat 50')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('switch', { name: 'Geschützte Gruppe' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() =>
      expect(mockSetGroupProtection).toHaveBeenCalledWith('group-referat-50', true),
    )
  })

  // ADR-0036, Entscheidung 4/9: retrieving the member list is the audit event - it happens
  // only when the dialog opens, never with the table, and the dialog names the logging.
  it('loads the member list of an internal group on opening the dialog', async () => {
    serve([adHocGroup])
    mockFetchedDetails['group-phoenix'] = adHocDetails
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Projektbeteiligte Phoenix')
    expect(mockListGroupMembers).not.toHaveBeenCalled()
    await user.click(within(menu).getByRole('menuitem', { name: 'Mitglieder' }))
    const dialog = await screen.findByRole('dialog', {
      name: /^Mitglieder von „Projektbeteiligte Phoenix“/,
    })

    await waitFor(() => expect(mockListGroupMembers).toHaveBeenCalledWith('group-phoenix'))
    const table = await within(dialog).findByRole('table', { name: 'Mitglieder' })
    expect(within(table).getByText('Alice')).toBeInTheDocument()
    expect(dialog).toHaveAccessibleName('Mitglieder von „Projektbeteiligte Phoenix“ · 1 Mitglied')
    await user.click(within(table).getByRole('button', { name: 'Alice entfernen' }))
    await waitFor(() => expect(mockRemoveGroupMember).toHaveBeenCalledWith('group-phoenix', 'u1'))
  })

  // The dialog adds with the same person search, verb and form row as „Meine Gruppen“.
  it('adds members of an internal group with „Hinzufügen“ and names a missing choice at the field', async () => {
    serve([adHocGroup])
    mockFetchedDetails['group-phoenix'] = adHocDetails
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Projektbeteiligte Phoenix')
    await user.click(within(menu).getByRole('menuitem', { name: 'Mitglieder' }))
    const dialog = await screen.findByRole('dialog', {
      name: /^Mitglieder von „Projektbeteiligte Phoenix“/,
    })
    await within(dialog).findByRole('table', { name: 'Mitglieder' })

    expect(within(dialog).queryByRole('button', { name: 'Aufnehmen' })).not.toBeInTheDocument()
    const add = within(dialog).getByRole('button', { name: 'Hinzufügen' })
    expect(add).toBeEnabled()
    await user.click(add)

    const search = within(dialog).getByRole('combobox', { name: 'Person suchen' })
    expect(search).toHaveAccessibleDescription('Bitte eine Person auswählen.')
    expect(search).toHaveFocus()
  })

  // For a group of an identity provider, opening the dialog is the explicit request:
  // the list loads at once, the retrieval stays logged.
  it('loads the members of a provider group on opening and keeps them read-only', async () => {
    serve([orgUnitGroup])
    mockFetchedDetails['group-referat-50'] = orgUnitDetails
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Referat 50')
    await user.click(within(menu).getByRole('menuitem', { name: 'Mitglieder' }))
    const dialog = await screen.findByRole('dialog')

    await waitFor(() => expect(mockListGroupMembers).toHaveBeenCalledWith('group-referat-50'))
    const table = await within(dialog).findByRole('table', { name: 'Mitglieder' })
    expect(within(table).getByText('Bob')).toBeInTheDocument()
    expect(within(table).getByRole('columnheader', { name: 'Mitglied seit' })).toBeInTheDocument()
    expect(
      within(dialog).getByRole('searchbox', { name: 'Mitglieder filtern' }),
    ).toBeInTheDocument()
    expect(
      within(dialog).queryByRole('button', { name: 'Mitglieder anzeigen' }),
    ).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: /entfernen/i })).not.toBeInTheDocument()
    expect(within(dialog).getByText(/pflegt die Quelle/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Nachweisprotokoll/)).toBeInTheDocument()
  })

  // regression guard: a failed member list shows its reason and a retry, never an endless spinner
  it('names a failed member list and loads it again on request', async () => {
    serve([orgUnitGroup])
    mockListGroupMembers.mockImplementationOnce(() => {
      throw new Error('Gruppe nicht gefunden')
    })
    mockFetchedDetails['group-referat-50'] = orgUnitDetails
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Referat 50')
    await user.click(within(menu).getByRole('menuitem', { name: 'Mitglieder' }))
    const dialog = await screen.findByRole('dialog')

    expect(await within(dialog).findByText('Gruppe nicht gefunden')).toBeInTheDocument()
    expect(within(dialog).queryByLabelText('Mitglieder werden geladen')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Erneut versuchen' }))

    expect(await within(dialog).findByRole('table', { name: 'Mitglieder' })).toBeInTheDocument()
  })

  it('deletes an internal group once the confirmation was answered', async () => {
    serve([adHocGroup])
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Projektbeteiligte Phoenix')
    await user.click(within(menu).getByRole('menuitem', { name: 'Löschen' }))
    await answerConfirm(user, '„Projektbeteiligte Phoenix“ löschen?', 'Löschen')

    await waitFor(() => expect(mockDeleteGroup).toHaveBeenCalledWith('group-phoenix'))
  })

  it('offers no deletion of a provider group and says why', async () => {
    serve([orgUnitGroup])
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Referat 50')
    expect(within(menu).getByRole('menuitem', { name: 'Löschen' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    expect(within(menu).getByText(/werden dort gepflegt/)).toBeInTheDocument()
  })

  // Creating looks like editing - sharing, protection and stewards included
  it('creates a released, protected group with its stewards in one step', async () => {
    useAuthStore.setState({
      user: {
        id: 'admin-1',
        email: 'admin@opaa.local',
        displayName: 'Ada Admin',
        systemRole: 'SYSTEM_ADMIN',
      },
    })
    serve([])
    renderPage()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Gruppe anlegen' }))
    const dialog = await screen.findByRole('dialog', { name: 'Gruppe anlegen' })
    expect(within(dialog).getByText('Ada Admin (Sie)')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Name der Gruppe'), 'Neue Gruppe')
    await user.click(within(dialog).getByRole('switch', { name: 'Zur Verwendung freigegeben' }))
    const protection = within(dialog).getByRole('switch', { name: 'Geschützte Gruppe' })
    expect(protection).toHaveAccessibleDescription(/Personalvertretung/)
    await user.click(protection)
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    await waitFor(() =>
      expect(mockCreateGroup).toHaveBeenCalledWith('Neue Gruppe', '', {
        releasedForUse: true,
        protectedGroup: true,
        stewardIds: ['admin-1'],
      }),
    )
  })

  // A dissolved group is marked and names the reason - its existing
  // permissions remain.
  it('marks a dissolved group and names the reason behind its state', async () => {
    serve([{ ...orgUnitGroup, dissolved: true, state: 'DISSOLVED' }])
    renderPage()

    const table = await screen.findByRole('table', { name: 'Gruppen' })
    expect(within(table).getByText('Aufgelöst')).toBeInTheDocument()
    expect(
      within(table).getByRole('img', { name: /^Grund: Aufgelöst — die Quelle meldet/ }),
    ).toBeInTheDocument()
  })

  // Deactivating in the row menu, with a confirmation that quantifies the consequences
  it('deactivates an internal group after a confirmation that counts the consequences', async () => {
    serve([adHocGroup])
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Projektbeteiligte Phoenix')
    await user.click(within(menu).getByRole('menuitem', { name: 'Deaktivieren' }))
    const dialog = await screen.findByRole('dialog', {
      name: '„Projektbeteiligte Phoenix“ deaktivieren?',
    })
    expect(dialog).toHaveTextContent(
      '3 Freigaben an 2 Objekten und 1 Space-Mitgliedschaft in 1 Space ruhen für 12 aktive Konten.',
    )
    expect(dialog).toHaveTextContent('Gespeichert bleibt alles')
    await user.click(within(dialog).getByRole('button', { name: 'Deaktivieren' }))

    await waitFor(() =>
      expect(mockSetGroupDeactivation).toHaveBeenCalledWith('group-phoenix', true),
    )
  })

  it('names the reason instead of asking when the group may not be deactivated', async () => {
    deactivationPreview.blockedReason =
      'Die Gruppe kann nicht deaktiviert werden. Die Gruppe ist einzige Eigentümerin von 1 Bibliothek.'
    serve([adHocGroup])
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Projektbeteiligte Phoenix')
    await user.click(within(menu).getByRole('menuitem', { name: 'Deaktivieren' }))

    await waitFor(() =>
      expect(useNotificationStore.getState().queue.at(-1)?.message).toMatch(
        /einzige Eigentümerin von 1 Bibliothek/,
      ),
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(mockSetGroupDeactivation).not.toHaveBeenCalled()
  })

  it('marks a deactivated group, names the reason and offers its reactivation', async () => {
    serve([
      {
        ...adHocGroup,
        deactivated: true,
        deactivatedAt: '2026-10-11T08:00:00Z',
        state: 'DEACTIVATED',
      },
    ])
    renderPage()
    const user = userEvent.setup()

    const table = await screen.findByRole('table', { name: 'Gruppen' })
    expect(within(table).getByText('Deaktiviert')).toBeInTheDocument()
    expect(
      within(table).getByRole('img', { name: /^Grund: Deaktiviert — die Gruppe wirkt/ }),
    ).toBeInTheDocument()

    const menu = await openRowMenu(user, 'Projektbeteiligte Phoenix')
    await user.click(within(menu).getByRole('menuitem', { name: 'Reaktivieren' }))
    const dialog = await screen.findByRole('dialog', {
      name: '„Projektbeteiligte Phoenix“ reaktivieren?',
    })
    expect(dialog).toHaveTextContent('gelten wieder für 12 aktive Konten')
    await user.click(within(dialog).getByRole('button', { name: 'Reaktivieren' }))

    await waitFor(() =>
      expect(mockSetGroupDeactivation).toHaveBeenCalledWith('group-phoenix', false),
    )
  })

  it('offers no deactivation of a provider group', async () => {
    serve([orgUnitGroup])
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Referat 50')
    expect(within(menu).queryByRole('menuitem', { name: 'Deaktivieren' })).not.toBeInTheDocument()
  })
})
