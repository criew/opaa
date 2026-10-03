import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import AssetFilterChips from './AssetFilterChips'

describe('AssetFilterChips (guidelines 5.11)', () => {
  it('is a named group of independent toggles whose state aria-pressed carries', async () => {
    const user = userEvent.setup()
    const onToggle = vi.fn()
    renderWithProviders(<AssetFilterChips value={{ favorites: true }} onToggle={onToggle} />)

    const group = screen.getByRole('group', { name: 'Filter' })
    expect(within(group).getByRole('button', { name: 'Favoriten' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(within(group).getAllByRole('button')).toHaveLength(1)
    // Without a choice in progress there is nothing to narrow to.
    expect(within(group).queryByRole('button', { name: 'Nur ausgewählte' })).not.toBeInTheDocument()

    await user.click(within(group).getByRole('button', { name: 'Favoriten' }))
    expect(onToggle.mock.calls).toEqual([['favorites']])
  })

  it('is operable by keyboard', async () => {
    const user = userEvent.setup()
    const onToggle = vi.fn()
    renderWithProviders(<AssetFilterChips value={{ favorites: false }} onToggle={onToggle} />)

    await user.tab()
    expect(screen.getByRole('button', { name: 'Favoriten' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(onToggle).toHaveBeenCalledWith('favorites')
  })

  it('sets itself apart from a type group before it when separated', () => {
    const { container } = renderWithProviders(
      <AssetFilterChips value={{ favorites: false }} onToggle={vi.fn()} separated />,
    )

    expect(container.querySelector('[role="separator"], hr')).toBeInTheDocument()
  })

  it('offers "Nur ausgewählte" where something is being chosen', async () => {
    const user = userEvent.setup()
    const onToggle = vi.fn()
    renderWithProviders(
      <AssetFilterChips value={{ favorites: false, selectedOnly: false }} onToggle={onToggle} />,
    )

    await user.click(screen.getByRole('button', { name: 'Nur ausgewählte' }))
    expect(onToggle).toHaveBeenCalledWith('selectedOnly')
  })
})
