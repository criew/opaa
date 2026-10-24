import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { buildCitationIndex } from '@/shared/ui/markdown/citations'
import CopyAnswerButton from './CopyAnswerButton'

const state = vi.hoisted(() => ({ fail: false }))

vi.mock('./copyHtml', async (importOriginal) => {
  const original = await importOriginal<typeof import('./copyHtml')>()
  return {
    ...original,
    answerAsHtml: (...args: Parameters<typeof original.answerAsHtml>) => {
      if (state.fail) throw new Error('chunk could not be loaded')
      return original.answerAsHtml(...args)
    },
  }
})

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

describe('CopyAnswerButton without the HTML flavour', () => {
  afterEach(() => {
    state.fail = false
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('copies the text with the note when the HTML code fails, and says it copied', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('ClipboardItem', FakeClipboardItem)
    const original = navigator.clipboard.write.bind(navigator.clipboard)
    // like a browser: a failing part of the item fails the whole write
    vi.spyOn(navigator.clipboard, 'write').mockImplementation(async (items) => {
      for (const item of items) await Promise.all(item.types.map((type) => item.getType(type)))
      return original(items)
    })
    state.fail = true
    render(
      <CopyAnswerButton
        content="Antwort"
        citations={buildCitationIndex('Antwort', [])}
        answeredAt={new Date('2026-10-24T10:00:00')}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Antwort kopieren' }))

    await waitFor(async () => {
      const [item] = await navigator.clipboard.read()
      expect(item.types).toEqual(['text/plain'])
    })
    expect(await navigator.clipboard.readText()).toContain('Antwort\n\n---\n\n')
    expect(await navigator.clipboard.readText()).toContain('24.10.2026')
    expect(screen.getByRole('button', { name: 'Antwort kopiert' })).toBeInTheDocument()
  })
})
