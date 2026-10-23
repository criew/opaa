import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders, setMockAuthState } from '@/test/test-utils'
import { server } from '@/test/server'
import { useAuthStore } from '@/shared/session/authStore'
import { markRecentLoginReturn } from '@/shared/session/recentLogin'
import { mockLocalAccount } from '@/shared/mocks/authFixtures'
import type { SelfDisclosureStatus } from '@/shared/api/types'
import {
  availableSelfDisclosureStatus,
  setMockRecentSignIn,
  setMockSelfDisclosureStatus,
} from '../mocks/selfDisclosureHandlers'
import type { DownloadRefusal } from '../utils/selfDisclosureDownload'
import { formatMoment } from '../utils/selfDisclosureMessages'
import MyDataSection from './MyDataSection'

const submitted = vi.hoisted(() => ({
  tickets: [] as string[],
  refuse: null as null | ((refusal: DownloadRefusal) => void),
}))

// jsdom cannot submit a form into a frame; the page only needs to know what it handed over
vi.mock('../utils/selfDisclosureDownload', () => ({
  submitSelfDisclosureDownload: (ticket: string, onRefused: (refusal: DownloadRefusal) => void) => {
    submitted.tickets.push(ticket)
    submitted.refuse = onRefused
    return () => {
      submitted.refuse = null
    }
  },
}))

const NOTICE =
  'Diese Auskunft ist nur für Sie. Niemand darf verlangen, dass Sie sie vorlegen oder weitergeben.'
const TOMORROW = '2026-10-24T00:00:00+02:00'

function status(overrides: Partial<SelfDisclosureStatus>): SelfDisclosureStatus {
  return { ...availableSelfDisclosureStatus(), ...overrides }
}

function liveRegionText() {
  return screen
    .getAllByRole('status')
    .map((region) => region.textContent)
    .join(' ')
}

/** The person returns to the tab; the page reads the status at once. */
async function returnToTab() {
  // let pending effects attach the listeners of the watch first
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0))
  })
  await act(async () => {
    window.dispatchEvent(new Event('focus'))
  })
}

async function renderReady() {
  renderWithProviders(<MyDataSection />)
  const button = await screen.findByRole('button', { name: 'Daten herunterladen' })
  await waitFor(() => expect(button).not.toHaveAttribute('aria-disabled'))
  return button
}

