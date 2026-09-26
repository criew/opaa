import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import GroupManagementPage from './GroupManagementPage'
import { useGroupStore } from '../stores/groupStore'
import { useGroupAdminListStore } from '../stores/groupAdminListStore'
import type { GroupListResponse, GroupResponse } from '../types/api'
import type { GroupListQuery } from '../services/groupAdminApi'

const {
  mockListGroupPage,
  mockGetGroup,
  mockFetchedDetails,
  listed,
  mockCreateGroup,
  mockUpdateGroup,
  mockSetGroupRelease,
  mockDeleteGroup,
  mockRemoveGroupMember,
  mockAppointGroupContact,
  mockDismissGroupContact,
} = vi.hoisted(() => ({
  mockListGroupPage: vi.fn(),
  mockGetGroup: vi.fn(),
  /** Was `getGroup` liefert - die Mitgliederliste, deren Abruf ein Audit-Ereignis ist. */
  mockFetchedDetails: {} as Record<string, GroupResponse>,
  /** Die Gruppen, die der Server für die aktuelle Anfrage kennt. */
  listed: { groups: [] as GroupListResponse[] },
  mockCreateGroup: vi.fn(async () => ({}) as GroupResponse),
  mockUpdateGroup: vi.fn(async () => ({}) as GroupResponse),
  mockSetGroupRelease: vi.fn(async () => ({}) as GroupResponse),
  mockDeleteGroup: vi.fn(async () => undefined),
  mockRemoveGroupMember: vi.fn(async () => undefined),
  mockAppointGroupContact: vi.fn(async () => ({
    userId: 'u2',
    displayName: 'Bob',
    appointedAt: '2026-09-01T10:00:00Z',
  })),
  mockDismissGroupContact: vi.fn(async () => undefined),
}))

vi.mock('../services/groupAdminApi', async () => {
  const actual = await vi.importActual<typeof import('../services/groupAdminApi')>(
    '../services/groupAdminApi',
  )
  return {
    ...actual,
    listGroupPage: vi.fn(async (query: GroupListQuery) => {
      mockListGroupPage(query)
      const items = listed.groups.filter((group) => !query.origin || group.origin === query.origin)
      return { items, total: items.length, page: 0, size: 25 }
    }),
  }
})

