import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { renderWithProviders, setMockAuthState } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import type { AuthUser } from '@/shared/session/types'
import { tourAnchor } from '@/shared/ui/a11y/tourAnchor'
import QuickTour from './QuickTour'
import QuickTourHint from './QuickTourHint'
import { useQuickTourStore } from './quickTourStore'

function matchMediaFor(isWide: boolean) {
  return (query: string): MediaQueryList => ({
    // MUI asks for (min-width: 900px) for md and up, and for reduced motion.
    matches: query.includes('min-width') ? isWide : false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })
}

/** The anchors of the real shell and chat, with the question field the tour returns to. */
function Page() {
  return (
    <>
      <button type="button" {...tourAnchor('menu')}>
        Menü öffnen
      </button>
      <nav aria-label="Spaces" {...tourAnchor('spaces')} />
      <a href="/catalog" {...tourAnchor('catalog')}>
        Katalog
      </a>
      <a href="/admin/users" {...tourAnchor('admin')}>
        Admin
      </a>
      <button type="button" {...tourAnchor('profile')}>
        Profil und Einstellungen
      </button>
      <span {...tourAnchor('space-knowledge')}>@Space-Wissen</span>
      <div {...tourAnchor('chat-input')}>
        <textarea aria-label="Nachricht eingeben" />
      </div>
      <QuickTourHint />
      <QuickTour />
    </>
  )
}

const USER: AuthUser = {
  id: 'user-1',
  email: 'e.muster@stadt.example',
  displayName: 'E. Muster',
  systemRole: 'USER',
}

function signIn(user: Partial<AuthUser>, quickTour = true) {
  setMockAuthState({ features: { quickTour } })
  useAuthStore.setState({ user: { ...USER, ...user } })
}

/** Every request the page sends, as "METHOD path". */
function recordRequests(): string[] {
  const seen: string[] = []
  server.events.on('request:start', ({ request }) => {
    seen.push(`${request.method} ${new URL(request.url).pathname}`)
  })
  return seen
}

const input = () => screen.getByRole('textbox', { name: 'Nachricht eingeben' })

