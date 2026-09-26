import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import type { UserSummary } from '../../types/api'
import UserPicker from './UserPicker'

const setQuery = vi.fn()

vi.mock('../../hooks/useUserSearch', () => ({
  useUserSearch: () => ({
    query: 'bo',
    setQuery,
    users: [{ id: 'u2', displayName: 'Bob Berger', email: 'bob@opaa.local' }],
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

    expect(field).toHaveValue('Bob Berger (bob@opaa.local)')
    expect(setQuery).not.toHaveBeenCalled()
  })
})
