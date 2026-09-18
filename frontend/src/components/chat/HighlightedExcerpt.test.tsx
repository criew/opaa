import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import HighlightedExcerpt from './HighlightedExcerpt'

function marks(container: HTMLElement): string[] {
  return [...container.querySelectorAll('mark')].map((mark) => mark.textContent ?? '')
}

describe('HighlightedExcerpt', () => {
  it('marks exactly the ranges of the API and keeps the text in between', () => {
    const { container } = render(
      <HighlightedExcerpt
        text="Die Frist für den Widerspruch beträgt einen Monat."
        highlights={[
          { start: 4, end: 9 },
          { start: 18, end: 29 },
        ]}
      />,
    )

    expect(container).toHaveTextContent('Die Frist für den Widerspruch beträgt einen Monat.')
    expect(marks(container)).toEqual(['Frist', 'Widerspruch'])
  })

  it('does not rely on colour alone: a marked word is also set in bold and underlined', () => {
    const { container } = render(
      <HighlightedExcerpt text="Aktenzeichen 12/4" highlights={[{ start: 0, end: 12 }]} />,
    )

    const style = getComputedStyle(container.querySelector('mark')!)
    expect(style.fontWeight).toBe('700')
    expect(style.textDecorationLine || style.textDecoration).toContain('underline')
  })

  it('renders markup in the excerpt as literal text, never as HTML', () => {
    const text = '<img src=x onerror="alert(1)"><b>fett</b> Widerspruch'
    const { container } = render(
      <HighlightedExcerpt text={text} highlights={[{ start: 40, end: 51 }]} />,
    )

    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('b')).toBeNull()
    expect(container).toHaveTextContent(text)
    expect(marks(container)).toEqual(['Widerspruch'])
  })

  it('counts offsets in UTF-16 code units, as the API does', () => {
    // The emoji takes two code units, so the word starts at index 3.
    const { container } = render(
      <HighlightedExcerpt text="😀 Frist" highlights={[{ start: 3, end: 8 }]} />,
    )

    expect(marks(container)).toEqual(['Frist'])
  })

  it('ignores ranges that overlap, run backwards or lie outside the excerpt', () => {
    const { container } = render(
      <HighlightedExcerpt
        text="Frist und Widerspruch"
        highlights={[
          { start: 0, end: 5 },
          { start: 3, end: 8 },
          { start: 12, end: 10 },
          { start: 10, end: 99 },
        ]}
      />,
    )

    expect(container).toHaveTextContent('Frist und Widerspruch')
    expect(marks(container)).toEqual(['Frist'])
  })
})
