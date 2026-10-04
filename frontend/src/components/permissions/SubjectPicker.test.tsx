import { useState } from 'react'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import SubjectPicker from './SubjectPicker'
import {
  confirmExternalSubject,
  emptySubjectSelection,
  type SubjectSelection,
} from './subjectSelection'
import type { SelectableGroupResponse, UserSummary } from '../../types/api'

const { mockSearchSelectableGroups, mockGetUserSummaries } = vi.hoisted(() => ({
  mockSearchSelectableGroups: vi.fn(async () => [] as SelectableGroupResponse[]),
  mockGetUserSummaries: vi.fn(async () => [] as UserSummary[]),
}))

vi.mock('../../services/groupApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../services/groupApi')>('../../services/groupApi')
  return {
    ...actual,
    searchSelectableGroups: mockSearchSelectableGroups,
  }
})

vi.mock('../../services/userApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../services/userApi')>('../../services/userApi')
  return {
    ...actual,
    getUserSummaries: mockGetUserSummaries,
  }
})

const fromDirectory: SelectableGroupResponse = {
  id: 'group-referat-50',
  name: 'Referat 50',
  origin: 'PROVIDER',
  provider: {
    id: 'provider-haus-a',
    displayName: 'Verzeichnis Haus A',
    external: false,
    enabled: true,
    groupMechanism: 'DIRECTORY',
  },
  sourcePath: '/Haus/Abteilung 5/Referat 50',
  activeMemberCount: 23,
  smallGroup: false,
  emptyGroup: false,
  protectedGroup: false,
  selectable: true,
  dissolved: false,
  providerDisabled: false,
  unmaintained: false,
}

/** #1443: derselbe Name, ein anderer Anbieter - allein am Namen nicht zu unterscheiden. */
const fromPartner: SelectableGroupResponse = {
  ...fromDirectory,
  id: 'group-referat-50-partner',
  provider: {
    id: 'provider-partner',
    displayName: 'Verzeichnis Partner',
    external: true,
    enabled: true,
    groupMechanism: 'TOKEN',
  },
  sourcePath: null,
  activeMemberCount: 8,
}

const smallInternal: SelectableGroupResponse = {
  ...fromDirectory,
  id: 'group-projektteam',
  name: 'Referat 5 Projektteam',
  origin: 'INTERNAL',
  provider: null,
  sourcePath: null,
  activeMemberCount: null,
  smallGroup: true,
}

const dissolved: SelectableGroupResponse = {
  ...fromDirectory,
  id: 'group-referat-52',
  name: 'Referat 52',
  selectable: false,
  dissolved: true,
}

/** Die Entprellung der beiden Suchen liegt bei 300 ms; hier wird sicher darueber hinaus gewartet. */
async function pastTheDebounce(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 400))
}

/**
 * Persons and groups are searched by two independent requests, so the list fills in two steps;
 * waiting for any option is not waiting for this one.
 */
function findOptionContaining(text: string): Promise<HTMLElement> {
  return waitFor(() => {
    const option = screen
      .getAllByRole('option')
      .find((candidate) => candidate.textContent?.includes(text))
    if (!option) throw new Error('No option containing "' + text + '" yet')
    return option
  })
}

function Harness({
  initial = emptySubjectSelection,
  allowAllAccounts = false,
}: {
  initial?: SubjectSelection
  allowAllAccounts?: boolean
}) {
  const [subject, setSubject] = useState<SubjectSelection>(initial)
  return (
    <>
      <SubjectPicker value={subject} onChange={setSubject} allowAllAccounts={allowAllAccounts} />
      <output data-testid="selection">{`${subject.type}:${subject.group?.id ?? subject.user?.id ?? ''}`}</output>
    </>
  )
}

const SEARCH = 'Person oder Gruppe suchen'

