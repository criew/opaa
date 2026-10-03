import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import AssetDetailHeader, { type AssetDetailHeaderProps } from './AssetDetailHeader'

function renderHeader(overrides: Partial<AssetDetailHeaderProps> = {}) {
  const props: AssetDetailHeaderProps = {
    assetType: 'PROMPT_LIBRARY',
    assetId: 'prompt-library-1',
    name: 'Textbausteine Bürgerbüro',
    description: 'Standardantworten für die Bürgerberatung',
    isPublic: false,
    headline: {
      idPrefix: 'prompt-library-detail',
      nameLabel: 'Name der Prompt-Bibliothek',
      editLabel: 'Name und Beschreibung bearbeiten',
      canEdit: false,
      onSave: vi.fn(async () => {}),
    },
    extent: '12 Prompts',
    responsible: { label: 'Bürgerbüro', group: true },
    updatedAt: '2026-10-03T10:00:00Z',
    mayUseInSpace: false,
    ...overrides,
  }
  return renderWithProviders(<AssetDetailHeader {...props} />, { withRouter: true })
}

describe('AssetDetailHeader (#2208)', () => {
  it('shows type badge, globe, name, description and the shared figures', () => {
    renderHeader({ isPublic: true, spaceCount: 3 })

    expect(screen.getByText('Prompts')).toBeInTheDocument()
    expect(screen.getByLabelText('Für alle Konten freigegeben')).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 1, name: 'Textbausteine Bürgerbüro' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Standardantworten für die Bürgerberatung')).toBeInTheDocument()
    expect(screen.getByText('12 Prompts')).toBeInTheDocument()
    expect(screen.getByText('in 3 Spaces')).toBeInTheDocument()
    expect(screen.getByText('Bürgerbüro')).toBeInTheDocument()
    expect(screen.getByTitle('Zuständige Gruppe')).toBeInTheDocument()
    expect(screen.getByText('Aktualisiert am 03.10.2026')).toBeInTheDocument()
  })

  it('carries no globe for an asset that is not released to all accounts', () => {
    renderHeader({ isPublic: false })

    expect(screen.queryByLabelText('Für alle Konten freigegeben')).not.toBeInTheDocument()
  })

  it('names the next press of the star and reports the wish to the page', async () => {
    const onFavoriteChange = vi.fn(async () => {})
    renderHeader({ favorite: false, onFavoriteChange })
    const user = userEvent.setup()

    await user.click(
      screen.getByRole('button', { name: '„Textbausteine Bürgerbüro“ als Favorit markieren' }),
    )

    expect(onFavoriteChange).toHaveBeenCalledWith(true)
  })

  it('names the star as removal for a favorite', () => {
    renderHeader({ favorite: true, onFavoriteChange: vi.fn(async () => {}) })

    expect(
      screen.getByRole('button', {
        name: '„Textbausteine Bürgerbüro“ aus den Favoriten entfernen',
      }),
    ).toBeInTheDocument()
  })

  // Wie im Katalog (#2117): Solange ein Druck läuft, ist der Stern nur aria-disabled - ein nativ
  // gesperrter Knopf verlöre den Fokus -, und ein zweiter Druck löst keinen zweiten Aufruf aus.
  it('locks the star by a ref while a press is pending and keeps the focus', async () => {
    let finish: () => void = () => {}
    const onFavoriteChange = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    renderHeader({ favorite: false, onFavoriteChange })
    const user = userEvent.setup()
    const star = screen.getByRole('button', {
      name: '„Textbausteine Bürgerbüro“ als Favorit markieren',
    })

    await user.click(star)
    expect(star).toHaveAttribute('aria-disabled', 'true')
    expect(star).not.toBeDisabled()
    expect(star).toHaveFocus()
    await user.click(star)
    expect(onFavoriteChange).toHaveBeenCalledTimes(1)

    finish()
    await waitFor(() => expect(star).not.toHaveAttribute('aria-disabled'))
  })

  it('shows no star while the favorite is unknown', () => {
    renderHeader({ favorite: undefined, onFavoriteChange: vi.fn(async () => {}) })

    expect(screen.queryByRole('button', { name: /favorit/i })).not.toBeInTheDocument()
  })

  it('offers „In Space verwenden" and, with the right, „Löschen" behind a separator in „⋯"', async () => {
    const onDelete = vi.fn()
    renderHeader({ mayUseInSpace: true, onDelete })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Weitere Aktionen' }))
    const menu = await screen.findByRole('menu')
    const items = within(menu).getAllByRole('menuitem')
    expect(items.map((item) => item.textContent)).toEqual(['In Space verwenden', 'Löschen'])
    expect(within(menu).getByRole('separator')).toBeInTheDocument()

    await user.click(within(menu).getByRole('menuitem', { name: 'Löschen' }))
    expect(onDelete).toHaveBeenCalledTimes(1)
  })

  it('opens the catalog dialog from „In Space verwenden" in the menu', async () => {
    renderHeader({ mayUseInSpace: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Weitere Aktionen' }))
    await user.click(await screen.findByRole('menuitem', { name: 'In Space verwenden' }))

    expect(
      await screen.findByRole('dialog', { name: '„Textbausteine Bürgerbüro“ in Space verwenden' }),
    ).toBeInTheDocument()
  })

  it('offers no „Löschen" without the right to delete', async () => {
    renderHeader({ mayUseInSpace: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Weitere Aktionen' }))
    const menu = await screen.findByRole('menu')
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['In Space verwenden'])
  })

  it('shows no „⋯" when it would stay empty', () => {
    renderHeader({ mayUseInSpace: false })

    expect(screen.queryByRole('button', { name: 'Weitere Aktionen' })).not.toBeInTheDocument()
  })

  it('has no big „In Space verwenden" button in the head', () => {
    renderHeader({ mayUseInSpace: true })

    expect(screen.queryByRole('button', { name: /in space verwenden/i })).not.toBeInTheDocument()
  })
})
