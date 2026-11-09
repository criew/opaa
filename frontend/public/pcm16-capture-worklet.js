/**
 * Audio worklet of live dictation (ADR-0070): turns the microphone's float samples into PCM16 mono
 * little-endian at the rate the server named in `ready`, and posts one ArrayBuffer about every
 * 100 ms. Before decimating, a moving average over the decimation window damps what lies above the
 * target's Nyquist frequency, so it does not fold back as noise; then linear interpolation. The message `flush` posts what is left and then `flushed`.
 * Nothing is kept beyond the current frame.
 */
class Pcm16CaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super()
    this.targetRate = options.processorOptions.targetRate
    this.step = sampleRate / this.targetRate
    this.position = 0
    this.previous = 0
    this.window = new Float32Array(Math.max(1, Math.round(this.step)))
    this.windowIndex = 0
    this.windowSum = 0
    this.filtered = new Float32Array(128)
    this.frame = new Int16Array(Math.round(this.targetRate / 10))
    this.filled = 0
    this.port.onmessage = (event) => {
      if (event.data === 'flush') {
        this.post()
        this.port.postMessage('flushed')
      }
    }
  }

  post() {
    if (this.filled === 0) return
    const out = this.frame.slice(0, this.filled)
    this.port.postMessage(out.buffer, [out.buffer])
    this.filled = 0
  }

  push(sample) {
    const clamped = Math.max(-1, Math.min(1, sample))
    this.frame[this.filled++] = clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff
    if (this.filled === this.frame.length) this.post()
  }

  lowPass(input) {
    if (this.filtered.length < input.length) this.filtered = new Float32Array(input.length)
    const size = this.window.length
    for (let i = 0; i < input.length; i++) {
      this.windowSum += input[i] - this.window[this.windowIndex]
      this.window[this.windowIndex] = input[i]
      this.windowIndex = (this.windowIndex + 1) % size
      this.filtered[i] = this.windowSum / size
    }
    return this.filtered.subarray(0, input.length)
  }

  process(inputs) {
    const input = inputs[0] && inputs[0][0]
    if (!input) return true
    const channel = this.lowPass(input)
    // position runs over the virtual sequence [previous, ...channel]; index 0 is the last sample of
    // the block before, so interpolation across the block border needs no extra buffer.
    while (this.position < channel.length) {
      const index = Math.floor(this.position)
      const fraction = this.position - index
      const a = index === 0 ? this.previous : channel[index - 1]
      const b = channel[index]
      this.push(a + (b - a) * fraction)
      this.position += this.step
    }
    this.position -= channel.length
    this.previous = channel[channel.length - 1]
    return true
  }
}

registerProcessor('pcm16-capture', Pcm16CaptureProcessor)
