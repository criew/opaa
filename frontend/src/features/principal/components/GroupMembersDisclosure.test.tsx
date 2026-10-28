import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import GroupMembersDisclosure from './GroupMembersDisclosure'
import type { GroupMemberDisclosureResponse } from '@/shared/api/types'

const twoOfThree: GroupMemberDisclosureResponse = {
  groupId: 'group-referat-50',
  name: 'Referat 50',
  protectedGroup: false,
  activeMemberCount: 3,
  members: [
    { userId: 'user-anna', displayName: 'Anna Bauer' },
    { userId: 'user-bert', displayName: 'Bert Conrad' },
  ],
  responsible: [],
}

describe('GroupMembersDisclosure', () => {
  /**
   * ADR-0036 Entscheidung 9: a member list is a statement about persons. It
   * is built on explicit request — not as a by-product of an overview someone opened for
   * another reason.
   */
  it('lädt nichts, solange niemand danach fragt', () => {
    const load = vi.fn(async () => twoOfThree)

    renderWithProviders(<GroupMembersDisclosure groupLabel="Referat 50" load={load} />)

    expect(load).not.toHaveBeenCalled()
    expect(
      screen.getByRole('button', { name: 'Mitglieder anzeigen (Gruppe „Referat 50“)' }),
    ).toBeVisible()
  })

  it('zeigt die Mitglieder erst nach dem Klick, mit der Zahl des Ganzen', async () => {
    const load = vi.fn(async () => twoOfThree)
    renderWithProviders(<GroupMembersDisclosure groupLabel="Referat 50" load={load} />)

    await userEvent.click(
      screen.getByRole('button', { name: 'Mitglieder anzeigen (Gruppe „Referat 50“)' }),
    )

    await waitFor(() => expect(screen.getByText('Anna Bauer')).toBeVisible())
    expect(screen.getByText('Bert Conrad')).toBeVisible()
    expect(screen.getByText('2 von 3 aktiven Konten')).toBeVisible()
    expect(load).toHaveBeenCalledWith(0, 50)
  })

  it('holt die nächste Seite mit dem bisherigen Bestand als Versatz', async () => {
    const load = vi.fn(async (offset: number) =>
      offset === 0
        ? twoOfThree
        : {
            ...twoOfThree,
            members: [{ userId: 'user-clara', displayName: 'Clara Dorn' }],
          },
    )
    renderWithProviders(<GroupMembersDisclosure groupLabel="Referat 50" load={load} />)
    await userEvent.click(
      screen.getByRole('button', { name: 'Mitglieder anzeigen (Gruppe „Referat 50“)' }),
    )
    await waitFor(() => expect(screen.getByText('Anna Bauer')).toBeVisible())

    await userEvent.click(
      screen.getByRole('button', { name: 'Weitere anzeigen (Gruppe „Referat 50“)' }),
    )

    await waitFor(() => expect(screen.getByText('Clara Dorn')).toBeVisible())
    expect(load).toHaveBeenLastCalledWith(2, 50)
    expect(screen.getByText('3 von 3 aktiven Konten')).toBeVisible()
    expect(
      screen.queryByRole('button', { name: 'Weitere anzeigen (Gruppe „Referat 50“)' }),
    ).toBeNull()
  })

  /** Limitation (d): no names, no size — the stewards take their place. */
  it('nennt bei einer geschützten Gruppe die Verantwortlichen statt der Mitglieder', async () => {
    const load = vi.fn(async () => ({
      groupId: 'group-personalrat',
      name: null,
      protectedGroup: true,
      activeMemberCount: null,
      members: [],
      responsible: ['Andrea Vogt'],
    }))
    renderWithProviders(<GroupMembersDisclosure groupLabel="Geschützte Gruppe" load={load} />)

    await userEvent.click(
      screen.getByRole('button', { name: 'Mitglieder anzeigen (Gruppe „Geschützte Gruppe“)' }),
    )

    await waitFor(() => expect(screen.getByText(/Verantwortlich: Andrea Vogt/)).toBeVisible())
    expect(screen.queryByText(/aktiven Konten/)).toBeNull()
  })

  it('nennt den Fehler, wenn der Dienst die Liste verweigert', async () => {
    const load = vi.fn(async () => {
      throw new Error('Gruppe nicht gefunden')
    })
    renderWithProviders(<GroupMembersDisclosure groupLabel="Referat 50" load={load} />)

    await userEvent.click(
      screen.getByRole('button', { name: 'Mitglieder anzeigen (Gruppe „Referat 50“)' }),
    )

    await waitFor(() => expect(screen.getByText('Gruppe nicht gefunden')).toBeVisible())
  })

  /** A small group names its members and their number as well. */
  it('nennt bei einer kleinen Gruppe Namen und Zahl', async () => {
    const load = vi.fn(async () => ({
      groupId: 'group-kleine-runde',
      name: 'Kleine Runde',
      protectedGroup: false,
      activeMemberCount: 2,
      members: [
        { userId: 'user-anna', displayName: 'Anna Bauer' },
        { userId: 'user-bert', displayName: 'Bert Conrad' },
      ],
      responsible: [] as string[],
    }))
    renderWithProviders(<GroupMembersDisclosure groupLabel="Kleine Runde" load={load} />)

    await userEvent.click(
      screen.getByRole('button', { name: 'Mitglieder anzeigen (Gruppe „Kleine Runde“)' }),
    )

    await waitFor(() => expect(screen.getByText('Bert Conrad')).toBeVisible())
    expect(screen.getByText('2 von 2 aktiven Konten')).toBeVisible()
    expect(screen.queryByText(/Kleine Gruppe/)).toBeNull()
  })

  /**
   * If the group shrinks between two clicks, the next page returns nothing any more - the trigger
   * then disappears instead of staying clickable and doing nothing.
   */
  it('nimmt „Weitere anzeigen" nach einer leeren Folgeseite zurück', async () => {
    const load = vi.fn(async (offset: number) =>
      offset === 0 ? twoOfThree : { ...twoOfThree, members: [] },
    )
    renderWithProviders(<GroupMembersDisclosure groupLabel="Referat 50" load={load} />)
    await userEvent.click(
      screen.getByRole('button', { name: 'Mitglieder anzeigen (Gruppe „Referat 50“)' }),
    )
    await waitFor(() => expect(screen.getByText('Anna Bauer')).toBeVisible())

    await userEvent.click(
      screen.getByRole('button', {
        name: 'Weitere anzeigen (Gruppe „Referat 50“)',
      }),
    )

    await waitFor(() =>
      expect(
        screen.queryByRole('button', {
          name: 'Weitere anzeigen (Gruppe „Referat 50“)',
        }),
      ).toBeNull(),
    )
    expect(screen.getByText('Anna Bauer')).toBeVisible()
  })
})
