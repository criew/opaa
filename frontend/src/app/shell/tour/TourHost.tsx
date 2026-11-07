import { useEffect } from 'react'
import { useLocation } from 'react-router'
import { currentUiLabVariant } from '@/shared/uiLabVariant'
import { registerEmptyChatGreeting } from '@/shared/ui/emptyChatGreeting'
import CoachmarkTour from './CoachmarkTour'
import EmptyChatOnboarding from './EmptyChatOnboarding'
import WelcomeDialog from './WelcomeDialog'
import { useTourStore } from './tourStore'

registerEmptyChatGreeting((greeting) => <EmptyChatOnboarding greeting={greeting} />)

/** Whether the person sits in an empty new chat, where every variant of the tour begins. */
const isNewChat = (pathname: string) => /\/chats\/new$/.test(pathname)

/**
 * Mounts the tour of the active UI-lab variant (#2312). A and B open on their own at the first
 * visit of a new chat; C and D live in the empty chat itself. Without a variant nothing renders.
 */
export default function TourHost() {
  const variant = currentUiLabVariant()
  const { pathname } = useLocation()
  const seen = useTourStore((s) => s.seen)
  const open = useTourStore((s) => s.open)
  const start = useTourStore((s) => s.start)

  useEffect(() => {
    if ((variant === 'dialog' || variant === 'coach') && !seen && !open && isNewChat(pathname)) {
      // After the chat page has laid out, so the coachmarks find their elements.
      const timer = window.setTimeout(start, 400)
      return () => window.clearTimeout(timer)
    }
  }, [variant, seen, open, pathname, start])

  if (variant === 'dialog') return <WelcomeDialog />
  if (variant === 'coach' || variant === 'invite') return <CoachmarkTour />
  return null
}
