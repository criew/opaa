import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { headingTree, type HeadingNode } from './headingOutline'
import { useHeadingNavigation } from './useHeadingNavigation'

/**
 * A scrolling box 400 px high with headings at fixed offsets in its content: jsdom has no layout,
 * so the boxes report where the headings stand for the current scroll position.
 */
function scrollingDocument(offsets: number[]) {
  const scroller = document.createElement('div')
  Object.defineProperty(scroller, 'clientHeight', { value: 400 })
  Object.defineProperty(scroller, 'scrollTop', { value: 0, writable: true })
  scroller.getBoundingClientRect = () => ({ top: 100 }) as DOMRect
  scroller.scrollTo = ((options: ScrollToOptions) => {
    scroller.scrollTop = options.top ?? scroller.scrollTop
    scroller.dispatchEvent(new Event('scroll'))
  }) as typeof scroller.scrollTo
  const headings = offsets.map((offset) => {
    const heading = document.createElement('h2')
    heading.getBoundingClientRect = () => ({ top: 100 + offset - scroller.scrollTop }) as DOMRect
    scroller.appendChild(heading)
    return heading
  })
  document.body.appendChild(scroller)
  return { scroller, headings }
}

function scrollTo(scroller: HTMLElement, top: number) {
  act(() => {
    scroller.scrollTop = top
    scroller.dispatchEvent(new Event('scroll'))
  })
}

const NODES = headingTree([
  { level: 1, title: 'Eins' },
  { level: 2, title: 'Eins.Eins' },
  { level: 1, title: 'Zwei' },
  { level: 1, title: 'Drei' },
])
const [EINS, EINS_EINS] = [NODES[0], NODES[0].children[0]]
const [ZWEI, DREI] = [NODES[1], NODES[2]]

beforeEach(() => {
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    // runs the frame at once; 0 tells the hook no frame is pending any more
    callback(0)
    return 0
  })
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

function setup() {
  // content 2000 px high: Drei is the last, short section and never reaches the reading line
  const { scroller, headings } = scrollingDocument([0, 500, 900, 1800])
  const hook = renderHook(
    ({ nodes }: { nodes: HeadingNode[] }) => useHeadingNavigation(scroller, nodes, () => headings),
    { initialProps: { nodes: NODES } },
  )
  return { scroller, headings, hook }
}

describe('useHeadingNavigation', () => {
  it('marks the last heading at or above the reading line, half way down the view', () => {
    const { scroller, hook } = setup()
    expect(hook.result.current.active).toBe(EINS)

    // Eins.Eins at 500 reaches the line at scrollTop 300 (200 px into the 400 px view)
    scrollTo(scroller, 299)
    expect(hook.result.current.active).toBe(EINS)
    scrollTo(scroller, 300)
    expect(hook.result.current.active).toBe(EINS_EINS)
  })

  it('marks the section of a passage the view opened at, a third down', () => {
    const { scroller, hook } = setup()
    // the passage under Zwei stands a third down, its heading just above it
    scrollTo(scroller, 780)
    expect(hook.result.current.active).toBe(ZWEI)
  })

  it('keeps the chosen entry active after a jump until the reader moves the view', () => {
    const { scroller, headings, hook } = setup()
    const spy = vi.spyOn(scroller, 'scrollTo')

    act(() => hook.result.current.goTo(DREI))

    // vertically only, 16 px above the heading
    expect(spy).toHaveBeenCalledWith({ top: 1784 })
    expect(hook.result.current.active).toBe(DREI)
    expect(headings[3]).toHaveFocus()

    // the content cannot scroll that far: the view stops short and the line stays above Drei
    scrollTo(scroller, 1550)
    expect(hook.result.current.active).toBe(DREI)

    act(() => {
      scroller.dispatchEvent(new Event('wheel'))
    })
    expect(hook.result.current.active).toBe(ZWEI)
  })

  it('marks the section of the passage the view was brought to, wherever it stands', () => {
    const { scroller, hook } = setup()
    // a short passage of Eins.Eins with Zwei's heading above the reading line already
    const passage = document.createElement('p')
    scroller.insertBefore(passage, scroller.children[2])
    scrollTo(scroller, 750)
    expect(hook.result.current.active).toBe(ZWEI)

    act(() => hook.result.current.follow(passage))
    expect(hook.result.current.active).toBe(EINS_EINS)
    // the reader's own scrolling hands the outline back to the reading line
    act(() => {
      scroller.dispatchEvent(new Event('keydown'))
    })
    expect(hook.result.current.active).toBe(ZWEI)
  })

  it('lets a jump to the passage replace the entry chosen before', () => {
    const { scroller, hook } = setup()
    const passage = document.createElement('p')
    scroller.insertBefore(passage, scroller.children[1])

    act(() => hook.result.current.goTo(DREI))
    expect(hook.result.current.active).toBe(DREI)
    act(() => hook.result.current.follow(passage))
    expect(hook.result.current.active).toBe(EINS)
  })

  it('marks the passage followed before the outline was read once it is', () => {
    const { scroller, headings } = scrollingDocument([0, 500, 900, 1800])
    const passage = document.createElement('p')
    scroller.insertBefore(passage, headings[3])
    const hook = renderHook(
      ({ nodes }: { nodes: HeadingNode[] }) =>
        useHeadingNavigation(scroller, nodes, () => headings),
      { initialProps: { nodes: [] as HeadingNode[] } },
    )
    act(() => hook.result.current.follow(passage))
    expect(hook.result.current.active).toBeNull()

    hook.rerender({ nodes: NODES })
    expect(hook.result.current.active).toBe(ZWEI)
  })

  it('drops a fixed entry whose target a new rendering replaced', () => {
    const { scroller, headings, hook } = setup()
    act(() => hook.result.current.goTo(DREI))
    headings[3].remove()
    scrollTo(scroller, 0)
    expect(hook.result.current.active).toBe(EINS)
  })

  it('has no active entry without headings', () => {
    const { scroller } = scrollingDocument([])
    const hook = renderHook(() => useHeadingNavigation(scroller, [], () => []))
    expect(hook.result.current.active).toBeNull()
  })
})
