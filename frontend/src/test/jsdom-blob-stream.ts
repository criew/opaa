/**
 * jsdom (30.x) implements `Blob` without the spec's `stream()` method. Undici — the fetch
 * implementation behind `Request`, and therefore behind every request MSW intercepts — serialises a
 * `FormData` entry that carries a `File` by consuming `value.stream()`; without it the request body
 * never produces bytes, so an upload hangs instead of failing. This module installs a spec-shaped
 * `Blob.prototype.stream()` for the test environment; it is a no-op once jsdom ships its own.
 */
if (typeof Blob.prototype.stream !== 'function') {
  Blob.prototype.stream = function stream(this: Blob): ReadableStream<Uint8Array<ArrayBuffer>> {
    const bytes = this.arrayBuffer()
    return new ReadableStream<Uint8Array<ArrayBuffer>>({
      async pull(controller) {
        const buffer = await bytes
        if (buffer.byteLength > 0) {
          controller.enqueue(new Uint8Array(buffer))
        }
        controller.close()
      },
    })
  }
}
