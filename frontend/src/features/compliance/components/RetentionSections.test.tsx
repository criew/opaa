import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/server'
import ConfirmHost from '@/shared/ui/feedback/ConfirmHost'
import {
  getChatAutoCleanupRetention,
  getIncidentScopeRetention,
  getLockedAccountChatRetention,
} from '../api/retentionApi'
import ChatAutoCleanupRetentionSection from './ChatAutoCleanupRetentionSection'
import IncidentScopeRetentionSection from './IncidentScopeRetentionSection'
import LockedAccountChatRetentionSection from './LockedAccountChatRetentionSection'

function render(section: React.ReactNode) {
  return renderWithProviders(
    <>
      {section}
      <ConfirmHost />
    </>,
  )
}

describe('ChatAutoCleanupRetentionSection', () => {
  it('shows the factory defaults and sets own periods after a confirmation', async () => {
    const user = userEvent.setup()
    render(<ChatAutoCleanupRetentionSection />)

    const archive = await screen.findByLabelText('Archivieren nach (Tage)')
    expect(archive).toHaveValue('90')
    expect(screen.getByText('Es gilt die Werkseinstellung.')).toBeInTheDocument()

    await user.clear(archive)
    await user.type(archive, '120')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/längeren Fristen/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Fristen ändern' }))

    await waitFor(async () =>
      expect((await getChatAutoCleanupRetention()).archiveAfterDays).toBe(120),
    )
    expect(await screen.findByText(/Es gilt ein eigener Wert/)).toBeInTheDocument()
  })

  it('refuses a period below its floor before sending', async () => {
    const user = userEvent.setup()
    render(<ChatAutoCleanupRetentionSection />)

    const remove = await screen.findByLabelText('Löschen nach (Tage)')
    await user.clear(remove)
    await user.type(remove, '29')

    expect(screen.getByRole('button', { name: 'Speichern' })).toBeDisabled()
  })

  it('warns that a shorter period deletes earlier and returns to the factory defaults', async () => {
    const user = userEvent.setup()
    render(<ChatAutoCleanupRetentionSection />)

    const remove = await screen.findByLabelText('Löschen nach (Tage)')
    await user.clear(remove)
    await user.type(remove, '30')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/früher als bisher/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Fristen ändern' }))
    await waitFor(async () => expect((await getChatAutoCleanupRetention()).overridden).toBe(true))

    await user.click(
      await screen.findByRole('button', { name: 'Werkseinstellung wiederherstellen' }),
    )
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: 'Werkseinstellung wiederherstellen',
      }),
    )
    await waitFor(async () => expect((await getChatAutoCleanupRetention()).overridden).toBe(false))
  })

  it('offers a retry when the periods cannot be loaded', async () => {
    server.use(
      http.get('/api/v1/admin/retention/chat-auto-cleanup', () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    )
    render(<ChatAutoCleanupRetentionSection />)

    expect(
      await screen.findByText('Die Fristen der Chat-Bereinigung konnten nicht geladen werden.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Erneut laden' })).toBeInTheDocument()
  })
})

describe('LockedAccountChatRetentionSection', () => {
  it('switches the deletion off after a confirmation', async () => {
    const user = userEvent.setup()
    render(<LockedAccountChatRetentionSection />)

    const toggle = await screen.findByRole('switch', { name: 'Chats nach der Sperre löschen' })
    expect(toggle).toBeChecked()
    await user.click(toggle)
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/jede Frist neu/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Ändern' }))

    await waitFor(async () => expect((await getLockedAccountChatRetention()).enabled).toBe(false))
  })
})

describe('IncidentScopeRetentionSection', () => {
  it('lengthens the period within 1 to 10 years and refuses 11', async () => {
    const user = userEvent.setup()
    render(<IncidentScopeRetentionSection />)

    const field = await screen.findByLabelText('Löschfrist (Kalenderjahre)')
    await user.clear(field)
    await user.type(field, '11')
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeDisabled()

    await user.clear(field)
    await user.type(field, '3')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/Dienstvereinbarung/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Frist ändern' }))

    await waitFor(async () => expect((await getIncidentScopeRetention()).retentionYears).toBe(3))
  })
})

