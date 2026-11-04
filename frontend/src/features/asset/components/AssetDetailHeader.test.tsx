import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
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
      editLabel: 'Name und Beschreibung ändern',
      canEdit: false,
      onSave: vi.fn(async () => {}),
    },
    extent: '12 Prompts',
    responsible: { label: 'Bürgerbüro', group: true },
    updatedAt: '2026-10-03T10:00:00Z',
    ...overrides,
  }
  return renderWithProviders(<AssetDetailHeader {...props} />, { withRouter: true })
}

describe('AssetDetailHeader (#2208)', () => {
  it('shows type badge, globe, name, description and the shared figures', () => {
    renderHeader({ isPublic: true, spaceCount: 3, responsibleHref: '?tab=sharing' })

    expect(screen.getByText('Prompts')).toBeInTheDocument()
    expect(screen.getByLabelText('Für alle Konten freigegeben')).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 1, name: 'Textbausteine Bürgerbüro' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Standardantworten für die Bürgerberatung')).toBeInTheDocument()
    expect(screen.getByText('12 Prompts')).toBeInTheDocument()
    expect(screen.getByText('in 3 Spaces')).toBeInTheDocument()
    // the responsible party is a plain figure that leads into the tab of the permissions
    expect(screen.getByRole('link', { name: 'Bürgerbüro' })).toHaveAttribute(
      'href',
      expect.stringContaining('tab=sharing'),
    )
    expect(screen.getByText('Aktualisiert am 03.10.2026')).toBeInTheDocument()
  })

  it('marks a role that comes from the administrative bypass', () => {
    const { unmount } = renderHeader({ administrative: true })
    expect(screen.getByText('administrativ')).toBeInTheDocument()
    unmount()

    renderHeader({ administrative: false })
    expect(screen.queryByText('administrativ')).not.toBeInTheDocument()
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

  // As in the catalog: while a press is running, the star is only aria-disabled - a natively
  // disabled button would lose focus -, and a second press triggers no second call.
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

  const editableHeadline = {
    idPrefix: 'prompt-library-detail',
    nameLabel: 'Name der Prompt-Bibliothek',
    editLabel: 'Name und Beschreibung ändern',
    canEdit: true,
    onSave: vi.fn(async () => {}),
  }

  it('offers „Name und Beschreibung ändern" and, with the right, „Löschen" behind a separator in „⋯"', async () => {
    const onDelete = vi.fn()
    renderHeader({ headline: editableHeadline, onDelete })
    const user = userEvent.setup()

    await user.click(
      screen.getByRole('button', { name: 'Weitere Aktionen für „Textbausteine Bürgerbüro“' }),
    )
    const menu = await screen.findByRole('menu')
    const items = within(menu).getAllByRole('menuitem')
    expect(items.map((item) => item.textContent)).toEqual([
      'Name und Beschreibung ändern',
      'Löschen',
    ])
    expect(within(menu).getByRole('separator')).toBeInTheDocument()

    await user.click(within(menu).getByRole('menuitem', { name: 'Löschen' }))
    expect(onDelete).toHaveBeenCalledTimes(1)
  })

  it('opens the edit dialog from „Name und Beschreibung ändern" in the menu', async () => {
    renderHeader({ headline: editableHeadline })
    const user = userEvent.setup()

    await user.click(
      screen.getByRole('button', { name: 'Weitere Aktionen für „Textbausteine Bürgerbüro“' }),
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Name und Beschreibung ändern' }))

    const dialog = await screen.findByRole('dialog', { name: 'Name und Beschreibung ändern' })
    expect(
      within(dialog).getByRole('textbox', { name: 'Name der Prompt-Bibliothek' }),
    ).toBeInTheDocument()
  })

  it('offers the edit entry alone without the right to delete', async () => {
    renderHeader({ headline: editableHeadline })
    const user = userEvent.setup()

    await user.click(
      screen.getByRole('button', { name: 'Weitere Aktionen für „Textbausteine Bürgerbüro“' }),
    )
    const menu = await screen.findByRole('menu')
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['Name und Beschreibung ändern'])
  })

  it('shows no „⋯" when it would stay empty', () => {
    renderHeader()

    expect(
      screen.queryByRole('button', { name: 'Weitere Aktionen für „Textbausteine Bürgerbüro“' }),
    ).not.toBeInTheDocument()
  })

  it('carries no pencil to edit name and description in the head', () => {
    renderHeader({ headline: editableHeadline })

    expect(
      screen.queryByRole('button', { name: 'Name und Beschreibung ändern' }),
    ).not.toBeInTheDocument()
  })
})
