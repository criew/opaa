import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import { renderWithProviders } from '@/test/test-utils'
import RowMenu from './RowMenu'

const NAME = 'Weitere Aktionen für „Bauamt“'

/**
 * The one „⋯" menu (guidelines 5.6): trigger, accessible names, structure and keyboard operation
 * are the same wherever further actions open.
 */
describe('RowMenu', () => {
  it('names the object in one form and announces the menu it controls', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RowMenu name="Bauamt" items={[{ label: 'Umbenennen', onClick: vi.fn() }]} />,
    )

    const trigger = screen.getByRole('button', { name: NAME })
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu')
    expect(trigger).toHaveAttribute('aria-expanded', 'false')

    await user.click(trigger)
    const menu = await screen.findByRole('menu', { name: NAME })
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(trigger).toHaveAttribute('aria-controls', menu.id)
  })

  it('leaves the name to the button, not to the tooltip wrapper', () => {
    renderWithProviders(
      <RowMenu name="Bauamt" items={[{ label: 'Umbenennen', onClick: vi.fn() }]} />,
    )
    const trigger = screen.getByRole('button', { name: NAME })
    expect(trigger.parentElement).not.toHaveAttribute('aria-label')
  })

  it('appends a distinguishing detail after the name', () => {
    renderWithProviders(
      <RowMenu
        name="CI-Token"
        detail="von Maria Musterfrau"
        items={[{ label: 'Sperren', onClick: vi.fn() }]}
      />,
    )
    expect(
      screen.getByRole('button', { name: 'Weitere Aktionen für „CI-Token“ von Maria Musterfrau' }),
    ).toBeInTheDocument()
  })

  it('is operated with the keyboard: arrows, Enter, Escape, focus back on the trigger', async () => {
    const user = userEvent.setup()
    const rename = vi.fn()
    const archive = vi.fn()
    renderWithProviders(
      <RowMenu
        name="Bauamt"
        items={[
          { label: 'Umbenennen', onClick: rename },
          { label: 'Archivieren', onClick: archive },
        ]}
      />,
    )
    const trigger = screen.getByRole('button', { name: NAME })

    trigger.focus()
    await user.keyboard('{Enter}')
    const menu = await screen.findByRole('menu', { name: NAME })
    await waitFor(() =>
      expect(within(menu).getByRole('menuitem', { name: 'Umbenennen' })).toHaveFocus(),
    )
    await user.keyboard('{ArrowDown}')
    expect(within(menu).getByRole('menuitem', { name: 'Archivieren' })).toHaveFocus()
    await user.keyboard('{ArrowUp}')
    expect(within(menu).getByRole('menuitem', { name: 'Umbenennen' })).toHaveFocus()

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
    expect(rename).not.toHaveBeenCalled()

    await user.keyboard('{Enter}')
    await screen.findByRole('menu', { name: NAME })
    await user.keyboard('{ArrowDown}{Enter}')
    expect(archive).toHaveBeenCalledOnce()
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
  })

  it('puts destructive entries last, set apart by a divider, and only those in danger text', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RowMenu
        name="Bauamt"
        items={[
          { label: 'Löschen', onClick: vi.fn(), danger: true },
          { label: 'Umbenennen', onClick: vi.fn() },
          { label: 'Archivieren', onClick: vi.fn() },
        ]}
      />,
    )
    await user.click(screen.getByRole('button', { name: NAME }))
    const menu = await screen.findByRole('menu', { name: NAME })

    const entries = within(menu).getAllByRole('menuitem')
    expect(entries.map((entry) => entry.textContent)).toEqual([
      'Umbenennen',
      'Archivieren',
      'Löschen',
    ])
    const separator = within(menu).getByRole('separator')
    expect(
      separator.compareDocumentPosition(entries[2]) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    expect(
      separator.compareDocumentPosition(entries[1]) & Node.DOCUMENT_POSITION_PRECEDING,
    ).toBeTruthy()
    expect(entries[2]).toHaveAttribute('data-danger', 'true')
    expect(entries[0]).not.toHaveAttribute('data-danger')
  })

  it('draws no divider when every entry is destructive', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RowMenu name="Bauamt" items={[{ label: 'Entziehen', onClick: vi.fn(), danger: true }]} />,
    )
    await user.click(screen.getByRole('button', { name: NAME }))
    const menu = await screen.findByRole('menu', { name: NAME })
    expect(within(menu).queryByRole('separator')).not.toBeInTheDocument()
  })

  it('names the reason of a disabled entry once, read out with every entry it concerns', async () => {
    const user = userEvent.setup()
    const reason = 'Das eigene Konto lässt sich nicht sperren oder löschen.'
    renderWithProviders(
      <RowMenu
        name="Bauamt"
        items={[
          { label: 'Bearbeiten', onClick: vi.fn() },
          { label: 'Sperren', onClick: vi.fn(), disabled: true, disabledReason: reason },
          {
            label: 'Löschen',
            onClick: vi.fn(),
            danger: true,
            disabled: true,
            disabledReason: reason,
          },
        ]}
      />,
    )
    await user.click(screen.getByRole('button', { name: NAME }))
    const menu = await screen.findByRole('menu', { name: NAME })

    expect(within(menu).getAllByText(reason)).toHaveLength(1)
    for (const label of ['Sperren', 'Löschen']) {
      const entry = within(menu).getByRole('menuitem', { name: label })
      expect(entry).toHaveAttribute('aria-disabled', 'true')
      expect(entry).toHaveAccessibleDescription(reason)
      expect(entry).not.toHaveAttribute('data-danger')
    }
  })

  it('shows a note below the entries', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RowMenu
        name="Bauamt"
        items={[{ label: 'Bearbeiten', onClick: vi.fn() }]}
        note="Sperren und Löschen erfolgen beim Identitätsanbieter."
      />,
    )
    await user.click(screen.getByRole('button', { name: NAME }))
    const menu = await screen.findByRole('menu', { name: NAME })
    expect(
      within(menu).getByText('Sperren und Löschen erfolgen beim Identitätsanbieter.'),
    ).toBeInTheDocument()
  })

  it('leads elsewhere through an entry with a link', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RowMenu name="Bauamt" items={[{ label: 'Anbieter verwalten', to: '/admin/anbieter' }]} />,
      { withRouter: true },
    )
    await user.click(screen.getByRole('button', { name: NAME }))
    const menu = await screen.findByRole('menu', { name: NAME })
    expect(within(menu).getByRole('menuitem', { name: 'Anbieter verwalten' })).toHaveAttribute(
      'href',
      '/admin/anbieter',
    )
  })

  it('aligns the entries when they carry icons', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <RowMenu
        name="Bauamt"
        items={[{ label: 'Bearbeiten', onClick: vi.fn(), icon: EditOutlinedIcon }]}
      />,
    )
    await user.click(screen.getByRole('button', { name: NAME }))
    const entry = await screen.findByRole('menuitem', { name: 'Bearbeiten' })
    expect(entry.querySelector('svg')).not.toBeNull()
  })

  it('keeps the trigger disabled while an action of the row runs', () => {
    renderWithProviders(
      <RowMenu name="Bauamt" disabled items={[{ label: 'Bearbeiten', onClick: vi.fn() }]} />,
    )
    expect(screen.getByRole('button', { name: NAME })).toBeDisabled()
  })

  it('reports opening and closing', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    renderWithProviders(
      <RowMenu
        name="Bauamt"
        onOpenChange={onOpenChange}
        items={[{ label: 'Bearbeiten', onClick: vi.fn() }]}
      />,
    )
    await user.click(screen.getByRole('button', { name: NAME }))
    expect(onOpenChange).toHaveBeenLastCalledWith(true)
    await user.keyboard('{Escape}')
    expect(onOpenChange).toHaveBeenLastCalledWith(false)
  })
})
