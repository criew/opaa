import { useState } from 'react'
import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Button from '@mui/material/Button'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import ListSearch from '../ListSearch'
import EmptyState from './EmptyState'
import LoadError from './LoadError'
import LoadingState, { LOADING_DELAY_MS } from './LoadingState'

describe('LoadingState', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('keeps the skeleton invisible for the first 300 ms, then shows it in place', () => {
    vi.useFakeTimers()
    renderWithProviders(<LoadingState shape="rows" count={2} />)

    const skeleton = screen.getByTestId('loading-skeleton')
    // Already in the layout, so neither its appearance nor the content shifts anything.
    expect(skeleton).not.toBeVisible()

    act(() => {
      vi.advanceTimersByTime(LOADING_DELAY_MS - 1)
    })
    expect(skeleton).not.toBeVisible()

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(skeleton).toBeVisible()
  })

  it('tells screen readers that content is loading without visible text', () => {
    renderWithProviders(<LoadingState />)

    const status = screen.getByRole('status')
    // Not busy itself: a busy live region would hold back its own announcement.
    expect(status).not.toHaveAttribute('aria-busy')
    expect(status).toHaveTextContent('Wird geladen')
    // Visually hidden: clipped to a single pixel, out of the flow.
    expect(screen.getByText('Wird geladen')).toHaveStyle({ position: 'absolute', width: '1px' })
  })
})

describe('EmptyState', () => {
  it('shows the first-time empty sentence with the create action', () => {
    renderWithProviders(
      <EmptyState message="Noch keine Gruppen." action={<Button>Gruppe anlegen</Button>} />,
    )

    expect(screen.getByText('Noch keine Gruppen.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gruppe anlegen' })).toBeInTheDocument()
  })

  it('offers „Filter zurücksetzen" when nothing matches', async () => {
    const user = userEvent.setup()
    const onReset = vi.fn()
    renderWithProviders(<EmptyState kind="noMatch" onReset={onReset} />)

    expect(screen.getByText('Keine Treffer.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Filter zurücksetzen' }))
    expect(onReset).toHaveBeenCalledTimes(1)
  })

  it('hands the focus to the search field after „Filter zurücksetzen"', async () => {
    function Liste() {
      const [query, setQuery] = useState('xyz')
      return (
        <div>
          <ListSearch value={query} onChange={setQuery} placeholder="z. B. Bauamt" label="Suchen" />
          {query ? <EmptyState kind="noMatch" onReset={() => setQuery('')} /> : <p>Bauamt</p>}
        </div>
      )
    }
    const user = userEvent.setup()
    renderWithProviders(<Liste />)

    await user.click(screen.getByRole('button', { name: 'Filter zurücksetzen' }))

    expect(screen.getByRole('textbox', { name: 'Suchen' })).toHaveFocus()
    expect(screen.getByText('Bauamt')).toBeInTheDocument()
  })

  it('looks for the search field only inside its own list', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <>
        <section aria-label="Andere Liste">
          <ListSearch
            value=""
            onChange={() => {}}
            placeholder="z. B. Bauamt"
            label="Andere Suche"
          />
        </section>
        <section aria-label="Diese Liste">
          <EmptyState kind="noMatch" onReset={() => {}} />
        </section>
      </>,
    )

    const reset = screen.getByRole('button', { name: 'Filter zurücksetzen' })
    await user.click(reset)

    expect(screen.getByRole('textbox', { name: 'Andere Suche' })).not.toHaveFocus()
  })

  it('tells the three empty states apart', () => {
    const { container } = renderWithProviders(
      <>
        <EmptyState message="Noch keine Gruppen." />
        <EmptyState kind="noMatch" onReset={() => undefined} />
        <EmptyState
          kind="noAccess"
          message="Die Mitglieder sieht nur, wer den Space verwaltet. Zuständig ist die Space-Verwaltung."
        />
      </>,
    )

    const arten = [...container.querySelectorAll('[data-empty-state]')].map((element) =>
      element.getAttribute('data-empty-state'),
    )
    expect(arten).toEqual(['initial', 'noMatch', 'noAccess'])
    // Only the first-time state invites to create; only the filter state offers a reset.
    expect(screen.getAllByRole('button')).toHaveLength(1)
  })
})

describe('LoadError', () => {
  it('names what failed and offers „Erneut laden"', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    renderWithProviders(
      <LoadError message="Gruppen konnten nicht geladen werden." onRetry={onRetry} />,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('Gruppen konnten nicht geladen werden.')
    await user.click(screen.getByRole('button', { name: 'Erneut laden' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('moves the focus to the content area after „Erneut laden"', async () => {
    function Bereich() {
      const [failed, setFailed] = useState(true)
      return (
        <section aria-label="Gruppen">
          {failed ? (
            <LoadError
              message="Gruppen konnten nicht geladen werden."
              onRetry={() => setFailed(false)}
            />
          ) : (
            <LoadingState />
          )}
        </section>
      )
    }
    const user = userEvent.setup()
    renderWithProviders(<Bereich />)

    await user.click(screen.getByRole('button', { name: 'Erneut laden' }))

    expect(screen.getByRole('region', { name: 'Gruppen' })).toHaveFocus()
  })

  it('adds a cause the server named, but does not repeat the sentence', () => {
    renderWithProviders(
      <>
        <LoadError
          message="Gruppen konnten nicht geladen werden."
          detail="Der Verzeichnisdienst antwortet nicht."
          onRetry={() => undefined}
        />
        <LoadError
          message="Konten konnten nicht geladen werden."
          detail="Konten konnten nicht geladen werden."
          onRetry={() => undefined}
        />
      </>,
    )

    const [mitGrund, ohneGrund] = screen.getAllByRole('alert')
    expect(mitGrund).toHaveTextContent(
      'Gruppen konnten nicht geladen werden. Der Verzeichnisdienst antwortet nicht.',
    )
    expect(ohneGrund.textContent).toBe('Konten konnten nicht geladen werden.Erneut laden')
  })

  it('keeps to the sentence and „Erneut laden" below it in a narrow column', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    renderWithProviders(
      <LoadError
        dense
        message="Chats konnten nicht geladen werden."
        detail="Der Server antwortet nicht."
        onRetry={onRetry}
      />,
    )

    const alert = screen.getByRole('alert')
    expect(alert.textContent).toBe('Chats konnten nicht geladen werden.Erneut laden')
    // no icon column and no action column beside the sentence
    expect(alert.querySelector('.MuiAlert-icon')).toBeNull()
    expect(alert.querySelector('.MuiAlert-action')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Erneut laden' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
