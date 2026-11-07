import { currentUiLabVariant } from '@/shared/uiLabVariant'
import { useTourStore } from './tourStore'

/** The profile menu's entry: its label and what it does for the active variant, or null. */
export function useTourMenuEntry(): { label: string; run: () => void } | null {
  const variant = currentUiLabVariant()
  const start = useTourStore((s) => s.start)
  const showChecklist = useTourStore((s) => s.showChecklist)
  if (variant === 'list') return { label: 'Erste Schritte anzeigen', run: showChecklist }
  if (variant === 'dialog' || variant === 'coach' || variant === 'invite') {
    return { label: 'Rundgang starten', run: start }
  }
  return null
}
