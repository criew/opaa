import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Navigate, Route, Routes } from 'react-router'
import { server } from '@/test/server'
import { renderWithProviders, setMockAuthState } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import { ARRIVAL_FOCUS_ATTRIBUTE } from '@/shared/ui/a11y/routeFocus'
import { tourAnchor } from '@/shared/ui/a11y/tourAnchor'
import AppShell from '../AppShell'
import GlobalAreaLayout from '../GlobalAreaLayout'
import { useQuickTourStore } from './quickTourStore'

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
  }
}

/** A new chat like the real one: it asks the shell to focus its question field on arrival. */
function NewChat() {
  return (
    <div {...tourAnchor('chat-input')}>
      <textarea aria-label="Nachricht eingeben" {...{ [ARRIVAL_FOCUS_ATTRIBUTE]: '' }} />
    </div>
  )
}

function renderApp(initialRoute: string) {
  return renderWithProviders(
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/chat" element={<Navigate to="/spaces/s-1/chats/new" replace />} />
        <Route path="/spaces/:spaceId/chats/:chatId" element={<NewChat />} />
        <Route element={<GlobalAreaLayout />}>
          <Route path="/catalog" element={<div>Katalog-Inhalt</div>} />
        </Route>
      </Route>
    </Routes>,
    { withRouter: true, initialRoute },
  )
}

function recordRequests(): string[] {
  const seen: string[] = []
  server.events.on('request:start', ({ request }) => {
    const { pathname } = new URL(request.url)
    if (request.method !== 'GET') seen.push(`${request.method} ${pathname}`)
  })
  return seen
}

describe('quick tour started from the profile menu', () => {
  const originalMatchMedia = window.matchMedia

  beforeAll(() => {
    window.matchMedia = desktopMatchMedia
  })

  afterAll(() => {
    window.matchMedia = originalMatchMedia
  })

  beforeEach(() => {
    useQuickTourStore.getState().reset()
    setMockAuthState({ features: { quickTour: true } })
  })

  afterEach(() => server.events.removeAllListeners())

  async function startFromMenu(quickTourHintDone: boolean) {
    useAuthStore.setState({
      user: {
        id: 'u-1',
        email: null,
        displayName: 'E. Muster',
        systemRole: 'USER',
        quickTourHintDone,
      },
    })
    const user = userEvent.setup()
    renderApp('/catalog')
    await user.click(screen.getByRole('button', { name: 'Profil und Einstellungen' }))
    await user.click(screen.getByRole('menuitem', { name: 'Rundgang starten' }))
    return user
  }

  it('keeps the focus on "Weiter" across the route change into the chat', async () => {
    const user = await startFromMenu(true)

    const dialog = await screen.findByRole('dialog', { name: 'Fragen stellen' })
    const input = await screen.findByRole('textbox', { name: 'Nachricht eingeben', hidden: true })
    await waitFor(() =>
      expect(within(dialog).getByRole('button', { name: 'Weiter' })).toHaveFocus(),
    )

    await user.keyboard('abc')
    expect(input).toHaveValue('')

    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('dialog', { name: 'Antworten prüfen' })).toBeVisible()

    await user.tab()
    await user.tab()
    await user.tab()
    expect(screen.getByRole('dialog')).toContainElement(document.activeElement as HTMLElement)

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(input).toHaveFocus())
  })

  it('answers a hint still open, with the one write call of the quick tour', async () => {
    const requests = recordRequests()
    await startFromMenu(false)

    await screen.findByRole('dialog', { name: 'Fragen stellen' })
    await waitFor(() => expect(requests).toEqual(['PUT /api/v1/me/quick-tour-hint']))
    expect(useAuthStore.getState().user?.quickTourHintDone).toBe(true)
  })

  it('sends nothing when the hint is answered already', async () => {
    const requests = recordRequests()
    const user = await startFromMenu(true)

    await screen.findByRole('dialog', { name: 'Fragen stellen' })
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(requests).toEqual([])
  })
})
