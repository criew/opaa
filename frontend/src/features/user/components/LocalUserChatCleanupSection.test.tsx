import { afterEach, describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../../test/test-utils'
import type { LocalUserResponse } from '@/shared/api/types'
import {
  mockChatCleanupHolds,
  mockChatCleanupRequests,
  mockLocalUsers,
} from '../mocks/localUserFixtures'
import LocalUserChatCleanupSection from './LocalUserChatCleanupSection'
import { CHAT_CLEANUP_HOLD_PURPOSE_LABEL, chatCleanupText, remainingDays } from './localUserLabels'

function fixture(id: string): LocalUserResponse {
  const user = mockLocalUsers.find((candidate) => candidate.id === id)
  if (!user) throw new Error(`fixture ${id} missing`)
  return user
}

const REASON = 'Anordnung der Staatsanwaltschaft, Az. 12 Js 345/26'

/**
 * The deletion of a locked account's chats in the single view of the account: the date for a lock
 * by the administration, nothing that depends on chats or a Klärung, and a hold only by request
 * and approval of two administrators, with its end in sight.
 */
describe('LocalUserChatCleanupSection', () => {
  afterEach(() => {
    mockChatCleanupHolds.clear()
    mockChatCleanupRequests.clear()
  })

  it('names the earliest deletion of an account locked by the administration', async () => {
    renderWithProviders(<LocalUserChatCleanupSection user={fixture('local-user-vogt')} />)

    expect(await screen.findByText(/frühestens am 23\.10\.2027 gelöscht/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Anhaltung beantragen' })).toBeInTheDocument()
  })

  it('files a request with purpose and reason that only a second administrator approves', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LocalUserChatCleanupSection user={fixture('local-user-vogt')} />)

    await user.click(await screen.findByRole('button', { name: 'Anhaltung beantragen' }))
    const submit = screen.getByRole('button', { name: 'Antrag stellen' })
    expect(submit).toBeDisabled()
    expect(screen.getByText(/disziplinarischer, arbeitsrechtlicher/)).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'Zweck' }))
    const options = screen.getAllByRole('option').map((option) => option.textContent)
    expect(options).toEqual(Object.values(CHAT_CLEANUP_HOLD_PURPOSE_LABEL))
    expect(options.join(' ')).not.toMatch(/Disziplinar/i)
    await user.click(
      screen.getByRole('option', { name: 'Behördliche oder gerichtliche Anordnung' }),
    )
    await user.type(screen.getByRole('textbox', { name: /Begründung/ }), 'kurz')
    expect(submit).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: /Begründung/ }), ` – ${REASON}`)
    await user.click(submit)

    const pending = await screen.findByTestId('chat-cleanup-pending')
    expect(within(pending).getByText('Anhaltung beantragt, wartet auf Freigabe')).toBeVisible()
    expect(within(pending).getByText(/Freigeben kann nur eine zweite Person/)).toBeVisible()
    expect(within(pending).queryByRole('button', { name: 'Freigeben' })).not.toBeInTheDocument()
    // the request alone holds nothing
    expect(screen.getByText(/frühestens am 23\.10\.2027 gelöscht/)).toBeInTheDocument()

    await user.click(within(pending).getByRole('button', { name: 'Antrag zurückziehen' }))
    expect(await screen.findByRole('button', { name: 'Anhaltung beantragen' })).toBeVisible()
  })

  it('lets a second administrator approve and shows purpose and remaining days', async () => {
    mockChatCleanupRequests.set('local-user-vogt', {
      purpose: 'DATA_SUBJECT_REQUEST',
      reason: REASON,
      requestedAt: '2026-10-30T08:00:00Z',
      byMe: false,
    })
    const user = userEvent.setup()
    renderWithProviders(<LocalUserChatCleanupSection user={fixture('local-user-vogt')} />)

    await user.click(await screen.findByRole('button', { name: 'Freigeben' }))

    const hold = await screen.findByTestId('chat-cleanup-hold')
    expect(within(hold).getByText(/noch 180 Tage/)).toBeVisible()
    expect(within(hold).getByText('Zweck: Laufender Auskunfts- oder Löschantrag')).toBeVisible()
    expect(within(hold).getByText(`Begründung: ${REASON}`)).toBeVisible()
    expect(screen.getByText(/ist für dieses Konto angehalten/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Verlängerung beantragen' })).toBeVisible()

    await user.click(screen.getByRole('button', { name: 'Löschung fortsetzen' }))
    expect(await screen.findByText(/frühestens am 23\.10\.2027 gelöscht/)).toBeInTheDocument()
  })

  it('lets a second administrator reject a request', async () => {
    mockChatCleanupRequests.set('local-user-vogt', {
      purpose: 'SECURITY_INCIDENT_EVIDENCE',
      reason: REASON,
      requestedAt: '2026-10-30T08:00:00Z',
      byMe: false,
    })
    const user = userEvent.setup()
    renderWithProviders(<LocalUserChatCleanupSection user={fixture('local-user-vogt')} />)

    await user.click(await screen.findByRole('button', { name: 'Ablehnen' }))

    expect(await screen.findByRole('button', { name: 'Anhaltung beantragen' })).toBeVisible()
    expect(screen.queryByTestId('chat-cleanup-pending')).not.toBeInTheDocument()
  })

  it('tells an account that is not locked by the administration what a lock would start', async () => {
    renderWithProviders(<LocalUserChatCleanupSection user={fixture('local-user-ernst')} />)

    expect(await screen.findByText(/365 Tage danach gelöscht/)).toBeInTheDocument()
  })

  it('counts the remaining days of a hold up and never below zero', () => {
    const now = new Date('2026-10-31T12:00:00Z')
    expect(remainingDays('2026-11-02T00:00:00Z', now)).toBe(2)
    expect(remainingDays('2026-10-01T00:00:00Z', now)).toBe(0)
  })

  it('words every state without a count of chats', () => {
    for (const status of ['SCHEDULED', 'HELD', 'SWITCHED_OFF', 'NOT_LOCKED'] as const) {
      const text = chatCleanupText({
        status,
        held: status === 'HELD',
        deleteAfterDays: 365,
        holdMaxDays: 180,
        earliestDeletionAt: status === 'SCHEDULED' ? '2027-10-23T08:00:00Z' : null,
      })
      expect(text).not.toMatch(/\d+ Chats?/)
    }
  })
})
