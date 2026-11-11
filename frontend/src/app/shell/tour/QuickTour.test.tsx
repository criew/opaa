import { useEffect } from 'react'
import TextField from '@mui/material/TextField'
import ViewDialog from '@/shared/ui/dialog/ViewDialog'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
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

/**
 * A page behind the tour that focuses its own field in the very commit in which the tour renders
 * anew - like the chat arriving after a route change. Rendered before the tour, so its effect runs
 * between the cleanup and the new subscription of the effects inside the tour.
 */
function FieldGrabber() {
  const paged = useQuickTourStore((s) => s.paged)
  useEffect(() => {
    if (paged) {
      document
        .querySelector<HTMLTextAreaElement>('textarea[aria-label="Nachricht eingeben"]')
        ?.focus()
    }
  }, [paged])
  return null
}

/** The anchors of the real shell and chat, with the question field the tour returns to. */
function Page({ grabFocus = false }: { grabFocus?: boolean }) {
  return (
    <>
      {grabFocus && <FieldGrabber />}
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
      <button type="button" {...tourAnchor('new-chat')}>
        Neuer Chat
      </button>
      <nav aria-label="Chats" {...tourAnchor('chats')} />
      <button type="button" {...tourAnchor('notifications')}>
        Benachrichtigungen
      </button>
      <span {...tourAnchor('space-knowledge')}>@Space-Wissen</span>
      <div {...tourAnchor('chat-input')}>
        <textarea aria-label="Nachricht eingeben" />
      </div>
      <button type="button" {...tourAnchor('attach')}>
        Dateien anhängen
      </button>
      <p {...tourAnchor('ai-notice')}>Antworten werden von einer KI erzeugt.</p>
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

const input = () => screen.getByRole('textbox', { name: 'Nachricht eingeben', hidden: true })

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

      const hint = screen.getByRole('dialog', { name: 'Neu hier?' })
      expect(hint).toHaveTextContent(
        'Ein Rundgang in 9 Schritten zeigt, wie Sie fragen, worauf sich Antworten stützen und wo Sie Wissen finden.',
      )
      expect(within(hint).getByRole('button', { name: 'Rundgang starten' })).toBeInTheDocument()
      expect(within(hint).getByRole('button', { name: 'Nicht jetzt' })).toBeInTheDocument()
      // Only the invitation opens on its own, never the tour.
      expect(screen.queryByRole('dialog', { name: 'Fragen stellen' })).not.toBeInTheDocument()
    })

    it('lies modally over the page with the focus on "Rundgang starten"', async () => {
      signIn({ quickTourHintDone: false })
      renderWithProviders(<Page />)

      const hint = screen.getByRole('dialog', { name: 'Neu hier?' })
      expect(hint).toHaveAttribute('aria-modal', 'true')
      expect(hint).toHaveAccessibleDescription(
        'Ein Rundgang in 9 Schritten zeigt, wie Sie fragen, worauf sich Antworten stützen und wo Sie Wissen finden.',
      )
      await waitFor(() =>
        expect(within(hint).getByRole('button', { name: 'Rundgang starten' })).toHaveFocus(),
      )
    })

    it('stays open on a click beside it and sets nothing', async () => {
      const user = userEvent.setup()
      const requests = recordRequests()
      signIn({ quickTourHintDone: false })
      renderWithProviders(<Page />)

      const backdrop = document.querySelector<HTMLElement>('.MuiBackdrop-root')
      expect(backdrop).not.toBeNull()
      await user.click(backdrop as HTMLElement)

      expect(screen.getByRole('dialog', { name: 'Neu hier?' })).toBeInTheDocument()
      expect(requests).toEqual([])
    })

    it('answers like "Nicht jetzt" on Escape', async () => {
      const user = userEvent.setup()
      const requests = recordRequests()
      signIn({ quickTourHintDone: false })
      renderWithProviders(<Page />)
      await screen.findByRole('dialog', { name: 'Neu hier?' })

      await user.keyboard('{Escape}')

      await waitFor(() =>
        expect(screen.queryByRole('dialog', { name: 'Neu hier?' })).not.toBeInTheDocument(),
      )
      await waitFor(() => expect(input()).toHaveFocus())
      await waitFor(() => expect(requests).toEqual(['PUT /api/v1/me/quick-tour-hint']))
      expect(screen.queryByRole('dialog', { name: 'Fragen stellen' })).not.toBeInTheDocument()
    })

    it.each([
      ['the marker is set', { quickTourHintDone: true }, true],
      ['the field is missing (older backend)', {}, true],
      ['the feature is switched off', { quickTourHintDone: false }, false],
    ])('stays away when %s', (_, user, quickTour) => {
      signIn(user, quickTour)
      renderWithProviders(<Page />)

      expect(screen.queryByRole('dialog', { name: 'Neu hier?' })).not.toBeInTheDocument()
    })

    it('counts an optional station only while its element is on screen', async () => {
      const original = HTMLElement.prototype.getClientRects
      // jsdom lays nothing out; here only the attach button counts as on screen.
      HTMLElement.prototype.getClientRects = function (this: HTMLElement) {
        return (this.getAttribute('data-tour-anchor') === 'attach'
          ? [new DOMRect(0, 0, 10, 10)]
          : []) as unknown as DOMRectList
      }
      try {
        signIn({ quickTourHintDone: false })
        renderWithProviders(<Page />)
        expect(await screen.findByText(/Ein Rundgang in 10 Schritten/)).toBeInTheDocument()
      } finally {
        HTMLElement.prototype.getClientRects = original
      }
    })

    it('counts the stations for the width and the role', () => {
      signIn({ quickTourHintDone: false, systemRole: 'SYSTEM_ADMIN' })
      const { unmount } = renderWithProviders(<Page />)
      expect(screen.getByText(/Ein Rundgang in 10 Schritten/)).toBeInTheDocument()
      unmount()

      window.matchMedia = matchMediaFor(false)
      renderWithProviders(<Page />)
      expect(screen.getByText(/Ein Rundgang in 5 Schritten/)).toBeInTheDocument()
    })

    it('"Nicht jetzt" sets the marker, closes the hint and puts the focus into the input', async () => {
      const user = userEvent.setup()
      const requests = recordRequests()
      signIn({ quickTourHintDone: false })
      renderWithProviders(<Page />)

      await user.click(screen.getByRole('button', { name: 'Nicht jetzt' }))

      expect(screen.queryByRole('dialog', { name: 'Neu hier?' })).not.toBeInTheDocument()
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
        'Schreiben Sie Ihre Frage hier hinein, so wie Sie einen Menschen fragen würden, und schicken Sie sie mit Enter ab. Im selben Chat können Sie einfach weiterfragen.',
      )
      await waitFor(() =>
        expect(within(dialog).getByRole('button', { name: 'Weiter' })).toHaveFocus(),
      )
      expect(screen.queryByRole('dialog', { name: 'Neu hier?' })).not.toBeInTheDocument()
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

      expect(screen.queryByRole('dialog', { name: 'Neu hier?' })).not.toBeInTheDocument()
      await waitFor(() => expect(input()).toHaveFocus())
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.queryByText(/kaputt|Fehler/)).not.toBeInTheDocument()
    })
  })

  describe('the tour', () => {
    async function openTour(grabFocus = false) {
      signIn({ quickTourHintDone: true })
      const user = userEvent.setup()
      renderWithProviders(<Page grabFocus={grabFocus} />)
      useQuickTourStore.getState().start()
      const dialog = await screen.findByRole('dialog', { name: 'Fragen stellen' })
      await waitFor(() =>
        expect(within(dialog).getByRole('button', { name: 'Weiter' })).toHaveFocus(),
      )
      return { user, dialog }
    }

    it('pages with the arrow keys and announces every change politely', async () => {
      const { user } = await openTour()
      // The opening is read out as the dialog's name and description, not a second time.
      expect(screen.getByRole('status')).toBeEmptyDOMElement()

      await user.keyboard('{ArrowRight}')
      expect(screen.getByRole('dialog', { name: 'Antworten prüfen' })).toBeInTheDocument()
      expect(screen.getByText('2 von 9')).toBeInTheDocument()
      expect(screen.getByRole('status')).toHaveTextContent(
        'Schritt 2 von 9: Antworten prüfen. In jeder Antwort stehen kleine Zahlen',
      )

      await user.keyboard('{ArrowLeft}')
      expect(screen.getByRole('dialog', { name: 'Fragen stellen' })).toBeInTheDocument()
    })

    it('does not end the last station on →, only on "Fertig" - and then the input has the focus', async () => {
      const { user } = await openTour()
      for (let i = 0; i < 8; i++) await user.keyboard('{ArrowRight}')
      expect(screen.getByRole('dialog', { name: 'Rundgang wiederholen' })).toBeInTheDocument()

      await user.keyboard('{ArrowRight}')
      expect(screen.getByRole('dialog', { name: 'Rundgang wiederholen' })).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Fertig' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      await waitFor(() => expect(input()).toHaveFocus())
    })

    it('takes the focus back when the page behind focuses its own field', async () => {
      const { user, dialog } = await openTour()

      act(() => input().focus())

      await waitFor(() =>
        expect(within(dialog).getByRole('button', { name: 'Weiter' })).toHaveFocus(),
      )
      await user.keyboard('abc')
      expect(input()).toHaveValue('')
      await user.keyboard('{ArrowRight}')
      expect(screen.getByRole('dialog', { name: 'Antworten prüfen' })).toBeInTheDocument()
    })

    it('takes the focus back from a field the page focuses while the tour renders anew', async () => {
      const { user, dialog } = await openTour(true)

      // Same station, new render of the tour - the page grabs the focus in that commit.
      act(() => useQuickTourStore.getState().goTo(0))

      await waitFor(() =>
        expect(within(dialog).getByRole('button', { name: 'Weiter' })).toHaveFocus(),
      )
      await user.keyboard('abc')
      expect(input()).toHaveValue('')
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

    it('keeps Tab inside the card and reaches every button of it', async () => {
      const { user, dialog } = await openTour()
      await user.keyboard('{ArrowRight}')
      const button = (name: string) => within(dialog).getByRole('button', { name })

      await user.tab()
      expect(button('Schließen')).toHaveFocus()
      await user.tab()
      expect(button('Zurück')).toHaveFocus()
      await user.tab()
      expect(button('Weiter')).toHaveFocus()
      await user.tab({ shift: true })
      expect(button('Zurück')).toHaveFocus()
    })

    it('leaves the focus to a modal opened above the tour', async () => {
      const { user } = await openTour()
      renderWithProviders(
        <ViewDialog title="Darüber" onClose={() => undefined}>
          <TextField label="Feld darüber" />
        </ViewDialog>,
      )
      const upper = await screen.findByRole('textbox', { name: 'Feld darüber' })

      await user.click(upper)
      await user.keyboard('abc')

      expect(upper).toHaveFocus()
      expect(upper).toHaveValue('abc')
    })

    it('ends below 900 px on the one step at the menu button', async () => {
      window.matchMedia = matchMediaFor(false)
      const { user } = await openTour()
      for (let i = 0; i < 4; i++) await user.keyboard('{ArrowRight}')
      expect(screen.getByRole('dialog', { name: 'Alles Weitere im Menü' })).toBeInTheDocument()
      expect(screen.getByText('5 von 5')).toBeInTheDocument()
    })

    it('reports neither the tour nor its progress to the server', async () => {
      const requests = recordRequests()
      const { user } = await openTour()
      for (let i = 0; i < 8; i++) await user.keyboard('{ArrowRight}')
      await user.keyboard('{ArrowLeft}{ArrowRight}')
      await user.click(screen.getByRole('button', { name: 'Fertig' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

      expect(requests).toEqual([])
    })
  })
})
