import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DONE_TIMEOUT_MS, READY_TIMEOUT_MS, startLiveTranscription } from './liveTranscription'

const mocks = vi.hoisted(() => ({ sockets: [] as FakeSocket[], nodes: [] as FakeNode[] }))

class FakeSocket {
  readyState = 1
  bufferedAmount = 0
  sent: Array<string | ArrayBuffer> = []
  closedWith: number | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null
  send(data: string | ArrayBuffer) {
    this.sent.push(data)
  }
  close(code: number) {
    this.closedWith = code
    this.readyState = 3
  }
  receive(message: object) {
    this.onmessage?.({ data: JSON.stringify(message) })
  }
  drop() {
    this.readyState = 3
    this.onclose?.()
  }
  commits() {
    return this.sent.filter((data) => data === JSON.stringify({ type: 'commit' }))
  }
}

class FakeNode {
  options: { processorOptions: { targetRate: number } }
  disconnected = false
  port = {
    onmessage: null as ((event: { data: unknown }) => void) | null,
    posted: [] as unknown[],
    postMessage: (message: unknown) => {
      this.port.posted.push(message)
      if (message === 'flush') {
        this.port.onmessage?.({ data: new Int16Array([7, 8]).buffer })
        this.port.onmessage?.({ data: 'flushed' })
      }
    },
    close: () => {},
  }
  constructor(_context: unknown, _name: string, options: FakeNode['options']) {
    this.options = options
    mocks.nodes.push(this)
  }
  disconnect() {
    this.disconnected = true
  }
}

class FakeAudioContext {
  audioWorklet = { addModule: vi.fn(async () => {}) }
  closed = false
  createMediaStreamSource() {
    return { connect: () => {} }
  }
  resume() {
    return Promise.resolve()
  }
  close() {
    this.closed = true
    return Promise.resolve()
  }
}

vi.mock('../api/transcriptionApi', () => ({
  openTranscriptionStream: async () => {
    const socket = new FakeSocket()
    mocks.sockets.push(socket)
    return socket
  },
}))

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

/** Live dictation in OPAA's protocol: ready, audio, commit, delta, done - and every way it breaks. */
describe('startLiveTranscription', () => {
  beforeEach(() => {
    mocks.sockets.length = 0
    mocks.nodes.length = 0
    vi.stubGlobal('AudioContext', FakeAudioContext)
    vi.stubGlobal('AudioWorkletNode', FakeNode)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  async function started() {
    const onText = vi.fn()
    const onBroken = vi.fn()
    const live = startLiveTranscription({ stream: {} as MediaStream, onText, onBroken })
    await flush()
    const socket = mocks.sockets[0]
    socket.receive({ type: 'ready', sampleRate: 24000 })
    await flush()
    return { live, socket, node: mocks.nodes[0], onText, onBroken }
  }

  it('feeds PCM16 at the rate of ready, passes deltas on and answers the final text', async () => {
    const { live, socket, node, onText, onBroken } = await started()
    expect(node.options.processorOptions.targetRate).toBe(24000)

    const frame = new Int16Array([1, 2, 3]).buffer
    node.port.onmessage?.({ data: frame })
    expect(socket.sent).toContain(frame)
    socket.receive({ type: 'delta', text: 'Guten' })
    expect(onText).toHaveBeenCalledWith('Guten')

    const final = live.finish()
    expect(node.port.posted).toContain('flush')
    expect(socket.sent.at(-2)).toBeInstanceOf(ArrayBuffer)
    expect(socket.commits()).toHaveLength(1)
    socket.receive({ type: 'done', text: 'Guten Tag.' })

    await expect(final).resolves.toBe('Guten Tag.')
    expect(onBroken).not.toHaveBeenCalled()
    expect(socket.closedWith).toBe(1000)
  })

  it('reports a server error mid-recording as broken and sends nothing more', async () => {
    const { live, socket, node, onBroken } = await started()

    socket.receive({ type: 'error', reason: 'upstream' })

    expect(onBroken).toHaveBeenCalledTimes(1)
    expect(node.disconnected).toBe(true)
    await expect(live.finish()).rejects.toThrow()
    expect(socket.commits()).toHaveLength(0)
  })

  it('rejects the final text when the connection drops after the commit', async () => {
    const { live, socket, onBroken } = await started()

    const final = live.finish()
    socket.drop()

    await expect(final).rejects.toThrow()
    expect(onBroken).not.toHaveBeenCalled()
  })

  it('rejects the final text when it does not arrive in time', async () => {
    const { live } = await started()
    vi.useFakeTimers()

    const final = live.finish()
    vi.advanceTimersByTime(DONE_TIMEOUT_MS)

    await expect(final).rejects.toThrow()
  })

  it('is broken when the server never names its sample rate', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const onBroken = vi.fn()
    startLiveTranscription({ stream: {} as MediaStream, onText: vi.fn(), onBroken })
    await vi.advanceTimersByTimeAsync(1)

    await vi.advanceTimersByTimeAsync(READY_TIMEOUT_MS)

    expect(onBroken).toHaveBeenCalledTimes(1)
    expect(mocks.nodes).toHaveLength(0)
  })

  it('ends everything on cancel without reporting it as broken', async () => {
    const { live, socket, onBroken, onText } = await started()

    live.cancel()
    socket.receive({ type: 'delta', text: 'spät' })

    expect(socket.closedWith).toBe(1000)
    expect(onBroken).not.toHaveBeenCalled()
    expect(onText).not.toHaveBeenCalled()
  })
})