describe('SubjectPicker (#1820, #2131, ADR-0036 Entscheidung 9)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Groups answer after persons, as they can in production: tests must wait for their own option.
    mockSearchSelectableGroups.mockImplementation(async () => {
      await new Promise((resolve) => setTimeout(resolve, 150))
      return [fromDirectory, fromPartner, smallInternal]
    })
    mockGetUserSummaries.mockResolvedValue([
      { id: 'user-alice', email: 'alice@opaa.local', displayName: 'Alice' },
    ])
  })

  it('has one search field and no switch between person and group', () => {
    renderWithProviders(<Harness />)

    expect(screen.getByRole('combobox', { name: SEARCH })).toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  })

  it('searches persons and groups with the same input and mixes them by closeness', async () => {
    mockGetUserSummaries.mockResolvedValue([
      { id: 'user-thomas', email: 'thomas.meier@opaa.local', displayName: 'Thomas Meier' },
      { id: 'user-meike', email: 'meike.brandt@opaa.local', displayName: 'Meike Brandt' },
    ])
    mockSearchSelectableGroups.mockResolvedValue([
      { ...fromDirectory, id: 'group-meldewesen', name: 'Gemeinwohl' },
      { ...smallInternal, id: 'group-mei', name: 'Mei' },
    ])
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'mei')

    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(4))
    expect(mockGetUserSummaries).toHaveBeenCalledWith('mei')
    expect(mockSearchSelectableGroups).toHaveBeenCalledWith('mei')
    const names = screen.getAllByRole('option').map((option) => option.textContent ?? '')
    expect(names[0]).toMatch(/^Mei · Gruppe/)
    expect(names[1]).toMatch(/^Meike Brandt/)
    expect(names[2]).toMatch(/^Thomas Meier/)
    expect(names[3]).toMatch(/^Gemeinwohl · Gruppe/)
  })

  it('names a group as a group in its text, not only with a symbol', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'Referat 5')

    const group = await screen.findByRole('option', { name: /Referat 5 Projektteam · Gruppe/ })
    expect(group).toBeInTheDocument()
    const person = screen.queryByRole('option', { name: /Alice/ })
    expect(person?.textContent ?? '').not.toContain('Gruppe')
  })

  it('tells two same-named groups apart by origin and source path', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'Referat 5')

    const fromHausA = await findOptionContaining('Verzeichnis Haus A')
    const fromPartnerOption = await findOptionContaining('Verzeichnis Partner')
    expect(fromHausA).toHaveTextContent('/Haus/Abteilung 5/Referat 50')
    expect(fromHausA).toHaveTextContent('23 Mitglieder')
    expect(fromPartnerOption).toHaveTextContent('extern')
  })

  /** Ein Zusatz allein genügt nicht: Die externe Gruppe trägt ein eigenes Symbol. */
  it('marks a group of an external provider with a symbol, not only with text', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'Referat 5')

    const external = await findOptionContaining('Verzeichnis Partner')
    expect(within(external).getByTitle('Gruppe eines externen Anbieters')).toBeInTheDocument()
  })

  it('says "kleine Gruppe" instead of a number below the minimum group size', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'Referat 5')

    const internal = await findOptionContaining('Projektteam')
    expect(internal).toHaveTextContent('kleine Gruppe')
    expect(internal?.textContent).not.toMatch(/\d+ Mitglieder/)
  })

  it('shows an ineffective group as not choosable, with the reason and without losing it', async () => {
    mockSearchSelectableGroups.mockResolvedValue([dissolved])
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'Referat 52')

    const option = await screen.findByRole('option', { name: /Referat 52/ })
    expect(option).toHaveTextContent('aufgelöst — bestehende Rechte bleiben')
    expect(option).toHaveAttribute('aria-disabled', 'true')
  })

  it('is operable by keyboard and reports the selected group', async () => {
    mockGetUserSummaries.mockResolvedValue([])
    mockSearchSelectableGroups.mockResolvedValue([fromDirectory])
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    const field = screen.getByRole('combobox', { name: SEARCH })
    field.focus()
    await user.keyboard('Referat 5')
    await screen.findAllByRole('option')
    await user.keyboard('{ArrowDown}{Enter}')

    await waitFor(() =>
      expect(screen.getByTestId('selection')).toHaveTextContent('GROUP:group-referat-50'),
    )
  })

  it('offers "Alle Konten" as an entry of its own only where the caller allows it', async () => {
    const { unmount } = renderWithProviders(<Harness allowAllAccounts />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('combobox', { name: SEARCH }))
    await user.click(await screen.findByRole('option', { name: /Alle Konten/ }))

    await waitFor(() => expect(screen.getByTestId('selection')).toHaveTextContent('ALL_ACCOUNTS:'))
    unmount()

    renderWithProviders(<Harness />)
    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'Alle')
    await pastTheDebounce()
    expect(screen.queryByRole('option', { name: /Alle Konten/ })).not.toBeInTheDocument()
  })

  /**
   * #778: Nach der Auswahl setzt MUI den Eingabetext auf das Label der Option - als neue Eingabe
   * weitergegeben loeste das eine zweite Suche nach nie getipptem Text aus.
   */
  it('runs no second search after a person was selected', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'al')
    await user.click(await screen.findByRole('option', { name: /Alice/ }))

    await waitFor(() =>
      expect(screen.getByTestId('selection')).toHaveTextContent('USER:user-alice'),
    )
    // Ueber die Entprellung hinaus warten: Ohne den Schutz laeuft die zweite Suche erst danach an,
    // und eine Behauptung davor bestuende auch auf dem fehlerhaften Stand.
    await pastTheDebounce()
    expect(mockGetUserSummaries).toHaveBeenCalledTimes(1)
    expect(mockSearchSelectableGroups).toHaveBeenCalledTimes(1)
  })

  it('runs no second search after a group was selected', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'Referat 5')
    await user.click(await screen.findByRole('option', { name: /Referat 5 Projektteam/ }))

    await waitFor(() =>
      expect(screen.getByTestId('selection')).toHaveTextContent('GROUP:group-projektteam'),
    )
    await pastTheDebounce()
    expect(mockSearchSelectableGroups).toHaveBeenCalledTimes(1)
    expect(mockGetUserSummaries).toHaveBeenCalledTimes(1)
  })

  /** Review #2137: "Alle Konten" must not stand in for an empty result in the grants. */
  it('shows the empty-result hint in the grants too, without "Alle Konten" in its way', async () => {
    mockGetUserSummaries.mockResolvedValue([])
    mockSearchSelectableGroups.mockResolvedValue([])
    renderWithProviders(<Harness allowAllAccounts />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'Personalrat')

    expect(await screen.findByText(/vollständige Bezeichnung/)).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Alle Konten/ })).not.toBeInTheDocument()
  })

  it('keeps offering "Alle Konten" for an input that names it', async () => {
    mockGetUserSummaries.mockResolvedValue([])
    mockSearchSelectableGroups.mockResolvedValue([])
    renderWithProviders(<Harness allowAllAccounts />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'Konten')

    expect(await screen.findByRole('option', { name: /Alle Konten/ })).toBeInTheDocument()
  })

  it('names the rule that hides a protected group when the search finds nothing', async () => {
    mockGetUserSummaries.mockResolvedValue([])
    mockSearchSelectableGroups.mockResolvedValue([])
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: SEARCH }), 'Personalrat')

    expect(await screen.findByText(/vollständige Bezeichnung/)).toBeInTheDocument()
  })
})

describe('confirmExternalSubject', () => {
  it('asks back before a right is granted to a group of an external provider', async () => {
    const confirmSpy = vi.fn()
    vi.spyOn(await import('../../stores/confirmStore'), 'confirmAction').mockImplementation(
      async (request) => {
        confirmSpy(request)
        return false
      },
    )

    const proceed = await confirmExternalSubject({
      type: 'GROUP',
      user: null,
      group: fromPartner,
    })

    expect(proceed).toBe(false)
    expect(confirmSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        question: expect.stringContaining('externen Anbieters'),
      }),
    )
    vi.restoreAllMocks()
  })

  it('does not ask back for an internal group or a person', async () => {
    const confirmAction = vi.spyOn(await import('../../stores/confirmStore'), 'confirmAction')

    await expect(
      confirmExternalSubject({ type: 'GROUP', user: null, group: smallInternal }),
    ).resolves.toBe(true)
    await expect(
      confirmExternalSubject({
        type: 'USER',
        user: { id: 'user-alice', email: 'alice@opaa.local', displayName: 'Alice' },
        group: null,
      }),
    ).resolves.toBe(true)
    expect(confirmAction).not.toHaveBeenCalled()
    vi.restoreAllMocks()
  })
})
