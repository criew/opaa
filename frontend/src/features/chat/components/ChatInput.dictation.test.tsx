import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatInput from './ChatInput'
import { useAuthStore } from '@/shared/session/authStore'
import { useChatStore } from '../stores/chatStore'
import { useSpaceStore } from '@/features/space'

const mocks = vi.hoisted(() => ({
  available: true,
  supported: true,
  transcribeRecording: vi.fn(async (recording: Blob) =>
    recording.size >= 0 ? 'Welche Frist gilt?' : '',
  ),
  getTranscriptionAvailability: vi.fn(),
  tracksStopped: 0,
  realtime: false,
  live: null as null | { onText: (text: string) => void },
}))

vi.mock('../utils/liveTranscription', () => ({
  startLiveTranscription: (options: { onText: (text: string) => void }) => {
    mocks.live = options
    return { finish: async () => 'Welche Frist gilt live?', cancel: () => {} }
  },
}))

vi.mock('../api/transcriptionApi', () => ({
  transcribeRecording: mocks.transcribeRecording,
  getTranscriptionAvailability: mocks.getTranscriptionAvailability,
}))

vi.mock('../utils/audioRecording', () => ({
  isRecordingSupported: () => mocks.supported,
  startRecording: async () => ({
    stream: {},
    stop: async () => {
      mocks.tracksStopped += 1
      return new Blob(['audio'], { type: 'audio/webm' })
    },
    cancel: () => {
      mocks.tracksStopped += 1
    },
  }),
}))

/** The microphone fills the input and never sends; it exists only where dictation works. */
describe('ChatInput dictation', () => {
  beforeEach(() => {
    mocks.available = true
    mocks.supported = true
    mocks.tracksStopped = 0
    mocks.realtime = false
    mocks.live = null
    mocks.transcribeRecording.mockClear()
    mocks.getTranscriptionAvailability.mockReset()
    mocks.getTranscriptionAvailability.mockImplementation(async () => ({
      available: mocks.available,
      realtimeAvailable: mocks.realtime,
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
    vi.unstubAllGlobals()
  })

  it('shows the live text dimmed while speaking and takes the final live text', async () => {
    mocks.realtime = true
    vi.stubGlobal('AudioContext', class {})
    vi.stubGlobal('AudioWorkletNode', class {})
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await waitFor(() => expect(mocks.live).not.toBeNull())
    act(() => mocks.live?.onText('Welche Frist'))
    expect(await screen.findByTestId('dictation-interim')).toHaveTextContent('Welche Frist')
    expect(screen.getByTestId('dictation-interim')).toHaveStyle({ fontStyle: 'italic' })
    await user.click(screen.getByRole('button', { name: 'Aufnahme beenden' }))

    await waitFor(() => expect(screen.getByRole('combobox')).toHaveValue('Welche Frist gilt live?'))
    expect(mocks.transcribeRecording).not.toHaveBeenCalled()
    expect(screen.queryByTestId('dictation-interim')).not.toBeInTheDocument()
  })

  it('offers no live text where the browser has no audio worklet', async () => {
    mocks.realtime = true
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await user.click(await screen.findByRole('button', { name: 'Aufnahme beenden' }))

    await waitFor(() => expect(mocks.transcribeRecording).toHaveBeenCalledTimes(1))
    expect(mocks.live).toBeNull()
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
