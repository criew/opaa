import { describe, expect, it } from 'vitest'
import { horizontalOffset, lastAtOrBefore, readingLine } from './readingPosition'

describe('readingLine', () => {
  it('lies a quarter down the view', () => {
    expect(readingLine({ scrollTop: 1000, clientHeight: 800, scrollHeight: 5000 })).toBe(1200)
  })

  it('lies where a view asks for it', () => {
    expect(readingLine({ scrollTop: 1000, clientHeight: 800, scrollHeight: 5000 }, 1 / 2)).toBe(
      1400,
    )
  })

  it('moves down over the last screen and reaches the bottom at the end', () => {
    expect(readingLine({ scrollTop: 3800, clientHeight: 800, scrollHeight: 5000 })).toBe(4300)
    expect(readingLine({ scrollTop: 4200, clientHeight: 800, scrollHeight: 5000 })).toBe(5000)
  })

  it('starts a quarter down even when the content is barely taller than the view', () => {
    expect(readingLine({ scrollTop: 0, clientHeight: 800, scrollHeight: 1000 })).toBe(200)
    expect(readingLine({ scrollTop: 200, clientHeight: 800, scrollHeight: 1000 })).toBe(1000)
  })
})

describe('lastAtOrBefore', () => {
  const entries = [
    { id: 'a', position: 0 },
    { id: 'c', position: 500 },
    { id: 'b', position: 200 },
    { id: 'b2', position: 200 },
  ]

  it('takes the entry nearest above the line, whatever its place in reading order', () => {
    expect(lastAtOrBefore(entries, 300)?.id).toBe('b2')
    expect(lastAtOrBefore(entries, 600)?.id).toBe('c')
  })

  it('finds nothing while every entry lies below the line', () => {
    expect(lastAtOrBefore([{ position: 50 }], 10)).toBeNull()
  })
})

describe('horizontalOffset', () => {
  it('centres a page that fits into the view', () => {
    expect(horizontalOffset({ pageLeft: 300, pageWidth: 600, viewWidth: 800 })).toBe(200)
  })

  it('never scrolls before the start of the content', () => {
    expect(horizontalOffset({ pageLeft: 0, pageWidth: 600, viewWidth: 800 })).toBe(0)
  })

  it('aligns a wider page left while its target is in view', () => {
    const target = { left: 500, right: 700 }
    expect(horizontalOffset({ pageLeft: 100, pageWidth: 1200, viewWidth: 800, target })).toBe(100)
  })

  it('scrolls on to a target beyond the right edge and keeps a margin before it', () => {
    const target = { left: 1000, right: 1050 }
    expect(horizontalOffset({ pageLeft: 0, pageWidth: 2000, viewWidth: 800, target })).toBe(952)
  })

  it('scrolls no further than the page end allows', () => {
    const target = { left: 1150, right: 1180 }
    expect(horizontalOffset({ pageLeft: 0, pageWidth: 1200, viewWidth: 800, target })).toBe(400)
  })
})
