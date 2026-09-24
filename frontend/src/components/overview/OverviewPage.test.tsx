import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import TableCell from '@mui/material/TableCell'
import { renderWithProviders } from '../../test/test-utils'
import OverviewPage, { OverviewCard } from './OverviewPage'

interface Item {
  id: string
  name: string
  description: string
}

const items: Item[] = [
  { id: 'a', name: 'Widerspruchsstelle', description: 'Laufende Widersprüche' },
  { id: 'b', name: 'Bauamt', description: 'Bauanträge und Genehmigungen' },
]

function renderOverview(props: Partial<React.ComponentProps<typeof OverviewPage<Item>>> = {}) {
  return renderWithProviders(
    <OverviewPage<Item>
      title="Beispiele"
      heading={(count) => `${count} Beispiele`}
      createLabel="Neues Beispiel"
      onCreate={() => {}}
      storageKey="test"
      items={items}
      itemKey={(item) => item.id}
      searchText={(item) => `${item.name} ${item.description}`}
      columns={[
        { key: 'name', label: 'Name' },
        { key: 'description', label: 'Beschreibung' },
      ]}
      renderCard={(item) => <OverviewCard to={`/items/${item.id}`}>{item.name}</OverviewCard>}
      renderRow={(item) => (
        <>
          <TableCell>{item.name}</TableCell>
          <TableCell>{item.description}</TableCell>
        </>
      )}
      {...props}
    />,
    { withRouter: true },
  )
}

describe('OverviewPage (#1913)', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('shows the heading with the visible count and the create button', () => {
    const onCreate = vi.fn()
    renderOverview({ onCreate })

    expect(screen.getByRole('heading', { level: 1, name: '2 Beispiele' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neues Beispiel' })).toBeInTheDocument()

    screen.getByRole('button', { name: 'Neues Beispiel' }).click()
    expect(onCreate).toHaveBeenCalled()
  })

  it('filters client-side over the supplied search text and updates the count', async () => {
    const user = userEvent.setup()
    renderOverview()

    // The description is part of searchText, so a term only found there still matches.
    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'bauanträge')

    expect(screen.getByRole('link', { name: 'Bauamt' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Widerspruchsstelle' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '1 Beispiele' })).toBeInTheDocument()
  })

  it('names the query when nothing matches', async () => {
    const user = userEvent.setup()
    renderOverview()

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'Ordnungsamt')

    expect(screen.getByText(/Kein Eintrag passt zu „Ordnungsamt“/)).toBeInTheDocument()
  })

  it('switches to the table view and remembers the choice per overview', async () => {
    const user = userEvent.setup()
    const { unmount } = renderOverview()

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Tabelle' }))

    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Beschreibung' })).toBeInTheDocument()
    expect(window.localStorage.getItem('opaa.overview.test.view')).toBe('table')

    // A second overview keeps its own choice - the key carries the overview's name.
    unmount()
    renderOverview({ storageKey: 'other' })
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('starts in the remembered view instead of the default', () => {
    window.localStorage.setItem('opaa.overview.test.view', 'table')
    renderOverview()

    expect(screen.getByRole('table')).toBeInTheDocument()
  })

  it('shows the empty state without search and switch when there is nothing to list', () => {
    renderOverview({ items: [], emptyState: <p>Noch nichts da.</p> })

    expect(screen.getByText('Noch nichts da.')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Suchen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Tabelle' })).not.toBeInTheDocument()
  })

  it('keeps the plain title while the first load is still running', () => {
    renderOverview({ items: [], isLoading: true })

    expect(screen.getByRole('heading', { level: 1, name: 'Beispiele' })).toBeInTheDocument()
    expect(screen.getByLabelText('Beispiele werden geladen')).toBeInTheDocument()
  })

  it('shows a load error above the list', () => {
    renderOverview({ error: 'Laden fehlgeschlagen' })

    expect(screen.getByRole('alert')).toHaveTextContent('Laden fehlgeschlagen')
  })
})
