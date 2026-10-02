import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
      items={items}
      itemKey={(item) => item.id}
      searchText={(item) => `${item.name} ${item.description}`}
      renderCard={(item) => <OverviewCard to={`/items/${item.id}`}>{item.name}</OverviewCard>}
      {...props}
    />,
    { withRouter: true },
  )
}

describe('OverviewPage (#1913)', () => {
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

  it('offers a count beside a fixed heading where the heading must stay the page name', async () => {
    const user = userEvent.setup()
    renderOverview({ heading: undefined, countLabel: (count) => `${count} Einträge` })

    expect(screen.getByRole('heading', { level: 1, name: 'Beispiele' })).toBeInTheDocument()
    expect(screen.getByText('2 Einträge')).toBeInTheDocument()

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'Bauamt')
    expect(screen.getByText('1 Einträge')).toBeInTheDocument()
  })

  it('leaves out the create button where an overview offers no creation', () => {
    renderOverview({ createLabel: undefined, onCreate: undefined })

    expect(screen.queryByRole('button', { name: 'Neues Beispiel' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '2 Beispiele' })).toBeInTheDocument()
  })

  it('names the query when nothing matches', async () => {
    const user = userEvent.setup()
    renderOverview()

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'Ordnungsamt')

    // Zweimal: einmal sichtbar, einmal im Statusbereich für Screenreader.
    expect(screen.getAllByText(/Kein Eintrag passt zu „Ordnungsamt“/)).toHaveLength(2)
  })

  it('announces the filter result in a live region (accessibility.md 2.8)', async () => {
    // Das Filtern verschiebt den Fokus nicht - ohne Statusbereich bliebe das Ergebnis am
    // Screenreader unbemerkt.
    const user = userEvent.setup()
    renderOverview()

    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('')

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'Bauamt')
    expect(status).toHaveTextContent('1 Eintrag passt zu „Bauamt“.')

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'xyz')
    expect(status).toHaveTextContent('Kein Eintrag passt zu „Bauamtxyz“.')
  })

  // ADR-0039, Entscheidung 1: only cards - neither a table nor a switch to one.
  it('shows the items as linked cards and offers no table view', () => {
    renderOverview()

    expect(screen.getByRole('link', { name: 'Bauamt' })).toHaveAttribute('href', '/items/b')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Ansicht' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Tabelle' })).not.toBeInTheDocument()
  })

  it('shows the empty state without search when there is nothing to list', () => {
    renderOverview({ items: [], emptyState: <p>Noch nichts da.</p> })

    expect(screen.getByText('Noch nichts da.')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Suchen' })).not.toBeInTheDocument()
  })

  it('keeps the plain title while the first load is still running', () => {
    renderOverview({ items: [], isLoading: true })

    expect(screen.getByRole('heading', { level: 1, name: 'Beispiele' })).toBeInTheDocument()
    expect(screen.getByLabelText('Liste wird geladen')).toBeInTheDocument()
  })

  it('hands a controlled search to the caller and filters nothing itself', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderOverview({
      searchText: undefined,
      search: { value: 'bau', onChange },
      heading: undefined,
      countLabel: (count) => `${count} Einträge`,
      total: 57,
    })

    // Both items stay: the caller's result is what `items` hold.
    expect(screen.getByRole('link', { name: 'Widerspruchsstelle' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Bauamt' })).toBeInTheDocument()
    expect(screen.getByText('57 Einträge')).toBeInTheDocument()

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'x')
    expect(onChange).toHaveBeenCalledWith('baux')
  })

  it('announces a controlled search only once the result answers the typed text', () => {
    const search = { value: 'bau', onChange: () => {}, resultFor: 'ba' }
    const { rerender } = renderOverview({
      searchText: undefined,
      search,
      items: [],
      total: 57,
    })

    // The 57 belong to "ba", not to "bau": nothing is announced, and nothing is called empty.
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    expect(screen.queryByText(/Kein Eintrag passt/)).not.toBeInTheDocument()

    rerender(
      <OverviewPage<Item>
        title="Beispiele"
        items={[]}
        itemKey={(item) => item.id}
        search={{ ...search, resultFor: 'bau' }}
        total={0}
        renderCard={() => null}
      />,
    )

    expect(screen.getByRole('status')).toHaveTextContent('Kein Eintrag passt zu „bau“.')
  })

  it('keeps search and filters in reach when a narrowed result is empty', () => {
    renderOverview({
      items: [],
      filters: <button type="button">Nur Prompts</button>,
      filtered: true,
      emptyState: <p>Noch nichts da.</p>,
    })

    expect(screen.queryByText('Noch nichts da.')).not.toBeInTheDocument()
    expect(screen.getByText('Kein Eintrag passt zu den Filtern.', { selector: 'p' })).toBeVisible()
    expect(screen.getByRole('textbox', { name: 'Suchen' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nur Prompts' })).toBeInTheDocument()
  })

  it('shows a load error above the list', () => {
    renderOverview({ error: 'Laden fehlgeschlagen' })

    expect(screen.getByRole('alert')).toHaveTextContent('Laden fehlgeschlagen')
  })
})
