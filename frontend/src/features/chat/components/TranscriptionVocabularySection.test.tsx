import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/test-utils'
import { mockTranscriptionVocabulary } from '../mocks/transcriptionHandlers'
import TranscriptionVocabularySection from './TranscriptionVocabularySection'

const availability = vi.hoisted(() => ({ value: true }))
vi.mock('../hooks/useTranscriptionAvailability', () => ({
  useTranscriptionAvailability: () => availability.value,
}))

describe('TranscriptionVocabularySection', () => {
  beforeEach(() => {
    availability.value = true
    mockTranscriptionVocabulary.value = 'Referat Z 4'
  })

  afterEach(() => {
    mockTranscriptionVocabulary.value = ''
  })

  it('is not shown where dictation is not offered', () => {
    availability.value = false
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

  it('shows the refusal of a list over the cap at the field', async () => {
    const user = userEvent.setup()
    renderWithProviders(<TranscriptionVocabularySection />)

    const field = await screen.findByLabelText('Begriffsliste')
    await waitFor(() => expect(field).toHaveValue('Referat Z 4'))
    await user.clear(field)
    await user.click(field)
    await user.paste('x'.repeat(501))

    expect(screen.getByText(/Höchstens 500 Zeichen/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    expect(
      await screen.findByText('Die Begriffsliste umfasst höchstens 500 Zeichen.'),
    ).toBeInTheDocument()
    expect(mockTranscriptionVocabulary.value).toBe('Referat Z 4')
  })
})
