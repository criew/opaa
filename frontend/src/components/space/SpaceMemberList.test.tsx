import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
import type { SpaceMemberResponse } from '../../types/api'
import SpaceMemberList from './SpaceMemberList'

const { mockGetSpaceAccessDerivation, mockGetSpaceGroupMembers } = vi.hoisted(() => ({
  mockGetSpaceAccessDerivation: vi.fn(async (spaceId: string, userId?: string) => ({
    spaceId,
    userId: userId ?? 'u1',
    effectiveRole: 'CURATOR' as const,
    pathsWithheld: false,
    paths: [{ basis: 'DIRECT_MEMBERSHIP' as const, spaceRole: 'CURATOR' as const }],
  })),
  mockGetSpaceGroupMembers: vi.fn(async () => ({
    groupId: 'g1',
    name: 'Bürgerbüro Rheinfurt',
    protectedGroup: false,
    activeMemberCount: 1,
    members: [{ userId: 'user-anna', displayName: 'Anna Bauer' }],
    responsible: [] as string[],
  })),
}))

vi.mock('../../services/spaceApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../services/spaceApi')>('../../services/spaceApi')
  return {
    ...actual,
    getSpaceAccessDerivation: mockGetSpaceAccessDerivation,
    getSpaceGroupMembers: mockGetSpaceGroupMembers,
  }
})

const created = '2026-03-01T10:00:00Z'

function person(id: string, name: string, role: SpaceMemberResponse['role']): SpaceMemberResponse {
  return {
    id: `m-${id}`,
    subjectType: 'USER',
    subjectId: id,
    displayName: name,
    role,
    createdAt: created,
  }
}

function group(
  id: string,
  name: string,
  role: SpaceMemberResponse['role'],
  activeMemberCount: number,
): SpaceMemberResponse {
  return {
    id: `m-${id}`,
    subjectType: 'GROUP',
    subjectId: id,
    displayName: name,
    role,
    protectedGroup: false,
    activeMemberCount,
    emptyGroup: activeMemberCount === 0,
    createdAt: created,
  }
}

const owner = person('u-owner', 'Andrea Vogt', 'ADMIN')
const buergerbuero = group('g1', 'Bürgerbüro Rheinfurt', 'MEMBER', 41)
const thomas = person('u-thomas', 'Thomas Klein', 'CURATOR')
const protectedGroup: SpaceMemberResponse = {
  id: 'm-g2',
  subjectType: 'GROUP',
  subjectId: 'g2',
  displayName: null,
  role: 'MEMBER',
  protectedGroup: true,
  activeMemberCount: null,
  emptyGroup: null,
  createdAt: created,
}

interface Overrides {
  members?: SpaceMemberResponse[]
  canManage?: boolean
  isOwner?: boolean
  isDefaultSpace?: boolean
}

function renderList(overrides: Overrides = {}) {
  const handlers = {
    onRoleChange: vi.fn(async () => undefined),
    onRemove: vi.fn(async () => undefined),
    onMakeOwner: vi.fn(async () => undefined),
  }
  renderWithProviders(
    <SpaceMemberList
      spaceId="space-team"
      ownerId={owner.subjectId}
      isDefaultSpace={overrides.isDefaultSpace ?? false}
      members={overrides.members ?? [thomas, buergerbuero, owner, protectedGroup]}
      canManage={overrides.canManage ?? true}
      isOwner={overrides.isOwner ?? false}
      {...handlers}
    />,
  )
  return handlers
}

/** Die Namen in der Reihenfolge der Zeilen. */
function rowNames(): string[] {
  return screen.getAllByTestId('space-member-name').map((element) => element.textContent ?? '')
}

function rowOf(name: string): HTMLElement {
  const row = screen.getByText(name).closest('[data-testid="space-member-row"]')
  expect(row).not.toBeNull()
  return row as HTMLElement
}

async function openMenu(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(screen.getByRole('button', { name: `Weitere Aktionen für „${name}“` }))
  return screen.findByRole('menu')
}

