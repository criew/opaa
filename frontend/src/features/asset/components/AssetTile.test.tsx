import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../../test/test-utils'
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
  extent: '12 Dokumente',
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
    expect(checkbox).toHaveAccessibleDescription('Alle geltenden Satzungen der Stadt. 12 Dokumente')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  // In a choice only what it is and how large it is counts.
  it('shows only title, description and extent in the selection mode', () => {
    renderWithProviders(
      <AssetTile tile={tile} mode={{ kind: 'select', selected: false, onToggle: vi.fn() }} />,
    )

    expect(screen.getByText('Satzungen & Gebührenordnungen')).toBeInTheDocument()
    expect(screen.getByText('Alle geltenden Satzungen der Stadt.')).toBeInTheDocument()
    expect(screen.getByText('12 Dokumente')).toBeInTheDocument()
    expect(screen.queryByText(/in 2 Spaces/)).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'Für alle Konten freigegeben' })).toBeNull()
    expect(screen.queryByText('Bürgerbüro Rheinfurt')).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'Zuständige Gruppe' })).toBeNull()
    expect(screen.queryByText(/Aktualisiert am/)).not.toBeInTheDocument()
  })

  // An open succession blocks a new association, so a choice keeps naming it.
  it('keeps the open succession in the selection mode', () => {
    renderWithProviders(
      <AssetTile
        tile={{ ...tile, successionOpen: true }}
        mode={{ kind: 'select', selected: false, onToggle: vi.fn() }}
      />,
    )

    expect(screen.getByText('Nachfolge offen')).toBeInTheDocument()
  })

  // Chosen shows as the check mark alone; the frame stays that of an unchosen tile.
  it('marks a chosen tile by the check mark, not by an accent frame', () => {
    const { unmount } = renderWithProviders(
      <AssetTile tile={tile} mode={{ kind: 'select', selected: false, onToggle: vi.fn() }} />,
    )
    const unchosen = getComputedStyle(screen.getByRole('checkbox').parentElement!)
    const frame = {
      borderWidth: unchosen.borderTopWidth,
      borderColor: unchosen.borderTopColor,
      background: unchosen.backgroundColor,
    }
    expect(screen.getByTestId('RadioButtonUncheckedIcon')).toBeInTheDocument()
    unmount()

    renderWithProviders(
      <AssetTile tile={tile} mode={{ kind: 'select', selected: true, onToggle: vi.fn() }} />,
    )
    const chosen = getComputedStyle(screen.getByRole('checkbox').parentElement!)
    expect(screen.getByTestId('CheckCircleIcon')).toBeInTheDocument()
    expect({
      borderWidth: chosen.borderTopWidth,
      borderColor: chosen.borderTopColor,
      background: chosen.backgroundColor,
    }).toEqual(frame)
  })

  // The stretched title link covers every unraised spot of the tile, so the globe must lie
  // above it to receive the pointer and show its tooltip. jsdom does no hit testing - hover opens
  // the tooltip even when covered - so only the position and z-index check guards the regression.
  it('raises the globe above the stretched title link and names it in a tooltip', async () => {
    renderWithProviders(
      <AssetTile tile={tile} mode={{ kind: 'link', to: '/libraries/library-satzungen' }} />,
      { withRouter: true },
    )
    const user = userEvent.setup()

    const globe = screen.getByRole('img', { name: 'Für alle Konten freigegeben' })
    expect(getComputedStyle(globe).position).toBe('relative')
    expect(Number(getComputedStyle(globe).zIndex)).toBeGreaterThanOrEqual(1)

    await user.hover(globe)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Für alle Konten freigegeben')
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

  describe('an asset that takes no new association (#3276)', () => {
    const REASON = 'Lässt sich nicht neu zuordnen, solange die Nachfolge offen ist.'

    it('locks an unchosen tile and names the reason outside the checkbox', async () => {
      const onToggle = vi.fn()
      renderWithProviders(
        <AssetTile
          tile={{ ...tile, successionOpen: true }}
          mode={{ kind: 'select', selected: false, onToggle, blockedReason: REASON }}
        />,
      )
      const user = userEvent.setup()
      const checkbox = screen.getByRole('checkbox', { name: 'Satzungen & Gebührenordnungen' })

      expect(checkbox).toHaveAttribute('aria-disabled', 'true')
      expect(checkbox).toHaveAccessibleDescription(expect.stringContaining(REASON))
      expect(checkbox).not.toContainElement(screen.getByText(REASON))

      await user.click(checkbox)
      await user.keyboard(' ')

      expect(onToggle).not.toHaveBeenCalled()
      expect(checkbox).toHaveAttribute('aria-checked', 'false')
    })

    it('keeps a locked tile in the tab order, so its reason can be heard', async () => {
      renderWithProviders(
        <AssetTile
          tile={{ ...tile, successionOpen: true }}
          mode={{ kind: 'select', selected: false, onToggle: vi.fn(), blockedReason: REASON }}
        />,
      )
      const user = userEvent.setup()

      await user.tab()

      expect(screen.getByRole('checkbox')).not.toBeDisabled()
      expect(screen.getByRole('checkbox')).toHaveFocus()
    })

    it('lets a chosen tile be unchosen and names the reason before it is', async () => {
      const onToggle = vi.fn()
      renderWithProviders(
        <AssetTile
          tile={{ ...tile, successionOpen: true }}
          mode={{ kind: 'select', selected: true, onToggle, blockedReason: REASON }}
        />,
      )
      const user = userEvent.setup()
      const checkbox = screen.getByRole('checkbox')

      expect(checkbox).not.toHaveAttribute('aria-disabled')
      expect(checkbox).toHaveAccessibleDescription(expect.stringContaining(REASON))

      await user.click(checkbox)

      expect(onToggle).toHaveBeenCalledTimes(1)
    })
  })
})
