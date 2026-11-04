import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/test-utils'
import { mockTranscriptionVocabulary } from '../mocks/transcriptionHandlers'
import TranscriptionVocabularySection from './TranscriptionVocabularySection'

const feature = vi.hoisted(() => ({ enabled: true }))
vi.mock('@/shared/session/useFeatureEnabled', () => ({
  useFeatureEnabled: () => feature.enabled,
}))

describe('TranscriptionVocabularySection', () => {
  beforeEach(() => {
    feature.enabled = true
    mockTranscriptionVocabulary.value = 'Referat Z 4'
  })

  afterEach(() => {
    mockTranscriptionVocabulary.value = ''
  })

  it('is not shown while the operation has switched off speech input', () => {
    feature.enabled = false
    renderWithProviders(<TranscriptionVocabularySection />)

    expect(screen.queryByText('Spracheingabe')).not.toBeInTheDocument()
  })

  it('loads and saves the own list', async () => {
    const user = userEvent.setup()
    renderWithProviders(<TranscriptionVocabularySection />)

    const field = await screen.findByLabelText('Begriffsliste')
    await waitFor(() => expect(field).toHaveValue('Referat Z 4'))
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeDisabled()

    await user.type(field, ', Az. 12-345')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(mockTranscriptionVocabulary.value).toBe('Referat Z 4, Az. 12-345'))
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeDisabled()
  })

  it('does not send a list over the cap', async () => {
    const user = userEvent.setup()
    renderWithProviders(<TranscriptionVocabularySection />)

    const field = await screen.findByLabelText('Begriffsliste')
    await waitFor(() => expect(field).toHaveValue('Referat Z 4'))
    await user.clear(field)
    await user.click(field)
    await user.paste('x'.repeat(501))

    expect(screen.getByText(/Höchstens 500 Zeichen/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeDisabled()
  })
})