describe('SpaceMemberList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('sortiert Eigentümer zuerst, dann nach Rolle und innerhalb der Rolle nach Name', () => {
    renderList({
      members: [
        person('u-zora', 'Zora Ziegler', 'MEMBER'),
        group('g-amt', 'Amt für Ordnung', 'MEMBER', 12),
        thomas,
        person('u-berta', 'Berta Adler', 'ADMIN'),
        owner,
        buergerbuero,
      ],
    })

    expect(rowNames()).toEqual([
      'Andrea Vogt',
      'Berta Adler',
      'Thomas Klein',
      'Amt für Ordnung',
      'Bürgerbüro Rheinfurt',
      'Zora Ziegler',
    ])
  })

  /** #2207: dieselbe Auswahl wie bei den anderen Rollen, aber schreibgeschützt. */
  it('zeigt beim Eigentümer „Eigentümer“ als schreibgeschützte Auswahl, ohne Menü', async () => {
    renderList()
    const user = userEvent.setup()

    const row = rowOf('Andrea Vogt')
    const owner = within(row).getByRole('combobox', { name: 'Rolle von „Andrea Vogt“' })
    expect(owner).toHaveTextContent('Eigentümer')
    expect(owner).toHaveAttribute('aria-readonly', 'true')
    expect(within(row).queryByText('Administrator')).not.toBeInTheDocument()
    expect(within(row).queryByRole('button')).not.toBeInTheDocument()

    await user.click(owner)
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('nennt bei Gruppen „Gruppe“ und die heutige Mitgliederzahl, bei geschützten keine Zahl', () => {
    renderList()

    expect(within(rowOf('Bürgerbüro Rheinfurt')).getByText('Gruppe · 41 Mitglieder')).toBeVisible()
    const protectedRow = rowOf('Geschützte Gruppe')
    expect(within(protectedRow).getByText('Gruppe')).toBeInTheDocument()
    expect(within(protectedRow).queryByText(/\d+ Mitglied/)).not.toBeInTheDocument()
  })

  it('trägt höchstens die Rollen-Auswahl und das „⋯“-Menü in einer Zeile', () => {
    renderList()

    const row = rowOf('Thomas Klein')
    expect(within(row).getByRole('combobox', { name: 'Rolle von „Thomas Klein“' })).toBeVisible()
    expect(within(row).getAllByRole('button')).toHaveLength(1)
    expect(within(row).queryByText(/Herleitung/)).not.toBeInTheDocument()
    expect(within(row).queryByText('Entfernen')).not.toBeInTheDocument()
  })

  it('bietet einer verwaltenden Person bei Personen Herleitung, Übergabe und Entfernen an', async () => {
    renderList()
    const user = userEvent.setup()

    const menu = await openMenu(user, 'Thomas Klein')

    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['Warum hat Thomas Klein Zugriff?', 'Zum Eigentümer machen', 'Aus Space entfernen'])
  })

  it('bietet bei Gruppen die Mitglieder und das Entfernen an, nie Herleitung oder Übergabe', async () => {
    renderList()
    const user = userEvent.setup()

    const menu = await openMenu(user, 'Bürgerbüro Rheinfurt')

    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['Mitglieder der Gruppe anzeigen', 'Aus Space entfernen'])
  })

  it('bietet im persönlichen Space keine Übergabe an', async () => {
    renderList({ isDefaultSpace: true })
    const user = userEvent.setup()

    const menu = await openMenu(user, 'Thomas Klein')

    expect(within(menu).queryByText('Zum Eigentümer machen')).not.toBeInTheDocument()
  })

  it('zeigt ohne Verwaltungsrecht die Rolle als Etikett und nur die Herleitung', async () => {
    renderList({ canManage: false })
    const user = userEvent.setup()

    expect(within(rowOf('Thomas Klein')).getByText('Kurator')).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Weitere Aktionen für „Bürgerbüro Rheinfurt“' }),
    ).not.toBeInTheDocument()

    const menu = await openMenu(user, 'Thomas Klein')
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['Warum hat Thomas Klein Zugriff?'])
  })

  it('öffnet die Herleitung einer Person aus dem Menü', async () => {
    renderList()
    const user = userEvent.setup()

    const menu = await openMenu(user, 'Thomas Klein')
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Warum hat Thomas Klein Zugriff?' }),
    )

    expect(
      await screen.findByText('Thomas Klein ist Kurator in diesem Space – direkt aufgenommen.'),
    ).toBeInTheDocument()
    expect(mockGetSpaceAccessDerivation).toHaveBeenCalledWith('space-team', 'u-thomas')
  })

  it('lädt die Mitglieder einer Gruppe erst auf Wunsch aus dem Menü', async () => {
    renderList()
    const user = userEvent.setup()
    expect(mockGetSpaceGroupMembers).not.toHaveBeenCalled()

    const menu = await openMenu(user, 'Bürgerbüro Rheinfurt')
    await user.click(within(menu).getByRole('menuitem', { name: 'Mitglieder der Gruppe anzeigen' }))

    expect(await screen.findByText('Anna Bauer')).toBeInTheDocument()
    expect(mockGetSpaceGroupMembers).toHaveBeenCalledTimes(1)
    expect(mockGetSpaceGroupMembers).toHaveBeenCalledWith('space-team', 'g1', 0, 50)
  })

  it('entfernt erst nach der Rückfrage', async () => {
    const handlers = renderList()
    const user = userEvent.setup()

    const menu = await openMenu(user, 'Bürgerbüro Rheinfurt')
    await user.click(within(menu).getByRole('menuitem', { name: 'Aus Space entfernen' }))
    await answerConfirm(user, 'Bürgerbüro Rheinfurt aus diesem Space entfernen?', 'Entfernen')

    await waitFor(() => expect(handlers.onRemove).toHaveBeenCalledWith(buergerbuero))
  })

  it('überträgt die Verantwortung erst nach der Rückfrage', async () => {
    const handlers = renderList()
    const user = userEvent.setup()

    const menu = await openMenu(user, 'Thomas Klein')
    await user.click(within(menu).getByRole('menuitem', { name: 'Zum Eigentümer machen' }))
    await answerConfirm(user, 'Verantwortung an Thomas Klein übertragen?', 'Übertragen')

    await waitFor(() => expect(handlers.onMakeOwner).toHaveBeenCalledWith(thomas))
  })

  it('ist per Tastatur bedienbar', async () => {
    renderList()
    const user = userEvent.setup()

    screen.getByRole('button', { name: 'Weitere Aktionen für „Thomas Klein“' }).focus()
    await user.keyboard('{Enter}')
    const menu = await screen.findByRole('menu')
    await waitFor(() =>
      expect(within(menu).getByRole('menuitem', { name: /Warum hat/ })).toHaveFocus(),
    )
    await user.keyboard('{ArrowDown}{Enter}')

    expect(
      await screen.findByRole('dialog', { name: 'Verantwortung an Thomas Klein übertragen?' }),
    ).toBeInTheDocument()
  })

  it('setzt „Aus Space entfernen“ durch eine Trennlinie ab', async () => {
    renderList()
    const user = userEvent.setup()

    const menu = await openMenu(user, 'Thomas Klein')

    const remove = within(menu).getByRole('menuitem', { name: 'Aus Space entfernen' })
    expect(remove.previousElementSibling?.matches('hr, [role="separator"]')).toBe(true)
  })

  it('fragt beim Entfernen als Gefahr nach, mit „Abbrechen“ im Fokus', async () => {
    renderList()
    const user = userEvent.setup()

    const menu = await openMenu(user, 'Thomas Klein')
    await user.click(within(menu).getByRole('menuitem', { name: 'Aus Space entfernen' }))

    const dialog = await screen.findByRole('dialog', {
      name: 'Thomas Klein aus diesem Space entfernen?',
    })
    await waitFor(() =>
      expect(within(dialog).getByRole('button', { name: 'Abbrechen' })).toHaveFocus(),
    )
  })

  it('gibt den Fokus nach „Schließen“ der Herleitung an den Menüknopf der Zeile zurück', async () => {
    renderList()
    const user = userEvent.setup()

    const menu = await openMenu(user, 'Thomas Klein')
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Warum hat Thomas Klein Zugriff?' }),
    )
    await screen.findByText(/Thomas Klein ist Kurator/)
    await user.click(screen.getByRole('button', { name: 'Schließen' }))

    expect(
      screen.getByRole('button', { name: 'Weitere Aktionen für „Thomas Klein“' }),
    ).toHaveFocus()
  })

  it('gibt den Fokus nach „Mitglieder ausblenden“ an den Menüknopf der Zeile zurück', async () => {
    renderList()
    const user = userEvent.setup()

    const menu = await openMenu(user, 'Bürgerbüro Rheinfurt')
    await user.click(within(menu).getByRole('menuitem', { name: 'Mitglieder der Gruppe anzeigen' }))
    await screen.findByText('Anna Bauer')
    await user.click(
      screen.getByRole('button', {
        name: 'Mitglieder der Gruppe „Bürgerbüro Rheinfurt“ ausblenden',
      }),
    )

    expect(
      screen.getByRole('button', { name: 'Weitere Aktionen für „Bürgerbüro Rheinfurt“' }),
    ).toHaveFocus()
  })

  it('zeigt bis zu zehn Einträgen kein Suchfeld', () => {
    const ten = Array.from({ length: 10 }, (_, index) =>
      person(`u${index}`, `Person ${index}`, 'MEMBER'),
    )
    renderList({ members: ten })

    expect(screen.queryByRole('searchbox', { name: 'Mitglieder filtern' })).not.toBeInTheDocument()
  })

  it('filtert ab mehr als zehn Einträgen nach Name', async () => {
    const many = [
      owner,
      buergerbuero,
      ...Array.from({ length: 9 }, (_, index) => person(`u${index}`, `Person ${index}`, 'MEMBER')),
    ]
    renderList({ members: many })
    const user = userEvent.setup()

    await user.type(screen.getByRole('searchbox', { name: 'Mitglieder filtern' }), 'bürger')

    expect(rowNames()).toEqual(['Bürgerbüro Rheinfurt'])
  })
})
