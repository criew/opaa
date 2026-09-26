import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../../test/test-utils'
import GroupMembersTable from './GroupMembersTable'

function member(userId: string, displayName: string | null) {
  return { userId, displayName, createdAt: '2026-03-01T10:00:00Z' }
}

const many = [
  member('u1', 'Zora Weiß'),
  member('u2', 'Ärne Becker'),
  member('u3', 'Anton Klein'),
  member('u4', 'Bea Roth'),
  member('u5', 'Carl Vogt'),
  member('u6', 'Dora Lang'),
  member('u7', 'Emil Kurz'),
  member('u8', null),
]

describe('GroupMembersTable', () => {
  it('lists the members alphabetically with the date they joined', () => {
    renderWithProviders(<GroupMembersTable members={many.slice(0, 3)} />)

    const rows = within(screen.getByRole('table', { name: 'Mitglieder' })).getAllByRole('row')
    expect(rows.slice(1).map((row) => row.textContent)).toEqual([
      'Anton Klein01.03.2026',
      'Ärne Becker01.03.2026',
      'Zora Weiß01.03.2026',
    ])
    expect(screen.getByText('3 Mitglieder')).toBeInTheDocument()
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  })

  it('filters a longer list by name', async () => {
    renderWithProviders(<GroupMembersTable members={many} />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('searchbox', { name: 'Mitglieder filtern' }), 'ro')

    const table = screen.getByRole('table', { name: 'Mitglieder' })
    expect(within(table).getByText('Bea Roth')).toBeInTheDocument()
    expect(within(table).queryByText('Anton Klein')).not.toBeInTheDocument()
    expect(screen.getByText('1 von 8 Mitgliedern')).toBeInTheDocument()
  })

  it('offers removal per row only where the caller maintains the members', async () => {
    const onRemove = vi.fn()
    renderWithProviders(<GroupMembersTable members={many.slice(0, 1)} onRemove={onRemove} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Zora Weiß entfernen' }))

    expect(onRemove).toHaveBeenCalledWith(many[0])
  })

  it('names an empty group', () => {
    renderWithProviders(<GroupMembersTable members={[]} />)

    expect(screen.getByText('Diese Gruppe hat keine Mitglieder.')).toBeInTheDocument()
  })
})
