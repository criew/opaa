import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatInput from './ChatInput'
import { useAuthStore } from '../../stores/authStore'
import { useChatStore } from '../../stores/chatStore'
import { useSpaceStore } from '../../stores/spaceStore'

const mocks = vi.hoisted(() => ({
  available: true,
  supported: true,
  transcribeRecording: vi.fn(async (recording: Blob) =>
    recording.size >= 0 ? 'Welche Frist gilt?' : '',
  ),
  getTranscriptionAvailability: vi.fn(),
  tracksStopped: 0,
}))

vi.mock('../../services/transcriptionApi', () => ({
  transcribeRecording: mocks.transcribeRecording,
  getTranscriptionAvailability: mocks.getTranscriptionAvailability,
}))

vi.mock('../../utils/audioRecording', () => ({
  isRecordingSupported: () => mocks.supported,
  startRecording: async () => ({
    stop: async () => {
      mocks.tracksStopped += 1
      return new Blob(['audio'], { type: 'audio/webm' })
    },
    cancel: () => {
      mocks.tracksStopped += 1
    },
  }),
}))

/** #1926: the microphone fills the input and never sends; it exists only where dictation works. */
describe('ChatInput dictation', () => {
  beforeEach(() => {
    mocks.available = true
    mocks.supported = true
    mocks.tracksStopped = 0
    mocks.transcribeRecording.mockClear()
    mocks.getTranscriptionAvailability.mockReset()
    mocks.getTranscriptionAvailability.mockImplementation(async () => ({
      available: mocks.available,
      realtimeAvailable: false,
    }))
    useAuthStore.setState({ features: {} })
    useSpaceStore.setState({
      spaces: [],
      assetAssociations: [],
      hasAssetAssociations: true,
      hasKnowledge: true,
      hasReadableKnowledge: true,
      isLoadingAssetAssociations: false,
      assetAssociationsSpaceId: null,
    })
    useChatStore.setState({
      chatId: null,
      spaceId: null,
      scope: 'all',
      referencedLibraryIds: [],
      metadataFilter: null,
    })
  })

  afterEach(() => {
    useAuthStore.setState({ features: {} })
  })

  it('appends the dictated text to the draft and does not send it', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)
    const input = screen.getByRole('combobox')
    await user.type(input, 'Zum Bauantrag:')

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await user.click(await screen.findByRole('button', { name: 'Aufnahme beenden' }))

    await waitFor(() => expect(input).toHaveValue('Zum Bauantrag: Welche Frist gilt?'))
    expect(mocks.transcribeRecording).toHaveBeenCalledTimes(1)
    expect(onSend).not.toHaveBeenCalled()
    expect(mocks.tracksStopped).toBe(1)
  })

  it('discards a recording on cancel without sending it anywhere', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await user.click(await screen.findByRole('button', { name: 'Aufnahme verwerfen' }))

    expect(mocks.transcribeRecording).not.toHaveBeenCalled()
    expect(mocks.tracksStopped).toBe(1)
    expect(screen.getByRole('combobox')).toHaveValue('')
    expect(screen.getByRole('button', { name: 'Spracheingabe starten' })).toBeInTheDocument()
  })

  it('shows no microphone while the installation has no usable transcription', async () => {
    mocks.available = false
    render(<ChatInput onSend={vi.fn()} />)

    await waitFor(() => expect(mocks.getTranscriptionAvailability).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: 'Spracheingabe starten' })).not.toBeInTheDocument()
  })

  it('shows no microphone and asks nothing with the feature switched off', () => {
    useAuthStore.setState({ features: { speechInput: false } })
    render(<ChatInput onSend={vi.fn()} />)

    expect(screen.queryByRole('button', { name: 'Spracheingabe starten' })).not.toBeInTheDocument()
    expect(mocks.getTranscriptionAvailability).not.toHaveBeenCalled()
  })

  it('shows no microphone in a browser that cannot record', () => {
    mocks.supported = false
    render(<ChatInput onSend={vi.fn()} />)

    expect(screen.queryByRole('button', { name: 'Spracheingabe starten' })).not.toBeInTheDocument()
    expect(mocks.getTranscriptionAvailability).not.toHaveBeenCalled()
  })
})
