import { currentUiLabVariant } from '@/shared/uiLabVariant'
import type { ReactNode } from 'react'
import FirstSteps from './FirstSteps'
import TourInvite from './TourInvite'
import { useTourStore } from './tourStore'

/**
 * What the empty chat shows per lab variant: C puts the first steps in place of the greeting, D
 * adds the invitation to the tour below it; A and B leave the greeting alone.
 */
export default function EmptyChatOnboarding({ greeting }: { greeting: ReactNode }) {
  const variant = currentUiLabVariant()
  const seen = useTourStore((s) => s.seen)
  const open = useTourStore((s) => s.open)
  const checklistHidden = useTourStore((s) => s.checklistHidden)

  if (variant === 'list' && !checklistHidden) return <FirstSteps />
  if (variant === 'invite' && !seen && !open) {
    return (
      <>
        {greeting}
        <TourInvite />
      </>
    )
  }
  return <>{greeting}</>
}
