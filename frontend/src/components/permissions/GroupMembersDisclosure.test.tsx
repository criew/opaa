import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import GroupMembersDisclosure from './GroupMembersDisclosure'
import type { GroupMemberDisclosureResponse } from '../../types/api'

const twoOfThree: GroupMemberDisclosureResponse = {
  groupId: 'group-referat-50',
  name: 'Referat 50',
  protectedGroup: false,
  smallGroup: false,
  activeMemberCount: 3,
  members: [
    { userId: 'user-anna', displayName: 'Anna Bauer' },
    { userId: 'user-bert', displayName: 'Bert Conrad' },
  ],
  responsible: [],
}

describe('GroupMembersDisclosure', () => {
  /**
   * #1880, ADR-0036 Entscheidung 9: Eine Mitgliederliste ist eine Aussage über Personen. Sie
   * entsteht auf ausdrücklichen Wunsch — nicht als Beiwerk einer Übersicht, die jemand aus einem
   * anderen Grund geöffnet hat.
   */
  it('lädt nichts, solange niemand danach fragt', () => {
    const load = vi.fn(async () => twoOfThree)

    renderWithProviders(<GroupMembersDisclosure groupLabel="Referat 50" load={load} />)

    expect(load).not.toHaveBeenCalled()
    expect(
      screen.getByRole('button', { name: 'Mitglieder der Gruppe „Referat 50“ anzeigen' }),
    ).toBeVisible()
  })

  it('zeigt die Mitglieder erst nach dem Klick, mit der Zahl des Ganzen', async () => {
    const load = vi.fn(async () => twoOfThree)
    renderWithProviders(<GroupMembersDisclosure groupLabel="Referat 50" load={load} />)

    await userEvent.click(
      screen.getByRole('button', { name: 'Mitglieder der Gruppe „Referat 50“ anzeigen' }),
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
      screen.getByRole('button', { name: 'Mitglieder der Gruppe „Referat 50“ anzeigen' }),
    )
    await waitFor(() => expect(screen.getByText('Anna Bauer')).toBeVisible())

    await userEvent.click(
      screen.getByRole('button', { name: 'Weitere Mitglieder der Gruppe „Referat 50“ anzeigen' }),
    )

    await waitFor(() => expect(screen.getByText('Clara Dorn')).toBeVisible())
    expect(load).toHaveBeenLastCalledWith(2, 50)
    expect(screen.getByText('3 von 3 aktiven Konten')).toBeVisible()
    expect(
      screen.queryByRole('button', { name: 'Weitere Mitglieder der Gruppe „Referat 50“ anzeigen' }),
    ).toBeNull()
  })

  /** Begrenzung (d): keine Namen, keine Größe — die Verantwortlichen treten an ihre Stelle. */
  it('nennt bei einer geschützten Gruppe die Verantwortlichen statt der Mitglieder', async () => {
    const load = vi.fn(async () => ({
      groupId: 'group-personalrat',
      name: null,
      protectedGroup: true,
      smallGroup: false,
      activeMemberCount: null,
      members: [],
      responsible: ['Andrea Vogt'],
    }))
    renderWithProviders(<GroupMembersDisclosure groupLabel="Geschützte Gruppe" load={load} />)

    await userEvent.click(
      screen.getByRole('button', { name: 'Mitglieder der Gruppe „Geschützte Gruppe“ anzeigen' }),
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
      screen.getByRole('button', { name: 'Mitglieder der Gruppe „Referat 50“ anzeigen' }),
    )

    await waitFor(() => expect(screen.getByText('Gruppe nicht gefunden')).toBeVisible())
  })

  /** Begrenzung (e), Auflage A2: unterhalb der Mindestgruppengröße gibt es weder Namen noch Zahl. */
  it('sagt bei einer kleinen Gruppe, dass weder Namen noch Zahl genannt werden', async () => {
    const load = vi.fn(async () => ({
      groupId: 'group-kleine-runde',
      name: 'Kleine Runde',
      protectedGroup: false,
      smallGroup: true,
      activeMemberCount: null,
      members: [],
      responsible: [] as string[],
    }))
    renderWithProviders(<GroupMembersDisclosure groupLabel="Kleine Runde" load={load} />)

    await userEvent.click(
      screen.getByRole('button', { name: 'Mitglieder der Gruppe „Kleine Runde“ anzeigen' }),
    )

    await waitFor(() => expect(screen.getByText(/Kleine Gruppe/)).toBeVisible())
    expect(screen.queryByText(/aktiven Konten/)).toBeNull()
  })

  /**
   * Schrumpft die Gruppe zwischen zwei Klicks, liefert die Folgeseite nichts mehr - der Auslöser
   * verschwindet dann, statt klickbar zu bleiben und nichts zu tun.
   */
  it('nimmt „Weitere anzeigen" nach einer leeren Folgeseite zurück', async () => {
    const load = vi.fn(async (offset: number) =>
      offset === 0 ? twoOfThree : { ...twoOfThree, members: [] },
    )
    renderWithProviders(<GroupMembersDisclosure groupLabel="Referat 50" load={load} />)
    await userEvent.click(
      screen.getByRole('button', { name: 'Mitglieder der Gruppe „Referat 50“ anzeigen' }),
    )
    await waitFor(() => expect(screen.getByText('Anna Bauer')).toBeVisible())

    await userEvent.click(
      screen.getByRole('button', {
        name: 'Weitere Mitglieder der Gruppe „Referat 50“ anzeigen',
      }),
    )

    await waitFor(() =>
      expect(
        screen.queryByRole('button', {
          name: 'Weitere Mitglieder der Gruppe „Referat 50“ anzeigen',
        }),
      ).toBeNull(),
    )
    expect(screen.getByText('Anna Bauer')).toBeVisible()
  })
})
