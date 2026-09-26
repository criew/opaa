import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import InitialStewardsSection from './InitialStewardsSection'

vi.mock('../../hooks/useUserSearch', () => ({
  useUserSearch: () => ({
    query: 'bo',
    setQuery: vi.fn(),
    users: [{ id: 'u2', displayName: 'Bob Berger', email: 'bob@opaa.local' }],
    isLoading: false,
    error: null,
  }),
}))

const self = { id: 'u1', displayName: 'Ada Admin', email: 'ada@opaa.local' }

describe('InitialStewardsSection', () => {
  it('adds a named person to the draft', async () => {
    const onChange = vi.fn()
    renderWithProviders(
      <InitialStewardsSection
        stewards={[self]}
        onChange={onChange}
        currentUserId="u1"
        canRemoveSelf
      />,
    )
    const user = userEvent.setup()

    await user.click(screen.getByRole('combobox', { name: 'Verantwortliche Person' }))
    await user.click(await screen.findByRole('option', { name: /Bob Berger/ }))
    await user.click(screen.getByRole('button', { name: 'Als verantwortlich benennen' }))

    expect(onChange).toHaveBeenCalledWith([
      self,
      { id: 'u2', displayName: 'Bob Berger', email: 'bob@opaa.local' },
    ])
  })

  it('lets the administration leave itself out', async () => {
    const onChange = vi.fn()
    renderWithProviders(
      <InitialStewardsSection
        stewards={[self]}
        onChange={onChange}
        currentUserId="u1"
        canRemoveSelf
      />,
    )
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Ada Admin nicht benennen' }))

    expect(onChange).toHaveBeenCalledWith([])
  })

  it('keeps a creator without the system role responsible', () => {
    renderWithProviders(
      <InitialStewardsSection
        stewards={[self]}
        onChange={vi.fn()}
        currentUserId="u1"
        canRemoveSelf={false}
      />,
    )

    expect(screen.getByText('Ada Admin (Sie)')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /nicht benennen/ })).not.toBeInTheDocument()
    expect(screen.getByText(/Sie selbst bleiben verantwortlich/)).toBeInTheDocument()
  })
})