vi.mock('../services/permissionTransferApi', async () => {
  const actual = await vi.importActual<typeof import('../services/permissionTransferApi')>(
    '../services/permissionTransferApi',
  )
  return { ...actual, getGroupEffects: vi.fn(async () => []) }
})

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getUsers: vi.fn(async () => []),
    getGroups: vi.fn(async () => listed.groups),
    getGroup: vi.fn(async (groupId: string) => {
      mockGetGroup(groupId)
      return mockFetchedDetails[groupId]
    }),
    createGroup: mockCreateGroup,
    updateGroup: mockUpdateGroup,
    setGroupRelease: mockSetGroupRelease,
    deleteGroup: mockDeleteGroup,
    removeGroupMember: mockRemoveGroupMember,
    appointGroupContact: mockAppointGroupContact,
    dismissGroupContact: mockDismissGroupContact,
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
  })

  // #1978: die Gruppen als Tabelle wie die Konten - Herkunft, Mitglieder, Zustand; eine eigene
  // Spalte „Art" gibt es nicht, das Info-Symbol hinter dem Anbieter erklärt die Herkunft
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
        name: /^Herkunft: Diese Gruppe stammt aus dem Verzeichnis von „Verzeichnisdienst“/,
      }),
    ).toBeInTheDocument()
    expect(within(table).queryByRole('columnheader', { name: /^Art/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Art' })).not.toBeInTheDocument()
    expect(within(referat).getByText('/Haus A/Referat 50')).toBeInTheDocument()
    expect(within(referat).getByText('Aktiv')).toBeInTheDocument()
    expect(screen.getByText('2 Gruppen · Seite 1 von 1')).toBeInTheDocument()
    // the member list is never part of the table
    expect(mockGetGroup).not.toHaveBeenCalled()
  })

  // ADR-0036, Entscheidung 3: ein wartender Plan bleibt laut - sein Alter steht im Link selbst,
  // die Einzelheiten im Popover; ein Hinweiskasten über der Liste entfällt (#1978)
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
    expect(within(dialog).queryByText(/Ansprechstellen sprechen/)).not.toBeInTheDocument()

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

  // ADR-0025, Entscheidung 4 (#1331): Name und Mitglieder pflegt die Quelle; die Erklärung nennt
  // die tatsächliche Quelle
  it('explains a provider group and offers only its contact points', async () => {
    const tokenGroup: GroupListResponse = {
      ...orgUnitGroup,
      id: 'group-token-fachbereich',
      name: 'Fachbereich 3',
      kind: 'IDENTITY_PROVIDER',
    }
    serve([tokenGroup])
    renderPage()
    const user = userEvent.setup()

    const origin = await screen.findByRole('img', { name: /^Herkunft: Diese Gruppe meldet/ })
    await user.hover(origin)
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/bei jeder Anmeldung mit/)
    await user.unhover(origin)

    const menu = await openRowMenu(user, 'Fachbereich 3')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/stammt aus dem Identitätsanbieter/)).toBeInTheDocument()
    expect(within(dialog).getByText('Verzeichnisdienst · /Haus A/Referat 50')).toBeInTheDocument()
    expect(within(dialog).queryByLabelText('Name der Gruppe')).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Speichern' })).not.toBeInTheDocument()
  })

  // #1875: benennbar ist nur ein Mitglied - die Auswahl hängt am ausdrücklichen Abruf der Liste
  it('appoints a member of a provider group as contact point once the list was fetched', async () => {
    serve([orgUnitGroup])
    mockFetchedDetails['group-referat-50'] = orgUnitDetails
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Referat 50')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/aus dem Verzeichnis synchronisiert/)).toBeInTheDocument()
    expect(mockGetGroup).not.toHaveBeenCalled()

    await user.click(within(dialog).getByRole('button', { name: 'Mitgliederliste abrufen' }))
    await waitFor(() => expect(mockGetGroup).toHaveBeenCalledWith('group-referat-50'))
    await user.click(await within(dialog).findByRole('combobox', { name: /ansprechstelle/i }))
    await user.click(await screen.findByRole('option', { name: 'Bob' }))
    await user.click(within(dialog).getByRole('button', { name: /als ansprechstelle benennen/i }))

    await waitFor(() =>
      expect(mockAppointGroupContact).toHaveBeenCalledWith('group-referat-50', 'u2'),
    )
  })

  it('dismisses a contact point after confirmation', async () => {
    const withContact: GroupListResponse = {
      ...orgUnitGroup,
      contacts: [{ userId: 'u2', displayName: 'Bob', appointedAt: '2026-09-01T10:00:00Z' }],
    }
    serve([withContact])
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Referat 50')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
    await user.click(await screen.findByRole('button', { name: /entlassen/i }))
    await answerConfirm(user, 'Bob als Ansprechstelle entlassen?', 'Entlassen')

    await waitFor(() =>
      expect(mockDismissGroupContact).toHaveBeenCalledWith('group-referat-50', 'u2'),
    )
  })

  // ADR-0036, Entscheidung 4/9: Der Abruf der Mitgliederliste ist das Audit-Ereignis - zugesichert
  // ist deshalb die ausbleibende ANFRAGE, nicht nur die ausbleibende Anzeige.
  it('does not load the member list until it is asked for', async () => {
    serve([adHocGroup])
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Projektbeteiligte Phoenix')
    await user.click(within(menu).getByRole('menuitem', { name: 'Mitglieder' }))
    const dialog = await screen.findByRole('dialog', {
      name: 'Mitglieder von „Projektbeteiligte Phoenix“',
    })
    expect(within(dialog).getByText(/Nachweisprotokoll/)).toBeInTheDocument()
    expect(within(dialog).getByText('Die Gruppe hat 1 Mitglied.')).toBeInTheDocument()
    expect(mockGetGroup).not.toHaveBeenCalled()

    mockFetchedDetails['group-phoenix'] = adHocDetails
    await user.click(within(dialog).getByRole('button', { name: 'Mitglieder anzeigen' }))

    await waitFor(() => expect(mockGetGroup).toHaveBeenCalledWith('group-phoenix'))
    expect(await within(dialog).findByText('Alice')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Entfernen' }))
    await waitFor(() => expect(mockRemoveGroupMember).toHaveBeenCalledWith('group-phoenix', 'u1'))
  })

  it('keeps the members of a provider group read-only', async () => {
    serve([orgUnitGroup])
    mockFetchedDetails['group-referat-50'] = orgUnitDetails
    renderPage()
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Referat 50')
    await user.click(within(menu).getByRole('menuitem', { name: 'Mitglieder' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Mitglieder anzeigen' }))

    expect(await within(dialog).findByText('Bob')).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Entfernen' })).not.toBeInTheDocument()
    expect(within(dialog).getByText(/pflegt ihre Quelle/)).toBeInTheDocument()
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

  it('creates a group through the dialog', async () => {
    serve([])
    renderPage()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Gruppe anlegen' }))
    await user.type(screen.getByLabelText(/^name/i), 'Neue Gruppe')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    await waitFor(() => expect(mockCreateGroup).toHaveBeenCalledWith('Neue Gruppe', ''))
  })

  // #1821: Eine aufgelöste Gruppe ist gekennzeichnet und nennt den Grund - ihre bestehenden
  // Berechtigungen bleiben.
  it('marks a dissolved group and names the reason behind its state', async () => {
    serve([{ ...orgUnitGroup, dissolved: true, state: 'DISSOLVED' }])
    renderPage()

    const table = await screen.findByRole('table', { name: 'Gruppen' })
    expect(within(table).getByText('Aufgelöst')).toBeInTheDocument()
    expect(
      within(table).getByRole('img', { name: /^Grund: Aufgelöst — die Quelle meldet/ }),
    ).toBeInTheDocument()
  })
})
