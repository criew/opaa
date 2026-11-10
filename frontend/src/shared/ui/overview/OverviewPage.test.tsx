import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../../test/test-utils'
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
      createLabel="Neues Beispiel"
      onCreate={() => {}}
      items={items}
      itemKey={(item) => item.id}
      searchText={(item) => `${item.name} ${item.description}`}
      renderCard={(item) => <OverviewCard to={`/items/${item.id}`}>{item.name}</OverviewCard>}
      errorMessage="Beispiele konnten nicht geladen werden."
      onRetry={() => {}}
      emptyMessage="Noch keine Beispiele."
      {...props}
    />,
    { withRouter: true },
  )
}

describe('OverviewPage (#1913)', () => {
  it('shows the heading with the visible count and the create button', () => {
    const onCreate = vi.fn()
    renderOverview({ onCreate })

    expect(screen.getByRole('heading', { level: 1, name: 'Beispiele' })).toBeInTheDocument()
    expect(screen.getByText('2 Einträge')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neues Beispiel' })).toBeInTheDocument()

    screen.getByRole('button', { name: 'Neues Beispiel' }).click()
    expect(onCreate).toHaveBeenCalled()
  })

  // Every create button carries the plus icon before its label (guidelines 5.1).
  it('marks the create button with the plus icon', () => {
    renderOverview()

    const button = screen.getByRole('button', { name: 'Neues Beispiel' })
    expect(within(button).getByTestId('AddIcon')).toBeInTheDocument()
  })

  it('filters client-side over the supplied search text and updates the count', async () => {
    const user = userEvent.setup()
    renderOverview()

    // The description is part of searchText, so a term only found there still matches.
    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'bauanträge')

    expect(screen.getByRole('link', { name: 'Bauamt' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Widerspruchsstelle' })).not.toBeInTheDocument()
    expect(screen.getByText('1 Eintrag')).toBeInTheDocument()
  })

  // Every overview heads its page alike - the page name as heading, the count quiet
  // beside it, no sentence underneath.
  it('keeps the page name as heading and sets the count beside it, without a subtitle', () => {
    renderOverview()

    const heading = screen.getByRole('heading', { level: 1, name: 'Beispiele' })
    expect(heading).not.toHaveTextContent('2')
    expect(heading.parentElement).toHaveTextContent(/^Beispiele2 EinträgeNeues Beispiel$/)
    expect(heading.parentElement?.nextElementSibling?.tagName).not.toBe('P')
  })

  it('leaves out the create button where an overview offers no creation', () => {
    renderOverview({ createLabel: undefined, onCreate: undefined })

    expect(screen.queryByRole('button', { name: 'Neues Beispiel' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Beispiele' })).toBeInTheDocument()
  })

  it('shows „Keine Treffer." with „Filter zurücksetzen" when nothing matches', async () => {
    const user = userEvent.setup()
    renderOverview()

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'Ordnungsamt')

    // The live region still names the query for screen readers.
    expect(screen.getByRole('status')).toHaveTextContent('Kein Eintrag passt zu „Ordnungsamt“.')
    expect(screen.getByText('Keine Treffer.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Filter zurücksetzen' }))
    expect(screen.getByRole('textbox', { name: 'Suchen' })).toHaveValue('')
    expect(screen.getByRole('link', { name: 'Bauamt' })).toBeInTheDocument()
  })

  it('announces the filter result in a live region (accessibility.md 2.8)', async () => {
    // Filtering does not move focus - without a status region the result would go
    // unnoticed with a screen reader.
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

  it('shows the first-time empty sentence with the create action and no search', () => {
    renderOverview({ items: [] })

    expect(screen.getByText('Noch keine Beispiele.')).toBeInTheDocument()
    // Head and empty state carry the same label (guidelines 5.1).
    expect(screen.getAllByRole('button', { name: 'Neues Beispiel' })).toHaveLength(2)
    expect(screen.queryByRole('textbox', { name: 'Suchen' })).not.toBeInTheDocument()
  })

  it('keeps the plain title while the first load is still running', () => {
    renderOverview({ items: [], isLoading: true })

    expect(screen.getByRole('heading', { level: 1, name: 'Beispiele' })).toBeInTheDocument()
    expect(screen.queryByText(/Einträge/)).not.toBeInTheDocument()
    expect(screen.getByRole('status', { busy: true })).toBeInTheDocument()
  })

  it('hands a controlled search to the caller and filters nothing itself', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderOverview({
      searchText: undefined,
      search: { value: 'bau', onChange },
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
        errorMessage="Beispiele konnten nicht geladen werden."
        onRetry={() => {}}
        emptyMessage="Noch keine Beispiele."
      />,
    )

    expect(screen.getByRole('status')).toHaveTextContent('Kein Eintrag passt zu „bau“.')
  })

  it('keeps search and filters in reach when a narrowed result is empty', async () => {
    const user = userEvent.setup()
    const onResetFilters = vi.fn()
    renderOverview({
      items: [],
      filters: <button type="button">Nur Prompts</button>,
      filtered: true,
      onResetFilters,
    })

    expect(screen.queryByText('Noch keine Beispiele.')).not.toBeInTheDocument()
    expect(screen.getByText('Keine Treffer.')).toBeVisible()
    expect(screen.getByRole('textbox', { name: 'Suchen' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nur Prompts' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Filter zurücksetzen' }))
    expect(onResetFilters).toHaveBeenCalledTimes(1)
  })

  it('shows only the load notice with „Erneut laden" after a failed load', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    renderOverview({ items: [], error: 'Laden fehlgeschlagen', onRetry })

    expect(screen.getByRole('alert')).toHaveTextContent('Beispiele konnten nicht geladen werden.')
    // Never together with an empty text.
    expect(screen.queryByText('Noch keine Beispiele.')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Erneut laden' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
