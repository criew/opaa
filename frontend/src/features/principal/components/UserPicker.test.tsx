import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../../test/test-utils'
import type { UserSummary } from '@/shared/api/types'
import UserPicker from './UserPicker'

const setQuery = vi.fn()

vi.mock('../hooks/useUserSearch', () => ({
  useUserSearch: () => ({
    query: 'bo',
    setQuery,
    users: [
      {
        id: 'u2',
        displayName: 'Bob Berger',
        email: 'bob@opaa.local',
        providerName: 'Landesverwaltung',
      },
    ],
    isLoading: false,
    error: null,
  }),
}))

function Harness() {
  const [value, setValue] = useState<UserSummary | null>(null)
  return (
    <UserPicker
      ariaLabel="Verantwortliche Person"
      placeholder="Person suchen …"
      value={value}
      onChange={setValue}
      excludedUserIds={[]}
    />
  )
}

describe('UserPicker', () => {
  // regression guard: a chosen person has to stand in the field, and choosing is no new search
  it('shows the chosen person in the field without searching for the label', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()
    const field = screen.getByRole('combobox', { name: 'Verantwortliche Person' })

    await user.type(field, 'bo')
    setQuery.mockClear()
    await user.click(await screen.findByRole('option', { name: /Bob Berger/ }))

    expect(field).toHaveValue('Bob Berger (bob@opaa.local) · Landesverwaltung')
    expect(setQuery).not.toHaveBeenCalled()
  })

  // regression guard for #3358 (6): name and address are self-chosen, the sign-in is not
  it('names the sign-in every hit comes from', async () => {
    renderWithProviders(<Harness />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: 'Verantwortliche Person' }), 'bo')

    expect(await screen.findByRole('option', { name: /Landesverwaltung/ })).toBeInTheDocument()
  })
})
