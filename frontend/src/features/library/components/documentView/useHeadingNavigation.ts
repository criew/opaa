import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import useFullScreenDialog from '@/shared/ui/dialog/useFullScreenDialog'
import {
  docxHeadingElements,
  docxHeadingLevel,
  flattenTree,
  headingTitle,
  headingTree,
  markdownHeadingElements,
  markdownHeadingLevel,
  type HeadingNode,
} from './headingOutline'
import { lastAtOrBefore, readingLine, useUserScroll } from './readingPosition'

/**
 * The reading line sits this far down the view (see {@link readingLine}): a heading above it opens
 * the section on screen. Half way, so a heading the reader scrolls past counts once it reaches the
 * middle.
 */
const READING_LINE = 0.5
/** A heading jumped to stands this far below the top edge of the view. */
const JUMP_MARGIN_PX = 16

/**
 * The entry whose section holds `target`: the last heading that is `target` or comes before it
 * in the document, `null` before the first heading.
 */
function sectionOf(
  target: Element,
  entries: HeadingNode[],
  elements: HTMLElement[],
): HeadingNode | null {
  let found: HeadingNode | null = null
  for (const entry of entries) {
    const element = elements[entry.index]
    if (!element) continue
    if (
      element === target ||
      element.compareDocumentPosition(target) & Node.DOCUMENT_POSITION_FOLLOWING
    ) {
      found = entry
    }
  }
  return found
}

/**
 * Keeps an outline of headings in step with the view scrolling in `scroller`: the active entry is
 * the last heading at or above the reading line. A jump fixes the active entry by what it jumped
 * to until the reader moves the view: {@link goTo} an entry (its heading scrolled to the top,
 * vertically only) marks that entry, {@link follow} a passage the view was brought to marks the
 * section holding it - so the outline names the place on screen even where the target cannot
 * reach the reading line. `headings` returns the heading elements, indexed by
 * {@link HeadingNode.index}; it is asked again each time, as a re-render may replace them.
 */
export function useHeadingNavigation(
  scroller: HTMLElement | null,
  nodes: HeadingNode[],
  headings: () => HTMLElement[],
) {
  const entries = useMemo(() => flattenTree(nodes), [nodes])
  const [active, setActive] = useState<HeadingNode | null>(null)
  // the element the last jump went to; the active entry is its section until the reader scrolls
  const pinned = useRef<Element | null>(null)
  // recomputes the active entry while the view is set up, a no-op before
  const refresh = useRef<() => void>(() => {})
  const headingsRef = useRef(headings)
  const scrollerRef = useRef<HTMLElement | null>(scroller)
  useEffect(() => {
    headingsRef.current = headings
    scrollerRef.current = scroller
  }, [headings, scroller])
  // the reader's own scrolling hands the outline back to the reading line
  const forgetInput = useUserScroll(
    scrollerRef,
    () => {
      pinned.current = null
      refresh.current()
    },
    scroller,
  )

  useEffect(() => {
    if (!scroller || entries.length === 0) return
    let frame = 0
    const update = () => {
      frame = 0
      const elements = headingsRef.current()
      // a target replaced by a new rendering no longer fixes anything
      if (pinned.current && !pinned.current.isConnected) pinned.current = null
      if (pinned.current) {
        setActive(sectionOf(pinned.current, entries, elements))
        return
      }
      const top = scroller.getBoundingClientRect().top - scroller.scrollTop
      const positioned = entries.flatMap((entry) => {
        const element = elements[entry.index]
        return element ? [{ entry, position: element.getBoundingClientRect().top - top }] : []
      })
      setActive(lastAtOrBefore(positioned, readingLine(scroller, READING_LINE))?.entry ?? null)
    }
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(update)
    }
    scroller.addEventListener('scroll', schedule, { passive: true })
    // the outline toggled, the window resized or an image loaded moves headings without a scroll
    const resized = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(schedule) : null
    resized?.observe(scroller)
    if (scroller.firstElementChild) resized?.observe(scroller.firstElementChild)
    refresh.current = schedule
    update()
    return () => {
      refresh.current = () => {}
      if (frame) cancelAnimationFrame(frame)
      resized?.disconnect()
      scroller.removeEventListener('scroll', schedule)
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
      forgetInput()
      scroller.scrollTo?.({ top: Math.max(0, top) })
      pinned.current = element
      setActive(node)
      // focus follows, so keyboard and screen reader continue in the section
      if (!element.hasAttribute('tabindex')) element.setAttribute('tabindex', '-1')
      element.focus({ preventScroll: true })
    },
    [scroller, forgetInput],
  )

  /**
   * The view was brought to `target` (the cited passage): its section is the active entry, also
   * when the outline is read only after the jump.
   */
  const follow = useCallback(
    (target: Element) => {
      forgetInput()
      pinned.current = target
      refresh.current()
    },
    [forgetInput],
  )

  return { active: active && entries.includes(active) ? active : null, goTo, follow }
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
