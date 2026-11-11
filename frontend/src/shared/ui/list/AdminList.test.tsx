import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { renderWithProviders } from '../../../test/test-utils'
import AdminList, { ListPager, RowAction, type AdminListColumn } from './AdminList'

interface Row {
  id: string
  name: string
  count: number
}

const ROWS: Row[] = [
  { id: 'a', name: 'Alpha', count: 3 },
  { id: 'b', name: 'Beta', count: 12 },
]

const COLUMNS: AdminListColumn<Row>[] = [
  { id: 'name', head: 'Name', cell: (row) => row.name, card: 'title' },
  { id: 'count', head: 'Anzahl', align: 'right', cell: (row) => row.count },
]

function renderList(props: Partial<React.ComponentProps<typeof AdminList<Row>>> = {}) {
  return renderWithProviders(
    <MemoryRouter>
      <Routes>
        <Route
          path="/"
          element={
            <AdminList<Row>
              label="Einträge"
              rows={ROWS}
              rowKey={(row) => row.id}
              rowLabel={(row) => row.name}
              columns={COLUMNS}
              {...props}
            />
          }
        />
        <Route path="/items/:id" element={<p>Detailseite</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

/** Answers every media query with the given result, as a viewport below or above `md` would. */
function stubViewport(matches: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  )
}

describe('AdminList', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders a table with one head cell per column on a wide viewport', () => {
    renderList()

    const table = screen.getByRole('table', { name: 'Einträge' })
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((th) => th.textContent),
    ).toEqual(['Name', 'Anzahl'])
    expect(within(table).getAllByRole('row')).toHaveLength(ROWS.length + 1)
  })

  it('keeps cells and heads on one line, but lets a `wrap` column break', () => {
    stubViewport(true)
    renderList({
      columns: [
        ...COLUMNS,
        { id: 'result', head: 'Letzter Lauf', wrap: true, cell: () => 'Wartet auf Bestätigung' },
      ],
    })

    const table = screen.getByRole('table', { name: 'Einträge' })
    const name = within(table).getByText('Alpha').closest('td') as HTMLElement
    const result = within(table).getAllByText('Wartet auf Bestätigung')[0].closest('td')!
    expect(getComputedStyle(within(table).getByText('Letzter Lauf')).whiteSpace).toBe('nowrap')
    expect(getComputedStyle(name).whiteSpace).toBe('nowrap')
    expect(getComputedStyle(result).whiteSpace).toBe('normal')
  })

  it('renders the same rows as a card list on a narrow viewport', () => {
    stubViewport(false)
    renderList()

    expect(screen.queryByRole('table')).toBeNull()
    const list = screen.getByRole('list', { name: 'Einträge' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(ROWS.length)
    expect(within(list).getByRole('article', { name: 'Beta' })).toHaveTextContent('12')
  })

  it('makes the whole row one link named after the entry', async () => {
    renderList({ rowTarget: (row) => ({ to: `/items/${row.id}` }) })

    const links = screen.getAllByRole('link')
    expect(links.map((a) => a.getAttribute('aria-label'))).toEqual(['Alpha', 'Beta'])

    // a click anywhere in the row follows the row's link
    await userEvent.click(screen.getByText('12'))
    expect(await screen.findByText('Detailseite')).toBeInTheDocument()
  })

  it('follows the row link inside the focusable scroll region of a wide table', async () => {
    renderList({ minWidth: 900, rowTarget: (row) => ({ to: `/items/${row.id}` }) })

    expect(screen.getByRole('region', { name: 'Einträge, horizontal scrollbar' })).toHaveAttribute(
      'tabindex',
      '0',
    )
    await userEvent.click(screen.getByText('12'))
    expect(await screen.findByText('Detailseite')).toBeInTheDocument()
  })

  it('keeps what stands below the name out of the row link', () => {
    renderList({
      rowTarget: () => ({ onOpen: vi.fn() }),
      columns: [{ ...COLUMNS[0], below: () => <span>Gesperrt</span> }, COLUMNS[1]],
    })

    const opener = screen.getByRole('button', { name: 'Alpha' })
    const badge = within(opener.closest('td')!).getByText('Gesperrt')
    expect(opener).not.toContainElement(badge)
  })

  it('leaves the open details of a card alone when they are clicked', async () => {
    stubViewport(false)
    const onOpen = vi.fn()
    renderList({
      rowTarget: () => ({ onOpen, expanded: true }),
      rowDetail: (row) => (row.id === 'a' ? <p>Einzelheiten</p> : null),
    })

    await userEvent.click(screen.getByText('Einzelheiten'))
    expect(onOpen).not.toHaveBeenCalled()
    await userEvent.click(screen.getByText('3'))
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('opens a row in place with the keyboard and reports the expanded state', async () => {
    const onOpen = vi.fn()
    renderList({ rowTarget: (row) => ({ onOpen: () => onOpen(row.id), expanded: row.id === 'a' }) })

    const opener = screen.getByRole('button', { name: 'Alpha' })
    expect(opener).toHaveAttribute('aria-expanded', 'true')
    opener.focus()
    await userEvent.keyboard('{Enter}')
    expect(onOpen).toHaveBeenCalledWith('a')
  })

  it('does not follow the row link when the row action is used', async () => {
    const onAction = vi.fn()
    const onOpen = vi.fn()
    renderList({
      rowTarget: () => ({ onOpen }),
      rowActions: (row) => (
        <RowAction label={`${row.name} entfernen`} onClick={() => onAction(row.id)}>
          Entfernen
        </RowAction>
      ),
    })

    const action = screen.getByRole('button', { name: 'Beta entfernen' })
    // a row action is a secondary button, never a filled one
    expect(action).toHaveClass('MuiButton-outlined')
    await userEvent.click(action)
    expect(onAction).toHaveBeenCalledWith('b')
    expect(onOpen).not.toHaveBeenCalled()
  })
})

describe('ListPager', () => {
  it('is omitted while the list fits on one page', () => {
    renderWithProviders(<ListPager page={0} onPage={vi.fn()} total={20} size={20} />)

    expect(screen.queryByRole('navigation')).toBeNull()
  })

  it('shows the position and pages forward and back', async () => {
    const onPage = vi.fn()
    renderWithProviders(<ListPager page={1} onPage={onPage} total={90} size={20} />)

    const pager = screen.getByRole('navigation', { name: 'Seiten' })
    expect(pager).toHaveTextContent('Seite 2 von 5')
    await userEvent.click(within(pager).getByRole('button', { name: 'Nächste Seite' }))
    await userEvent.click(within(pager).getByRole('button', { name: 'Vorherige Seite' }))
    expect(onPage.mock.calls).toEqual([[2], [0]])
  })

  it('shows only the page number when the total is unknown', () => {
    renderWithProviders(<ListPager page={2} onPage={vi.fn()} hasMore={false} />)

    const pager = screen.getByRole('navigation', { name: 'Seiten' })
    expect(pager).toHaveTextContent('Seite 3')
    expect(within(pager).getByRole('button', { name: 'Nächste Seite' })).toBeDisabled()
  })
})