describe('quick tour', () => {
  const originalMatchMedia = window.matchMedia

  beforeEach(() => {
    window.matchMedia = matchMediaFor(true)
    useQuickTourStore.getState().reset()
  })

  afterEach(() => {
    window.matchMedia = originalMatchMedia
    server.events.removeAllListeners()
  })

  describe('the hint "Neu hier?"', () => {
    it('shows when the server says explicitly "not done" and the feature is on', () => {
      signIn({ quickTourHintDone: false })
      renderWithProviders(<Page />)

      const hint = screen.getByRole('region', { name: 'Neu hier?' })
      expect(hint).toHaveTextContent(
        'Ein Rundgang in 5 Schritten zeigt, wie Sie fragen, worauf sich Antworten stützen und wo Sie Wissen finden.',
      )
      expect(within(hint).getByRole('button', { name: 'Rundgang starten' })).toBeInTheDocument()
      expect(within(hint).getByRole('button', { name: 'Nicht jetzt' })).toBeInTheDocument()
      // Nothing opens on its own.
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it.each([
      ['the marker is set', { quickTourHintDone: true }, true],
      ['the field is missing (older backend)', {}, true],
      ['the feature is switched off', { quickTourHintDone: false }, false],
    ])('stays away when %s', (_, user, quickTour) => {
      signIn(user, quickTour)
      renderWithProviders(<Page />)

      expect(screen.queryByRole('region', { name: 'Neu hier?' })).not.toBeInTheDocument()
    })

    it('counts the stations for the width and the role', () => {
      signIn({ quickTourHintDone: false, systemRole: 'SYSTEM_ADMIN' })
      const { unmount } = renderWithProviders(<Page />)
      expect(screen.getByText(/Ein Rundgang in 6 Schritten/)).toBeInTheDocument()
      unmount()

      window.matchMedia = matchMediaFor(false)
      renderWithProviders(<Page />)
      expect(screen.getByText(/Ein Rundgang in 3 Schritten/)).toBeInTheDocument()
    })

    it('"Nicht jetzt" sets the marker, closes the hint and puts the focus into the input', async () => {
      const user = userEvent.setup()
      const requests = recordRequests()
      signIn({ quickTourHintDone: false })
      renderWithProviders(<Page />)

      await user.click(screen.getByRole('button', { name: 'Nicht jetzt' }))

      expect(screen.queryByRole('region', { name: 'Neu hier?' })).not.toBeInTheDocument()
      await waitFor(() => expect(input()).toHaveFocus())
      await waitFor(() => expect(requests).toEqual(['PUT /api/v1/me/quick-tour-hint']))
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('"Rundgang starten" sets the marker and opens the tour on "Weiter"', async () => {
      const user = userEvent.setup()
      const requests = recordRequests()
      signIn({ quickTourHintDone: false })
      renderWithProviders(<Page />)

      await user.click(screen.getByRole('button', { name: 'Rundgang starten' }))

      const dialog = await screen.findByRole('dialog', { name: 'Fragen stellen' })
      expect(dialog).toHaveAccessibleDescription(
        'Fragen Sie in eigenen Worten. Jede Antwort nennt ihre Fundstellen als Ziffern; ein Klick zeigt die Stelle im Dokument.',
      )
      await waitFor(() =>
        expect(within(dialog).getByRole('button', { name: 'Weiter' })).toHaveFocus(),
      )
      expect(screen.queryByRole('region', { name: 'Neu hier?' })).not.toBeInTheDocument()
      await waitFor(() => expect(requests).toEqual(['PUT /api/v1/me/quick-tour-hint']))
    })

    it('closes silently when setting the marker fails', async () => {
      server.use(
        http.put('/api/v1/me/quick-tour-hint', () =>
          HttpResponse.json({ error: 'kaputt' }, { status: 500 }),
        ),
      )
      const user = userEvent.setup()
      signIn({ quickTourHintDone: false })
      renderWithProviders(<Page />)

      await user.click(screen.getByRole('button', { name: 'Nicht jetzt' }))

      expect(screen.queryByRole('region', { name: 'Neu hier?' })).not.toBeInTheDocument()
      await waitFor(() => expect(input()).toHaveFocus())
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.queryByText(/kaputt|Fehler/)).not.toBeInTheDocument()
    })
  })

  describe('the tour', () => {
    async function openTour() {
      signIn({ quickTourHintDone: true })
      const user = userEvent.setup()
      renderWithProviders(<Page />)
      useQuickTourStore.getState().start()
      const dialog = await screen.findByRole('dialog', { name: 'Fragen stellen' })
      await waitFor(() =>
        expect(within(dialog).getByRole('button', { name: 'Weiter' })).toHaveFocus(),
      )
      return { user, dialog }
    }

    it('pages with the arrow keys and announces every change politely', async () => {
      const { user } = await openTour()

      await user.keyboard('{ArrowRight}')
      expect(
        screen.getByRole('dialog', { name: 'Worauf die Antwort sich stützt' }),
      ).toBeInTheDocument()
      expect(screen.getByText('2 von 5')).toBeInTheDocument()
      expect(screen.getByRole('status')).toHaveTextContent(
        'Schritt 2 von 5: Worauf die Antwort sich stützt. @Space-Wissen ist das Wissen',
      )

      await user.keyboard('{ArrowLeft}')
      expect(screen.getByRole('dialog', { name: 'Fragen stellen' })).toBeInTheDocument()
    })

    it('does not end the last station on →, only on "Fertig" - and then the input has the focus', async () => {
      const { user } = await openTour()
      for (let i = 0; i < 4; i++) await user.keyboard('{ArrowRight}')
      expect(screen.getByRole('dialog', { name: 'Rundgang wiederholen' })).toBeInTheDocument()

      await user.keyboard('{ArrowRight}')
      expect(screen.getByRole('dialog', { name: 'Rundgang wiederholen' })).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Fertig' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      await waitFor(() => expect(input()).toHaveFocus())
    })

    it('ends on Escape with the focus in the input', async () => {
      const { user } = await openTour()
      await user.keyboard('{Escape}')

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      await waitFor(() => expect(input()).toHaveFocus())
    })

    it('ends on ✕ "Schließen" with the focus in the input', async () => {
      const { user, dialog } = await openTour()
      await user.click(within(dialog).getByRole('button', { name: 'Schließen' }))

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      await waitFor(() => expect(input()).toHaveFocus())
    })

    it('keeps Tab inside the card', async () => {
      const { user, dialog } = await openTour()
      await user.keyboard('{ArrowRight}')
      for (let i = 0; i < 5; i++) {
        await user.tab()
        expect(dialog).toContainElement(document.activeElement as HTMLElement)
      }
    })

    it('ends below 900 px on the one step at the menu button', async () => {
      window.matchMedia = matchMediaFor(false)
      const { user } = await openTour()
      await user.keyboard('{ArrowRight}{ArrowRight}')
      expect(screen.getByRole('dialog', { name: 'Alles Weitere im Menü' })).toBeInTheDocument()
      expect(screen.getByText('3 von 3')).toBeInTheDocument()
    })

    it('reports neither the tour nor its progress to the server', async () => {
      const requests = recordRequests()
      const { user } = await openTour()
      for (let i = 0; i < 4; i++) await user.keyboard('{ArrowRight}')
      await user.keyboard('{ArrowLeft}{ArrowRight}')
      await user.click(screen.getByRole('button', { name: 'Fertig' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

      expect(requests).toEqual([])
    })
  })
})
