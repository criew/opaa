import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../../test/test-utils'
import SelectionDialog from './SelectionDialog'
import type { BrowseEntry, BrowseResult, Crumb, SelectionItem, SelectionSource } from './selection'

const folder = (key: string, name = key): BrowseEntry => ({
  key,
  name,
  selectable: true,
  openable: true,
})
const flat = (key: string, name = key): BrowseEntry => ({
  key,
  name,
  selectable: true,
  openable: false,
})

/** A small folder tree: the outermost level holds „Akten“ and „Archiv“, „Akten“ holds two years. */
function treeSource(levels: Record<string, BrowseEntry[]>, patch: Partial<SelectionSource> = {}) {
  const source: SelectionSource = {
    rootLabel: 'Ablage',
    noun: ['Ordner', 'Ordner'],
    list: vi.fn(async (level: Crumb[]): Promise<BrowseResult> => ({
      entries: levels[level.map((crumb) => crumb.key).join('/')] ?? [],
      complete: true,
      message: null,
    })),
    serverSearch: () => false,
    searchLabel: () => 'Ordner suchen',
    nested: true,
    ...patch,
  }
  return source
}

const TREE = {
  '': [folder('akten', 'Akten'), folder('archiv', 'Archiv')],
  akten: [folder('2025'), folder('2026')],
}

function renderDialog(
  source: SelectionSource,
  value: SelectionItem[] = [],
  { onApply = vi.fn(), onClose = vi.fn(), startManual = false } = {},
) {
  renderWithProviders(
    <SelectionDialog
      open
      title="Ordner auswählen"
      source={source}
      value={value}
      onApply={onApply}
      onClose={onClose}
      startManual={startManual}
    />,
  )
  return { onApply, onClose }
}

