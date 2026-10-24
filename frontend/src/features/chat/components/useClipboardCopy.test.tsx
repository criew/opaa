import { act, renderHook } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useClipboardCopy } from './useClipboardCopy'

// Shaped like the browser's ClipboardItem, so the clipboard double of user-event can read it back.
class FakeClipboardItem {
  data: Record<string, Blob | string | Promise<Blob | string>>
  constructor(data: Record<string, Blob | string | Promise<Blob | string>>) {
    this.data = data
  }
  get types() {
    return Object.keys(this.data)
  }
  async getType(type: string) {
    const value = await this.data[type]
    return value instanceof Blob ? value : new Blob([value], { type })
  }
}

/** Like a browser: a write whose item has a failing part fails as a whole. */
function rejectWritesWithFailingParts() {
  const original = navigator.clipboard.write.bind(navigator.clipboard)
  vi.spyOn(navigator.clipboard, 'write').mockImplementation(async (items) => {
    for (const item of items) {
      await Promise.all(item.types.map((type) => item.getType(type)))
    }
    return original(items)
  })
}

describe('useClipboardCopy', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('writes the HTML flavour next to the text when the markup arrives', async () => {
    userEvent.setup()
    vi.stubGlobal('ClipboardItem', FakeClipboardItem)
    const { result } = renderHook(() => useClipboardCopy('Die Antwort'))

    await act(() => result.current.copy('Text', () => Promise.resolve('<p>Text</p>')))

    const [item] = await navigator.clipboard.read()
    expect(item.types).toEqual(['text/plain', 'text/html'])
  })

  it('falls back to the plain text when the HTML cannot be produced', async () => {
    userEvent.setup()
    vi.stubGlobal('ClipboardItem', FakeClipboardItem)
    rejectWritesWithFailingParts()
    const { result } = renderHook(() => useClipboardCopy('Die Antwort'))

    await act(() => result.current.copy('Text', () => Promise.reject(new Error('chunk'))))

    const [item] = await navigator.clipboard.read()
    expect(item.types).toEqual(['text/plain'])
    expect(await navigator.clipboard.readText()).toBe('Text')
  })
})
