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

  it('shows the live text in the field while speaking and takes the final live text', async () => {
    mocks.realtime = true
    vi.stubGlobal('AudioContext', class {})
    vi.stubGlobal('AudioWorkletNode', class {})
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await waitFor(() => expect(mocks.live).not.toBeNull())
    act(() => mocks.live?.onText('Welche Frist'))
    expect(await screen.findByTestId('dictation-interim')).toHaveTextContent('Welche Frist')
    expect(screen.getByTestId('dictation-field')).toContainElement(
      screen.getByTestId('dictation-interim'),
    )
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Fertig, Text ins Eingabefeld' }))

    await waitFor(() => expect(screen.getByRole('textbox')).toHaveValue('Welche Frist gilt live?'))
    expect(mocks.transcribeRecording).not.toHaveBeenCalled()
    expect(screen.queryByTestId('dictation-interim')).not.toBeInTheDocument()
  })

  it('offers no live text where the browser has no audio worklet', async () => {
    mocks.realtime = true
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await user.click(await screen.findByRole('button', { name: 'Fertig, Text ins Eingabefeld' }))

    await waitFor(() => expect(mocks.transcribeRecording).toHaveBeenCalledTimes(1))
    expect(mocks.live).toBeNull()
  })

  it('appends the dictated text to the draft and does not send it', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)
    await user.type(screen.getByRole('textbox'), 'Zum Bauantrag:')

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    expect(screen.getByTestId('dictation-field')).toHaveTextContent('Zum Bauantrag:')
    await user.click(await screen.findByRole('button', { name: 'Fertig, Text ins Eingabefeld' }))

    await waitFor(() =>
      expect(screen.getByRole('textbox')).toHaveValue('Zum Bauantrag: Welche Frist gilt?'),
    )
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
    expect(screen.getByRole('textbox')).toHaveValue('')
    expect(screen.getByRole('button', { name: 'Spracheingabe starten' })).toBeInTheDocument()
  })

  it('sends at once with the final text when send is pressed while recording', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)
    await user.type(screen.getByRole('textbox'), 'Zum Bauantrag:')

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await user.click(await screen.findByRole('button', { name: 'Senden' }))

    await waitFor(() => expect(onSend).toHaveBeenCalledWith('Zum Bauantrag: Welche Frist gilt?'))
    expect(mocks.tracksStopped).toBe(1)
    expect(screen.getByRole('textbox')).toHaveValue('')
  })

  it('sends the final live text, never the interim text', async () => {
    mocks.realtime = true
    vi.stubGlobal('AudioContext', class {})
    vi.stubGlobal('AudioWorkletNode', class {})
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await waitFor(() => expect(mocks.live).not.toBeNull())
    act(() => mocks.live?.onText('Welche Frist'))
    await user.click(screen.getByRole('button', { name: 'Senden' }))

    await waitFor(() => expect(onSend).toHaveBeenCalledWith('Welche Frist gilt live?'))
    expect(onSend).toHaveBeenCalledTimes(1)
    expect(mocks.transcribeRecording).not.toHaveBeenCalled()
  })

  it('moves the focus to the take-over button on start and back to the field on discard', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    const takeOver = await screen.findByRole('button', { name: 'Fertig, Text ins Eingabefeld' })
    await waitFor(() => expect(takeOver).toHaveFocus())
    await user.click(screen.getByRole('button', { name: 'Aufnahme verwerfen' }))

    await waitFor(() => expect(screen.getByRole('textbox')).toHaveFocus())
  })

  it('leaves the focus in the field after sending during a recording', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await user.click(await screen.findByRole('button', { name: 'Senden' }))

    await waitFor(() => expect(onSend).toHaveBeenCalledWith('Welche Frist gilt?'))
    expect(screen.getByRole('textbox')).toHaveFocus()
  })

  it('keeps a question that is too long in the field instead of sending it', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)
    await user.click(screen.getByRole('textbox'))
    await user.paste('x'.repeat(1995))

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await user.click(await screen.findByRole('button', { name: 'Senden' }))

    await waitFor(() =>
      expect(screen.getByRole('textbox')).toHaveValue(`${'x'.repeat(1995)} Welche Frist gilt?`),
    )
    expect(onSend).not.toHaveBeenCalled()
  })

  it('keeps a refused question that comes back while dictating', async () => {
    const user = userEvent.setup()
    let refuse: (value: { restoreDraft: string }) => void = () => {}
    const onSend = vi.fn(
      () => new Promise<{ restoreDraft: string }>((resolve) => (refuse = resolve)),
    )
    render(<ChatInput onSend={onSend} />)
    await user.type(screen.getByRole('textbox'), 'Erste Frage{Enter}')

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await screen.findByRole('button', { name: 'Fertig, Text ins Eingabefeld' })
    await act(async () => refuse({ restoreDraft: 'Erste Frage' }))
    expect(screen.getByTestId('dictation-field')).toHaveTextContent('Erste Frage')
    await user.click(screen.getByRole('button', { name: 'Fertig, Text ins Eingabefeld' }))

    await waitFor(() =>
      expect(screen.getByRole('textbox')).toHaveValue('Erste Frage Welche Frist gilt?'),
    )
  })

  it('discards the recording on Escape wherever the focus is', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)
    await user.type(screen.getByRole('textbox'), 'Entwurf')

    await user.click(await screen.findByRole('button', { name: 'Spracheingabe starten' }))
    await screen.findByRole('button', { name: 'Fertig, Text ins Eingabefeld' })
    await user.keyboard('{Escape}')

    expect(await screen.findByRole('textbox')).toHaveValue('Entwurf')
    expect(mocks.transcribeRecording).not.toHaveBeenCalled()
    expect(mocks.tracksStopped).toBe(1)
    expect(onSend).not.toHaveBeenCalled()
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
