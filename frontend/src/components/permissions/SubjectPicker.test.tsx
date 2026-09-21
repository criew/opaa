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

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    searchSelectableGroups: mockSearchSelectableGroups,
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

function Harness({ initial = emptySubjectSelection }: { initial?: SubjectSelection }) {
  const [subject, setSubject] = useState<SubjectSelection>(initial)
  return (
    <>
      <SubjectPicker labelId="subject-label" value={subject} onChange={setSubject} />
      <output data-testid="selection">{`${subject.type}:${subject.group?.id ?? subject.user?.id ?? ''}`}</output>
    </>
  )
}

describe('SubjectPicker (#1820, ADR-0036 Entscheidung 9)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockSearchSelectableGroups.mockResolvedValue([fromDirectory, fromPartner, smallInternal])
    mockGetUserSummaries.mockResolvedValue([
      { id: 'user-alice', email: 'alice@opaa.local', displayName: 'Alice' },
    ])
  })

  it('tells two same-named groups apart by origin and source path', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: 'Gruppe' }))
    await user.type(screen.getByLabelText('Gruppe suchen'), 'Referat 5')

    const options = await screen.findAllByRole('option')
    const fromHausA = options.find((option) => option.textContent?.includes('Verzeichnis Haus A'))
    const fromPartnerOption = options.find((option) =>
      option.textContent?.includes('Verzeichnis Partner'),
    )
    expect(fromHausA).toHaveTextContent('/Haus/Abteilung 5/Referat 50')
    expect(fromHausA).toHaveTextContent('23 Mitglieder')
    expect(fromPartnerOption).toHaveTextContent('extern')
  })

  /** Ein Zusatz allein genügt nicht: Die externe Gruppe trägt ein eigenes Symbol. */
  it('marks a group of an external provider with a symbol, not only with text', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: 'Gruppe' }))
    await user.type(screen.getByLabelText('Gruppe suchen'), 'Referat 5')

    const options = await screen.findAllByRole('option')
    const external = options.find((option) => option.textContent?.includes('Verzeichnis Partner'))
    expect(
      within(external as HTMLElement).getByTitle('Gruppe eines externen Anbieters'),
    ).toBeInTheDocument()
  })

  it('says "kleine Gruppe" instead of a number below the minimum group size', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: 'Gruppe' }))
    await user.type(screen.getByLabelText('Gruppe suchen'), 'Referat 5')

    const options = await screen.findAllByRole('option')
    const internal = options.find((option) => option.textContent?.includes('Projektteam'))
    expect(internal).toHaveTextContent('kleine Gruppe')
    expect(internal?.textContent).not.toMatch(/\d+ Mitglieder/)
  })

  it('shows an ineffective group as not choosable, with the reason and without losing it', async () => {
    mockSearchSelectableGroups.mockResolvedValue([dissolved])
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: 'Gruppe' }))
    await user.type(screen.getByLabelText('Gruppe suchen'), 'Referat 52')

    const option = await screen.findByRole('option', { name: /Referat 52/ })
    expect(option).toHaveTextContent('aufgelöst — bestehende Rechte bleiben')
    expect(option).toHaveAttribute('aria-disabled', 'true')
  })

  it('is operable by keyboard and reports the selected group', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: 'Gruppe' }))
    const field = screen.getByLabelText('Gruppe suchen')
    field.focus()
    await user.keyboard('Referat 5')
    await screen.findAllByRole('option')
    await user.keyboard('{ArrowDown}{Enter}')

    await waitFor(() =>
      expect(screen.getByTestId('selection')).toHaveTextContent('GROUP:group-referat-50'),
    )
  })

  it('searches persons when the subject is a person', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Person suchen'), 'al')

    await waitFor(() => expect(mockGetUserSummaries).toHaveBeenCalledWith('al'))
    expect(await screen.findByRole('option', { name: /Alice/ })).toBeInTheDocument()
    expect(mockSearchSelectableGroups).not.toHaveBeenCalled()
  })

  it('names the rule that hides a protected group from the search', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: 'Gruppe' }))

    expect(screen.getByText(/vollständige Bezeichnung/)).toBeInTheDocument()
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