describe('SelectionDialog', () => {
  it('opens levels through the breadcrumb and back, and marks a partly chosen folder mixed', async () => {
    const user = userEvent.setup()
    renderDialog(treeSource(TREE))

    await user.click(await screen.findByRole('button', { name: '„Akten“ öffnen' }))
    const crumbs = screen.getByRole('navigation', { name: 'Ebenen' })
    expect(within(crumbs).getByText('Akten')).toHaveAttribute('aria-current', 'location')
    await user.click(await screen.findByRole('checkbox', { name: '2025' }))

    await user.click(within(crumbs).getByRole('button', { name: 'Ablage' }))
    const akten = await screen.findByRole('checkbox', { name: 'Akten' })
    expect(akten).not.toBeChecked()
    expect(akten).toHaveAttribute('aria-checked', 'mixed')
    expect(screen.getByRole('checkbox', { name: 'Archiv' })).not.toHaveAttribute('aria-checked')
  })

  it('gives an entry that only leads deeper a single tab stop', async () => {
    const user = userEvent.setup()
    const site = (key: string): BrowseEntry => ({
      key,
      name: key,
      selectable: false,
      openable: true,
    })
    renderDialog(
      treeSource({ '': [site('Rathaus'), site('Bauamt')], Rathaus: [flat('Dokumente')] }),
    )

    const open = await screen.findByRole('button', { name: '„Rathaus“ öffnen' })
    // the chevron beside the name is a pointer target only
    expect(screen.getAllByRole('button', { name: '„Rathaus“ öffnen' })).toHaveLength(1)
    open.focus()
    await user.tab()
    expect(screen.getByRole('button', { name: '„Bauamt“ öffnen' })).toHaveFocus()
    await user.click(open)
    expect(await screen.findByRole('checkbox', { name: 'Dokumente' })).toBeInTheDocument()
  })

  it('shows the search only from seven entries of a level on', async () => {
    const six = Array.from({ length: 6 }, (_, i) => flat(`s${i}`, `Space ${i + 1}`))
    const { unmount } = renderWithProviders(
      <SelectionDialog
        open
        title="Spaces auswählen"
        source={treeSource({ '': six }, { nested: false })}
        value={[]}
        onApply={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    await screen.findByRole('checkbox', { name: 'Space 6' })
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
    unmount()

    const user = userEvent.setup()
    renderDialog(treeSource({ '': [...six, flat('s6', 'Bauamt')] }, { nested: false }))
    const search = await screen.findByRole('searchbox', { name: 'Ordner suchen' })
    await user.type(search, 'bau')
    expect(screen.getByRole('checkbox', { name: 'Bauamt' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Space 1' })).not.toBeInTheDocument()
  })

  it('pins the stored selection of another level ticked above the list', async () => {
    const stored: SelectionItem = {
      key: '2026',
      name: '2026',
      trail: [{ key: 'akten', name: 'Akten' }],
    }
    renderDialog(treeSource({ '': [flat('archiv', 'Archiv')] }), [stored])

    const pinned = await screen.findByRole('group', { name: 'Bereits ausgewählt' })
    expect(within(pinned).getByRole('checkbox', { name: 'Akten / 2026' })).toBeChecked()
    const list = screen.getByRole('group', { name: 'Inhalt von Ablage' })
    expect(pinned.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText('1 ausgewählt')).toBeInTheDocument()
  })

  it('counts only the choosable entries in „Alle n wählbaren auswählen“', async () => {
    const user = userEvent.setup()
    renderDialog(
      treeSource(
        {
          '': [
            flat('a', 'Bauamt'),
            flat('b', 'Kämmerei'),
            { ...flat('c', 'Personal'), blocked: 'Nicht wählbar' },
          ],
        },
        { nested: false },
      ),
    )

    await user.click(await screen.findByRole('checkbox', { name: /Alle 2 wählbaren auswählen/ }))
    expect(screen.getByRole('checkbox', { name: 'Bauamt' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Kämmerei' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Personal' })).toBeDisabled()
    expect(screen.getByText('2 ausgewählt')).toBeInTheDocument()
  })

  it('offers only „Alle geladenen auswählen“ on an incomplete list', async () => {
    renderDialog(
      treeSource(
        {},
        {
          nested: false,
          list: async () => ({
            entries: [flat('a', 'Bauamt'), flat('b', 'Kämmerei')],
            complete: false,
            message: 'gekürzt',
          }),
        },
      ),
    )

    expect(
      await screen.findByRole('checkbox', { name: /Alle geladenen auswählen/ }),
    ).toBeInTheDocument()
    expect(screen.getByText('Liste unvollständig.')).toBeInTheDocument()
    expect(screen.queryByText(/Alle 2/)).not.toBeInTheDocument()
  })

  it('adds an object typed by hand', async () => {
    const user = userEvent.setup()
    const resolve = vi.fn(async (text: string) =>
      text === '/fehlt' ? 'Den Ordner gibt es nicht.' : { key: text, name: text, trail: [] },
    )
    renderDialog(
      treeSource(TREE, {
        manual: { label: 'Pfad', placeholder: '/Akten/2026', kind: 'add', resolve },
      }),
    )

    await user.click(await screen.findByRole('button', { name: 'Von Hand eintragen …' }))
    const field = screen.getByLabelText('Pfad')
    await user.type(field, '/fehlt{Enter}')
    expect(await screen.findByText('Den Ordner gibt es nicht.')).toBeInTheDocument()
    await user.clear(field)
    await user.type(field, '/Projekte')
    await user.click(screen.getByRole('button', { name: 'Hinzufügen' }))
    await waitFor(() => expect(screen.getByText('1 ausgewählt')).toBeInTheDocument())
  })

  it('hands the selection back only through „Übernehmen“', async () => {
    const user = userEvent.setup()
    const { onApply, onClose } = renderDialog(treeSource(TREE))

    await user.click(await screen.findByRole('checkbox', { name: 'Archiv' }))
    expect(onApply).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Übernehmen' }))

    expect(onApply).toHaveBeenCalledWith([{ key: 'archiv', name: 'Archiv', trail: [] }])
    expect(onClose).toHaveBeenCalled()
  })
})
