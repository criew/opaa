import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import { ASSET_TYPES } from './assetTypeRegistry'
import AssetFilterBar from './AssetFilterBar'

function follows(first: Element, second: Element): boolean {
  return Boolean(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING)
}

describe('AssetFilterBar (guidelines 5.11)', () => {
  it('orders search, type and favorites in one row, the type named without a title', async () => {
    const user = userEvent.setup()
    const onType = vi.fn()
    renderWithProviders(
      <AssetFilterBar
        search={{ value: '', onChange: vi.fn() }}
        types={{ offered: ASSET_TYPES, value: undefined, onChange: onType }}
        filters={{ favorites: false }}
        onToggle={vi.fn()}
      />,
    )

    const search = screen.getByRole('searchbox', { name: 'Suchen' })
    const type = screen.getByRole('group', { name: 'Typ' })
    const favorites = screen.getByRole('button', { name: 'Favoriten' })
    // #2207: the group is named "Typ" without a visible title.
    expect(screen.queryByText('Typ')).not.toBeInTheDocument()
    expect(type).toHaveAttribute('aria-label', 'Typ')
    expect(follows(search, type)).toBe(true)
    expect(follows(type, favorites)).toBe(true)
    expect(within(type).getByRole('button', { name: 'Alle' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    await user.click(within(type).getByRole('button', { name: 'Prompts' }))
    expect(onType).toHaveBeenCalledWith('PROMPT_LIBRARY')
  })

  it('offers no type filter for a single type, and clears the search', async () => {
    const user = userEvent.setup()
    const onSearch = vi.fn()
    renderWithProviders(
      <AssetFilterBar
        search={{ value: 'recht', onChange: onSearch }}
        types={{ offered: ASSET_TYPES.slice(0, 1), value: undefined, onChange: vi.fn() }}
        filters={{ favorites: false, selectedOnly: false }}
        onToggle={vi.fn()}
      />,
    )

    expect(screen.queryByRole('group', { name: 'Typ' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nur ausgewählte' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Suche zurücksetzen' }))
    expect(onSearch).toHaveBeenCalledWith('')
  })
})
