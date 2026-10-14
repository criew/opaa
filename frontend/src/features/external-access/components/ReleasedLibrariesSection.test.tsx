import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import type { ExternalAccessLibraryResponse } from '@/shared/api/types'
import ReleasedLibrariesSection from './ReleasedLibrariesSection'
import { releaseEndingLabel } from './externalToolsState'

const released: ExternalAccessLibraryResponse = {
  libraryId: 'library-1',
  libraryName: 'Baugenehmigungen 2024',
  externalAccess: {
    libraryId: 'library-1',
    state: 'ACTIVE',
    expiresAt: '2027-03-01T22:59:59Z',
    setAt: '2026-03-01T10:00:00Z',
    setByDisplayName: 'Erika Mustermann',
    tokenCount: 4,
    maxReleaseDays: 365,
  },
}

/** jsdom has no matchMedia; the tables render only on a desktop viewport (guidelines 5.3). */
function desktopMatchMedia(query: string): MediaQueryList {
  return {
    matches: query.includes('min-width'),
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  } as unknown as MediaQueryList
}

function render(
  libraries: ExternalAccessLibraryResponse[] | null,
  error: string | null = null,
  onRetry = () => {},
) {
  return renderWithProviders(
    <ReleasedLibrariesSection libraries={libraries} error={error} onRetry={onRetry} />,
    { withRouter: true },
  )
}

/** Die freigegebenen Bibliotheken (#1731) als Abschnitt der externen KI-Werkzeuge (#2504). */
describe('ReleasedLibrariesSection', () => {
  const originalMatchMedia = window.matchMedia

  beforeEach(() => {
    window.matchMedia = desktopMatchMedia
  })

  afterEach(() => {
    window.matchMedia = originalMatchMedia
  })

  it('nennt jede Bibliothek mit Ablauf, Setzendem und Zahl der Tokens - ohne Personen', () => {
    render([released])

    const row = screen.getByRole('row', { name: /Baugenehmigungen 2024/ })
    expect(within(row).getByRole('link', { name: 'Baugenehmigungen 2024' })).toHaveAttribute(
      'href',
      '/libraries/library-1?tab=freigaben',
    )
    expect(row).toHaveTextContent('Erika Mustermann')
    expect(row).toHaveTextContent('4')
    expect(screen.getByRole('heading', { name: 'Freigegebene Bibliotheken (1)' })).toBeVisible()
  })

  it('zeigt im schmalen Fenster dieselben Angaben als Liste', () => {
    window.matchMedia = originalMatchMedia
    render([released])

    const list = screen.getByRole('list', { name: 'Freigegebene Bibliotheken' })
    expect(within(list).getByRole('link', { name: 'Baugenehmigungen 2024' })).toBeVisible()
    expect(list).toHaveTextContent('Erika Mustermann, 01.03.2026')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('sagt es, wenn derzeit keine Bibliothek freigegeben ist', () => {
    render([])

    expect(screen.getByText('Derzeit ist keine Bibliothek freigegeben.')).toBeVisible()
  })

  it('zeigt einen Fehler mit erneutem Laden statt einer leeren Liste', async () => {
    const onRetry = vi.fn()
    render(null, 'Keine Berechtigung', onRetry)

    expect(screen.getByText('Keine Berechtigung')).toBeVisible()
    expect(screen.queryByText(/keine Bibliothek freigegeben/)).not.toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Erneut laden' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })
})

describe('releaseEndingLabel', () => {
  const now = new Date('2026-10-11T10:00:00Z')

  it('markiert eine Freigabe erst in den letzten dreißig Tagen', () => {
    expect(releaseEndingLabel('2026-12-31T22:59:59Z', now)).toBeNull()
    expect(releaseEndingLabel('2026-10-24T12:00:00Z', now)).toBe('läuft in 13 Tagen ab')
    expect(releaseEndingLabel('2026-10-12T12:00:00Z', now)).toBe('läuft morgen ab')
    expect(releaseEndingLabel('2026-10-11T14:00:00Z', now)).toBe('läuft heute ab')
    expect(releaseEndingLabel(null, now)).toBeNull()
  })
})