describe('reset and pause', () => {
  it('warns that a reset to the shorter factory periods deletes earlier', async () => {
    const user = userEvent.setup()
    render(<ChatAutoCleanupRetentionSection />)
    const remove = await screen.findByLabelText('Löschen nach (Tage)')
    await user.clear(remove)
    await user.type(remove, '500')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Fristen ändern' }),
    )
    await waitFor(async () => expect((await getChatAutoCleanupRetention()).overridden).toBe(true))

    await user.click(
      await screen.findByRole('button', { name: 'Werkseinstellung wiederherstellen' }),
    )
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/früher als bisher/)).toBeInTheDocument()
    expect(
      within(dialog).getByRole('button', { name: 'Werkseinstellung wiederherstellen' }),
    ).toHaveClass('MuiButton-colorError')
  })

  it('warns that a reset of the locked-account deletion to the shorter factory period deletes earlier', async () => {
    const user = userEvent.setup()
    render(<LockedAccountChatRetentionSection />)
    const days = await screen.findByLabelText('Löschen nach (Tage)')
    await user.clear(days)
    await user.type(days, '700')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Ändern' }),
    )
    await waitFor(async () =>
      expect((await getLockedAccountChatRetention()).deleteAfterDays).toBe(700),
    )

    await user.click(
      await screen.findByRole('button', { name: 'Werkseinstellung wiederherstellen' }),
    )
    expect(
      within(await screen.findByRole('dialog')).getByText(/früher als bisher/),
    ).toBeInTheDocument()
  })

  it('does not warn of an earlier deletion when the deletion is only switched on again', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/admin/retention/locked-account-chats', () =>
        HttpResponse.json({
          enabled: false,
          deleteAfterDays: 365,
          defaultEnabled: true,
          defaultDeleteAfterDays: 365,
          overridden: true,
          pausedBy: [],
        }),
      ),
    )
    render(<LockedAccountChatRetentionSection />)
    await user.click(await screen.findByRole('switch', { name: 'Chats nach der Sperre löschen' }))
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    const dialog = await screen.findByRole('dialog')
    expect(
      within(dialog).getByText(/beginnt mit dem nächsten nächtlichen Lauf neu/),
    ).toBeInTheDocument()
    expect(within(dialog).queryByText(/früher als bisher/)).not.toBeInTheDocument()
  })

  it('names the paused deletion and sets the shown values as the own ones', async () => {
    const user = userEvent.setup()
    let sent: unknown = null
    server.use(
      http.get('/api/v1/admin/retention/incident-scopes', () =>
        HttpResponse.json({
          retentionYears: 1,
          defaultRetentionYears: 1,
          overridden: false,
          pausedBy: [
            {
              variable: 'OPAA_AUDIT_INCIDENT_SCOPE_RETENTION_YEARS',
              retiredValue: '3',
              effectiveValue: '1',
            },
          ],
        }),
      ),
      http.put('/api/v1/admin/retention/incident-scopes', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({
          retentionYears: 1,
          defaultRetentionYears: 1,
          overridden: true,
          pausedBy: [],
        })
      }),
    )
    render(<IncidentScopeRetentionSection />)

    expect(await screen.findByText('Löschen pausiert')).toBeInTheDocument()
    expect(screen.getByText('OPAA_AUDIT_INCIDENT_SCOPE_RETENTION_YEARS')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Angezeigte Werte festlegen' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Wert festlegen' }),
    )

    await waitFor(() => expect(sent).toEqual({ retentionYears: 1 }))
    await waitFor(() => expect(screen.queryByText('Löschen pausiert')).not.toBeInTheDocument())
  })
})