describe('MyDataSection', () => {
  beforeEach(() => {
    submitted.tickets = []
    submitted.refuse = null
    setMockAuthState()
    useAuthStore.setState({ mode: 'oidc', sessionKind: 'local', token: 'old-token' })
  })

  it('states word for word that the disclosure is for the person alone, and what it leaves out', async () => {
    await renderReady()

    expect(screen.getByText(NOTICE)).toBeInTheDocument()
    expect(screen.getByText(/Nicht enthalten sind Inhalte von Dokumenten/)).toBeInTheDocument()
    expect(screen.getByText(/ersetzt keinen Auskunftsantrag/)).toBeInTheDocument()
    expect(screen.getByText(/Einmal pro Tag möglich/)).toBeInTheDocument()
  })

  it('confirms the sign-in, downloads and then tells from when the next export is possible', async () => {
    setMockRecentSignIn(false)
    server.use(
      http.post('/api/v1/auth/local/reauthenticate', () => {
        setMockRecentSignIn(true)
        return HttpResponse.json({
          accessToken: 'mock-recent-login-token',
          expiresInSeconds: 900,
          tokenType: 'Bearer',
        })
      }),
    )
    const user = userEvent.setup()
    const button = await renderReady()

    await user.click(button)
    expect(await screen.findByRole('dialog', { name: 'Anmeldung bestätigen' })).toBeInTheDocument()
    await user.keyboard(`${mockLocalAccount.password}{Enter}`)

    await waitFor(() => expect(submitted.tickets).toEqual(['mock-ticket']))
    expect(await screen.findByText('Der Download wird gestartet …')).toBeInTheDocument()

    setMockSelfDisclosureStatus(
      status({
        startsToday: 1,
        completedToday: 1,
        remainingExports: 0,
        remainingStarts: 4,
        blocked: 'DAILY_LIMIT',
        nextPossibleAt: TOMORROW,
        lastCompletedAt: '2026-10-23T10:00:00Z',
        lastResult: 'COMPLETED',
      }),
    )
    await returnToTab()

    await waitFor(() =>
      expect(liveRegionText()).toContain(
        `Ihre Daten wurden vollständig heruntergeladen. Der nächste Download ist ab ${formatMoment(TOMORROW)} möglich.`,
      ),
    )
    // marked, not disabled: the keyboard focus stays on the button
    const done = screen.getByRole('button', { name: 'Daten herunterladen' })
    expect(done).toHaveAttribute('aria-disabled', 'true')
    expect(done).toBeEnabled()
    await user.click(done)
    expect(submitted.tickets).toEqual(['mock-ticket'])
  })

  it('reports an aborted download and that a new attempt is possible', async () => {
    const user = userEvent.setup()
    await user.click(await renderReady())
    await waitFor(() => expect(submitted.tickets).toHaveLength(1))
    expect(await screen.findByText('Der Download wird gestartet …')).toBeInTheDocument()

    setMockSelfDisclosureStatus(status({ running: true, startsToday: 1, remainingStarts: 4 }))
    await returnToTab()
    await waitFor(() => expect(liveRegionText()).toContain('Der Download läuft'))

    setMockSelfDisclosureStatus(
      status({ startsToday: 1, remainingStarts: 4, lastResult: 'ABORTED', retryPossible: true }),
    )
    await returnToTab()

    await waitFor(() =>
      expect(liveRegionText()).toContain(
        'Der Download wurde abgebrochen und nicht gezählt. Sie können es erneut versuchen.',
      ),
    )
    expect(screen.getByRole('button', { name: 'Daten herunterladen' })).not.toHaveAttribute(
      'aria-disabled',
    )
  })

  it('tells an aborted last attempt on a later visit from the status alone', async () => {
    setMockSelfDisclosureStatus(
      status({ startsToday: 2, lastResult: 'ABORTED', retryPossible: true }),
    )
    await renderReady()

    expect(liveRegionText()).toContain(
      'Der letzte Download wurde abgebrochen und nicht gezählt. Sie können es erneut versuchen.',
    )
  })

  it('names exhausted starts and from when the next attempt is possible', async () => {
    setMockSelfDisclosureStatus(
      status({
        startsToday: 5,
        remainingStarts: 0,
        blocked: 'STARTS_EXHAUSTED',
        nextPossibleAt: TOMORROW,
        lastResult: 'ABORTED',
      }),
    )
    renderWithProviders(<MyDataSection />)

    await waitFor(() =>
      expect(liveRegionText()).toContain(
        `Starts für heute erschöpft: Der Download wurde heute zu oft abgebrochen. Der nächste Versuch ist ab ${formatMoment(TOMORROW)} möglich.`,
      ),
    )
    expect(screen.getByRole('button', { name: 'Daten herunterladen' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
  })

  it('explains a reached daily limit at the ticket with the next possible time', async () => {
    server.use(
      http.post('/api/v1/me/self-disclosure/tickets', () => {
        setMockSelfDisclosureStatus(
          status({
            completedToday: 1,
            remainingExports: 0,
            blocked: 'DAILY_LIMIT',
            nextPossibleAt: TOMORROW,
          }),
        )
        return HttpResponse.json(
          { error: 'x', status: 429, timestamp: '', code: 'SELF_DISCLOSURE_DAILY_LIMIT' },
          { status: 429, headers: { 'Retry-After': '3600' } },
        )
      }),
    )
    const user = userEvent.setup()
    await user.click(await renderReady())

    await waitFor(() =>
      expect(liveRegionText()).toContain(
        `Für heute ist kein weiterer Download möglich. Der nächste ist ab ${formatMoment(TOMORROW)} möglich.`,
      ),
    )
    expect(liveRegionText()).not.toContain('429')
  })

  it('asks to try again later when the instance streams as many exports as it may', async () => {
    const user = userEvent.setup()
    await user.click(await renderReady())
    await waitFor(() => expect(submitted.refuse).not.toBeNull())

    await act(async () => {
      submitted.refuse?.({ status: 503, code: null })
    })

    await waitFor(() =>
      expect(liveRegionText()).toContain(
        'Gerade laden viele Personen ihre Daten herunter. Bitte versuchen Sie es in einer Minute erneut.',
      ),
    )
  })

  it('points to the request at the authority when the self-disclosure is switched off', async () => {
    setMockSelfDisclosureStatus(status({ enabled: false, blocked: 'DISABLED' }))
    renderWithProviders(<MyDataSection />)

    expect(
      await screen.findByText(/Die Selbstauskunft ist in dieser Installation derzeit abgeschaltet/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Daten herunterladen' })).not.toBeInTheDocument()
    expect(screen.getByText(NOTICE)).toBeInTheDocument()
  })

  it('reports a network failure without technical details', async () => {
    server.use(http.post('/api/v1/me/self-disclosure/tickets', () => HttpResponse.error()))
    const user = userEvent.setup()
    await user.click(await renderReady())

    await waitFor(() =>
      expect(liveRegionText()).toContain(
        'Der Server ist gerade nicht erreichbar. Bitte prüfen Sie Ihre Verbindung und versuchen Sie es erneut.',
      ),
    )
    expect(liveRegionText()).not.toMatch(/Network|HTTP|Error/)
  })

  it('continues the download by itself after the forced sign-in at the provider', async () => {
    markRecentLoginReturn()
    renderWithProviders(<MyDataSection />)

    await waitFor(() => expect(submitted.tickets).toEqual(['mock-ticket']))
  })

  it('explains a provider that did not confirm the sign-in instead of asking again', async () => {
    setMockRecentSignIn(false)
    markRecentLoginReturn()
    renderWithProviders(<MyDataSection />)

    expect(
      await screen.findByText(/Ihr Anmeldedienst hat die erneute Anmeldung nicht bestätigt/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(submitted.tickets).toEqual([])
  })

  it('reports an abort after midnight, when the counters of the new day equal the old ones', async () => {
    setMockSelfDisclosureStatus(
      status({ startsToday: 0, lastResult: 'ABORTED', retryPossible: true }),
    )
    const user = userEvent.setup()
    await user.click(await renderReady())
    expect(await screen.findByText('Der Download wird gestartet …')).toBeInTheDocument()

    setMockSelfDisclosureStatus(status({ running: true, startsToday: 1, remainingStarts: 4 }))
    await returnToTab()
    await waitFor(() => expect(liveRegionText()).toContain('Der Download läuft'))

    // the next day: no start today, the last result again an abort
    setMockSelfDisclosureStatus(
      status({ startsToday: 0, lastResult: 'ABORTED', retryPossible: true }),
    )
    await returnToTab()

    await waitFor(() =>
      expect(liveRegionText()).toContain(
        'Der Download wurde abgebrochen und nicht gezählt. Sie können es erneut versuchen.',
      ),
    )
  })

  it('does not report the export of another tab when its own ticket was replaced', async () => {
    const user = userEvent.setup()
    await user.click(await renderReady())
    await waitFor(() => expect(submitted.refuse).not.toBeNull())

    // another tab redeemed its newer ticket and is streaming
    setMockSelfDisclosureStatus(
      status({ running: true, startsToday: 1, remainingStarts: 4, blocked: 'RUNNING_EXPORT' }),
    )
    await act(async () => {
      submitted.refuse?.({ status: 404, code: 'SELF_DISCLOSURE_TICKET_INVALID' })
    })

    await waitFor(() =>
      expect(liveRegionText()).toContain('Gerade läuft ein Download Ihrer Daten.'),
    )
    expect(liveRegionText()).not.toContain('Der Download läuft')
    expect(liveRegionText()).not.toContain('abgebrochen')
  })

  it('keeps the outcome a poll has settled when the refusal of the frame arrives later', async () => {
    const user = userEvent.setup()
    await user.click(await renderReady())
    expect(await screen.findByText('Der Download wird gestartet …')).toBeInTheDocument()
    const refuse = submitted.refuse

    setMockSelfDisclosureStatus(
      status({ startsToday: 1, remainingStarts: 4, lastResult: 'ABORTED', retryPossible: true }),
    )
    await returnToTab()
    await waitFor(() => expect(liveRegionText()).toContain('Der Download wurde abgebrochen'))

    await act(async () => {
      refuse?.({ status: 503, code: null })
      // the refusal reads the status before it decides
      await new Promise((resolve) => setTimeout(resolve, 200))
    })

    expect(liveRegionText()).toContain('Der Download wurde abgebrochen')
    expect(liveRegionText()).not.toContain('Gerade laden viele Personen')
  })
})
