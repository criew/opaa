import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/test-utils'
import AssetTile from './AssetTile'
import type { AssetTileData } from './assetTileData'

const tile: AssetTileData = {
  assetType: 'KNOWLEDGE_LIBRARY',
  assetId: 'library-satzungen',
  name: 'Satzungen & Gebührenordnungen',
  description: 'Alle geltenden Satzungen der Stadt.',
  isPublic: true,
  favorite: false,
  figures: '12 Dokumente · in 2 Spaces',
  responsible: { label: 'Bürgerbüro Rheinfurt', group: true },
  updatedAt: '2026-09-30T08:00:00Z',
}

describe('AssetTile (#2131)', () => {
  it('shows badge, globe, star, title, description, figures, responsibility and date', () => {
    renderWithProviders(
      <AssetTile
        tile={tile}
        mode={{ kind: 'link', to: '/libraries/library-satzungen' }}
        onFavoriteChange={vi.fn(async () => undefined)}
      />,
      { withRouter: true },
    )

    expect(screen.getByText('Wissen')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Für alle Konten freigegeben' })).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '„Satzungen & Gebührenordnungen“ als Favorit markieren' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Satzungen & Gebührenordnungen' })).toHaveAttribute(
      'href',
      '/libraries/library-satzungen',
    )
    expect(screen.getByText('Alle geltenden Satzungen der Stadt.')).toBeInTheDocument()
    expect(screen.getByText('12 Dokumente · in 2 Spaces')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Zuständige Gruppe' })).toBeInTheDocument()
    expect(screen.getByText('Aktualisiert am 30.09.2026')).toBeInTheDocument()
  })

  it('is a checkbox named by its title and described by the rest in the selection mode', () => {
    renderWithProviders(
      <AssetTile tile={tile} mode={{ kind: 'select', selected: true, onToggle: vi.fn() }} />,
    )

    const checkbox = screen.getByRole('checkbox', { name: 'Satzungen & Gebührenordnungen' })
    expect(checkbox).toHaveAttribute('aria-checked', 'true')
    expect(checkbox).toHaveAccessibleDescription(
      'Alle geltenden Satzungen der Stadt. 12 Dokumente · in 2 Spaces',
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('toggles the choice on a click and on the keyboard', async () => {
    const onToggle = vi.fn()
    renderWithProviders(
      <AssetTile tile={tile} mode={{ kind: 'select', selected: false, onToggle }} />,
    )
    const user = userEvent.setup()

    await user.click(screen.getByRole('checkbox'))
    screen.getByRole('checkbox').focus()
    await user.keyboard(' ')
    await user.keyboard('{Enter}')

    expect(onToggle).toHaveBeenCalledTimes(3)
  })

  it('sets only the favorite when the star is clicked in the selection mode', async () => {
    const onToggle = vi.fn()
    const onFavoriteChange = vi.fn(async () => undefined)
    renderWithProviders(
      <AssetTile
        tile={tile}
        mode={{ kind: 'select', selected: false, onToggle }}
        onFavoriteChange={onFavoriteChange}
      />,
    )
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /als Favorit markieren/ }))

    expect(onFavoriteChange).toHaveBeenCalledWith(true)
    expect(onToggle).not.toHaveBeenCalled()
  })

  it('keeps the choice when read only', async () => {
    const onToggle = vi.fn()
    renderWithProviders(
      <AssetTile tile={tile} mode={{ kind: 'select', selected: true, onToggle, readOnly: true }} />,
    )
    const user = userEvent.setup()

    await user.click(screen.getByRole('checkbox'))

    expect(onToggle).not.toHaveBeenCalled()
    expect(screen.getByRole('checkbox')).toHaveAttribute('aria-readonly', 'true')
  })

  it('leaves out what the source does not know', () => {
    renderWithProviders(
      <AssetTile
        tile={{ assetType: 'PROMPT_LIBRARY', assetId: 'p', name: 'Textbausteine' }}
        mode={{ kind: 'select', selected: false, onToggle: vi.fn() }}
      />,
    )

    const checkbox = screen.getByRole('checkbox', { name: 'Textbausteine' })
    expect(checkbox).not.toHaveAttribute('aria-describedby')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(within(document.body).getByText('Prompts')).toBeInTheDocument()
  })
})
