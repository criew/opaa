import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import useFullScreenDialog from '@/shared/ui/dialog/useFullScreenDialog'
import {
  docxHeadingElements,
  docxHeadingLevel,
  flattenHeadings,
  headingTitle,
  headingTree,
  markdownHeadingElements,
  markdownHeadingLevel,
  type HeadingNode,
} from './headingOutline'

/** The reading line sits this far down the view: a heading above it opens the section on screen. */
const READING_LINE = 0.25
/** A heading jumped to stands this far below the top edge of the view. */
const JUMP_MARGIN_PX = 16
/** What the reader does to move the view; any of it ends the entry fixed by a click. */
const USER_SCROLL_EVENTS = ['wheel', 'touchmove', 'keydown', 'pointerdown'] as const

/**
 * Keeps an outline of headings in step with the view scrolling in `scroller`: the active entry is
 * the last heading at or above the reading line. A click on an entry scrolls its heading to the
 * top - vertically only, the horizontal position stays - and fixes that entry as the active one
 * until the reader moves the view, so it is marked even where the heading cannot reach the line
 * (the last, short sections). `headings` returns the heading elements, indexed by
 * {@link HeadingNode.index}; it is asked again each time, as a re-render may replace them.
 */
export function useHeadingNavigation(
  scroller: HTMLElement | null,
  nodes: HeadingNode[],
  headings: () => HTMLElement[],
) {
  const entries = useMemo(() => flattenHeadings(nodes), [nodes])
  const [active, setActive] = useState<HeadingNode | null>(null)
  const pinned = useRef<HeadingNode | null>(null)
  const headingsRef = useRef(headings)
  useEffect(() => {
    headingsRef.current = headings
  }, [headings])

  useEffect(() => {
    if (!scroller || entries.length === 0) return
    let frame = 0
    const update = () => {
      frame = 0
      if (pinned.current) {
        setActive(pinned.current)
        return
      }
      const elements = headingsRef.current()
      const line = scroller.getBoundingClientRect().top + scroller.clientHeight * READING_LINE
      let current: HeadingNode | null = null
      for (const entry of entries) {
        const element = elements[entry.index]
        if (element && element.getBoundingClientRect().top <= line) current = entry
      }
      setActive(current)
    }
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(update)
    }
    const release = () => {
      if (!pinned.current) return
      pinned.current = null
      schedule()
    }
    scroller.addEventListener('scroll', schedule, { passive: true })
    for (const type of USER_SCROLL_EVENTS) {
      scroller.addEventListener(type, release, { passive: true })
    }
    update()
    return () => {
      if (frame) cancelAnimationFrame(frame)
      scroller.removeEventListener('scroll', schedule)
      for (const type of USER_SCROLL_EVENTS) scroller.removeEventListener(type, release)
    }
  }, [scroller, entries])

  const goTo = useCallback(
    (node: HeadingNode) => {
      const element = headingsRef.current()[node.index]
      if (!scroller || !element) return
      const top =
        element.getBoundingClientRect().top -
        scroller.getBoundingClientRect().top +
        scroller.scrollTop -
        JUMP_MARGIN_PX
      scroller.scrollTo?.({ top: Math.max(0, top) })
      pinned.current = node
      setActive(node)
      // focus follows, so keyboard and screen reader continue in the section
      if (!element.hasAttribute('tabindex')) element.setAttribute('tabindex', '-1')
      element.focus({ preventScroll: true })
    },
    [scroller],
  )

  return { active: active && entries.includes(active) ? active : null, goTo }
}

/** How a view renders its document, and so where its headings are. */
export type HeadingSource = 'docx' | 'markdown'

/**
 * The headings of a document rendered into `body`, read once it is in the DOM and again for each
 * new `version` (the document shown): the outline's entries and a lookup of their elements.
 */
export function useRenderedHeadings(
  body: HTMLElement | null,
  source: HeadingSource | null,
  version: unknown,
) {
  const headings = useCallback(
    () =>
      !body || !source
        ? []
        : source === 'docx'
          ? docxHeadingElements(body)
          : markdownHeadingElements(body),
    [body, source],
  )
  const [read, setRead] = useState<{ version: unknown; nodes: HeadingNode[] }>({
    version: null,
    nodes: [],
  })
  // read a frame after the commit, once the rendered document is in the DOM
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      const found = headings().map((element) => ({
        level: source === 'docx' ? (docxHeadingLevel(element) ?? 1) : markdownHeadingLevel(element),
        title: headingTitle(element),
      }))
      setRead({ version, nodes: headingTree(found) })
    })
    return () => cancelAnimationFrame(frame)
  }, [headings, source, version])
  return { nodes: read.version === version ? read.nodes : NO_HEADINGS, headings }
}

const NO_HEADINGS: HeadingNode[] = []

/**
 * Whether a view's outline shows: open from a medium-wide screen on unless toggled; on a phone it
 * lives in the unfolding finding bar, always there and without a toggle of its own. A document
 * without headings has none.
 */
export function useOutlineOpen(hasOutline: boolean) {
  const theme = useTheme()
  const wide = useMediaQuery(theme.breakpoints.up('md'))
  const phone = useFullScreenDialog()
  const [toggled, setToggled] = useState<boolean | null>(null)
  const open = hasOutline && (phone || (toggled ?? wide))
  return {
    open,
    /** Whether the toolbar shows the toggle: with an outline, and not on a phone. */
    toggleable: hasOutline && !phone,
    toggle: () => setToggled(!open),
  }
}
