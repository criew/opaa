import { useEffect, useState } from 'react'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import { useLocation, useNavigate } from 'react-router'
import { markQuickTourHintDone } from '@/features/auth'
import { useAuthStore } from '@/shared/session/authStore'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { useUiStore } from '@/shared/stores/uiStore'
import { useQuickTourStore } from './quickTourStore'
import { findTourAnchor, type TourAnchorName } from '@/shared/ui/a11y/tourAnchor'
import { OPTIONAL_TOUR_ANCHORS, type TourAudience } from './tourSteps'

const NONE: ReadonlySet<TourAnchorName> = new Set()

function visibleOptionalAnchors(): ReadonlySet<TourAnchorName> {
  return new Set(
    OPTIONAL_TOUR_ANCHORS.filter(
      (name) => (findTourAnchor(name)?.getClientRects().length ?? 0) > 0,
    ),
  )
}

const sameSet = (a: ReadonlySet<TourAnchorName>, b: ReadonlySet<TourAnchorName>) =>
  a.size === b.size && [...a].every((name) => b.has(name))

/**
 * The optional anchors on screen, followed while `active` (the hint or the tour is open) - the chat
 * page may arrive after the tour started. Inactive, nothing observes the page.
 */
function useVisibleOptionalAnchors(active: boolean): ReadonlySet<TourAnchorName> {
  const [present, setPresent] = useState<ReadonlySet<TourAnchorName>>(NONE)
  useEffect(() => {
    if (!active) return
    const update = () => {
      const next = visibleOptionalAnchors()
      setPresent((previous) => (sameSet(previous, next) ? previous : next))
    }
    update()
    const observer = new MutationObserver(update)
    observer.observe(document.body, { childList: true, subtree: true })
    window.addEventListener('resize', update)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', update)
    }
  }, [active])
  return active ? present : NONE
}

/**
 * Who the tour is for: the role decides the admin station, the width the drawer variant, the
 * screen the optional stations. `active` is whether the hint or the tour is open right now.
 */
export function useTourAudience(active: boolean): TourAudience {
  const isWide = useMediaQuery(useTheme().breakpoints.up('md'))
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const present = useVisibleOptionalAnchors(active)
  return { isSystemAdmin, isWide, present }
}

/**
 * The hint shows only while the quick tour is switched on and the server says explicitly that the
 * person has not answered it yet; a missing field (an older backend) never shows it.
 */
export function useQuickTourHintVisible(): boolean {
  const enabled = useFeatureEnabled('quickTour')
  const done = useAuthStore((s) => s.user?.quickTourHintDone)
  return enabled && done === false
}

/**
 * Both answers of the hint - "Rundgang starten" and "Nicht jetzt" - set the one marker. The hint
 * closes at once; a failed call leaves it closed without a message, as during a rolling update,
 * and the server's value decides again on the next load.
 */
export function answerQuickTourHint(): void {
  useAuthStore.setState((state) =>
    state.user ? { user: { ...state.user, quickTourHintDone: true } } : {},
  )
  markQuickTourHintDone().catch(() => undefined)
}

const CHAT_PATH = /^\/spaces\/[^/]+\/chats\/[^/]+$/

/**
 * Starts the tour from the profile menu. The stations explain the chat, so a start elsewhere first
 * opens a new chat; the drawer closes so the tour points at the page, not at the menu. A hint still
 * open is answered by this start, like by its own "Rundgang starten".
 */
export function useStartQuickTour(): () => void {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const start = useQuickTourStore((s) => s.start)
  const setSidebarOpen = useUiStore((s) => s.setSidebarOpen)
  const hintOpen = useQuickTourHintVisible()
  return () => {
    if (hintOpen) answerQuickTourHint()
    setSidebarOpen(false)
    if (!CHAT_PATH.test(pathname)) navigate('/chat')
    start()
  }
}
