import { afterEach, describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../../test/test-utils'
import type { LocalUserResponse } from '@/shared/api/types'
import { mockChatCleanupHolds, mockLocalUsers } from '../mocks/localUserFixtures'
import LocalUserChatCleanupSection from './LocalUserChatCleanupSection'
import { chatCleanupText } from './localUserLabels'

function fixture(id: string): LocalUserResponse {
  const user = mockLocalUsers.find((candidate) => candidate.id === id)
  if (!user) throw new Error(`fixture ${id} missing`)
  return user
}

/**
 * The deletion of a locked account's chats in the single view of the account: the date for a lock
 * by the administration, nothing that depends on chats or a Klärung, and hold and resume.
 */
describe('LocalUserChatCleanupSection', () => {
  afterEach(() => {
    mockChatCleanupHolds.clear()
  })

  it('names the earliest deletion of an account locked by the administration', async () => {
    renderWithProviders(<LocalUserChatCleanupSection user={fixture('local-user-vogt')} />)

    expect(await screen.findByText(/frühestens am 23\.10\.2027 gelöscht/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Löschung anhalten' })).toBeInTheDocument()
  })

  it('holds and resumes the deletion', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LocalUserChatCleanupSection user={fixture('local-user-vogt')} />)

    await user.click(await screen.findByRole('button', { name: 'Löschung anhalten' }))
    expect(await screen.findByText(/ist für dieses Konto angehalten/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Löschung fortsetzen' }))
    expect(await screen.findByText(/frühestens am 23\.10\.2027 gelöscht/)).toBeInTheDocument()
  })

  it('tells an account that is not locked by the administration what a lock would start', async () => {
    renderWithProviders(<LocalUserChatCleanupSection user={fixture('local-user-ernst')} />)

    expect(await screen.findByText(/365 Tage danach gelöscht/)).toBeInTheDocument()
  })

  it('words every state without a count of chats', () => {
    for (const status of ['SCHEDULED', 'HELD', 'SWITCHED_OFF', 'NOT_LOCKED'] as const) {
      const text = chatCleanupText({
        status,
        held: status === 'HELD',
        deleteAfterDays: 365,
        earliestDeletionAt: status === 'SCHEDULED' ? '2027-10-23T08:00:00Z' : null,
      })
      expect(text).not.toMatch(/\d+ Chats?/)
    }
  })
})
