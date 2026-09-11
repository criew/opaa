import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Collapse from '@mui/material/Collapse'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { ChatNoteItem } from '../../types/api'
import { isConversationNoteVisible } from './conversationNoteVisibility'

interface ConversationNoteProps {
  /** The chat's note points, oldest first. */
  items: ChatNoteItem[]
  /** Rounds of this chat that have an answer - a round in flight does not count yet. */
  completedRounds: number
  /** False in an archived space: the points stay visible, the remove buttons do not. */
  canRemove: boolean
  onRemove: (itemId: string) => void
  /**
   * Where focus goes once the last point was removed: the button it sat on is gone by then, since
   * an empty note has no surface. Without it, removing the last point would drop focus to the
   * document body.
   */
  emptyFocusRef?: RefObject<HTMLElement | null>
}

/** Names the number of remaining points, so two removals in a row never produce the same text - an
 * aria-live region with unchanged content is not announced a second time. */
function removalAnnouncement(remaining: number): string {
  if (remaining === 0) return 'Notizpunkt entfernt. Die Gesprächsnotiz ist jetzt leer.'
  if (remaining === 1) return 'Notizpunkt entfernt. Noch 1 Punkt.'
  return `Notizpunkt entfernt. Noch ${remaining} Punkte.`
}

/**
 * The Gesprächsnotiz in the chat's header (#1488): a button carrying the number of points and a
 * collapsible panel listing them, each removable on the spot. The kind of a point (RAHMEN /
 * ANTWORTFORM) is an internal distinction and deliberately not shown; there is no editing, no
 * adding and no "remove all" (docs/features/conversation-memory.md, "Oberfläche").
 *
 * Expanding is session state only - a reload starts collapsed again.
 */
export default function ConversationNote({
  items,
  completedRounds,
  canRemove,
  onRemove,
  emptyFocusRef,
}: ConversationNoteProps) {
  // Never reset by a removal: removal is optimistic, and a failed one puts the point - and with it
  // the panel the person had open - back exactly as it was. While the note is empty, `visible` is
  // false and nothing is rendered anyway.
  const [open, setOpen] = useState(false)
  // The point removed last and the number that remained - the count is captured at the click, not
  // derived from the current list, so a point condensed later does not re-trigger the announcement.
  const [lastRemoval, setLastRemoval] = useState<{ id: string; remaining: number } | null>(null)
  const panelId = useId()
  const toggleRef = useRef<HTMLButtonElement>(null)
  const removeButtonRefs = useRef(new Map<string, HTMLButtonElement | null>())
  // Index of the point just removed - read once the shortened list has rendered, to put focus on
  // whatever now sits at that position.
  const pendingFocusIndexRef = useRef<number | null>(null)

  const visible = isConversationNoteVisible(items, completedRounds)
  // The store puts a point back when its DELETE fails; the announcement of its removal must not
  // stand next to the error message. Derived, not stored, so no state has to be reset for it.
  const rolledBack = lastRemoval !== null && items.some((item) => item.id === lastRemoval.id)
  const announcement =
    lastRemoval === null || rolledBack ? '' : removalAnnouncement(lastRemoval.remaining)

  useEffect(() => {
    const index = pendingFocusIndexRef.current
    if (index === null) return
    pendingFocusIndexRef.current = null
    if (items.length === 0) {
      emptyFocusRef?.current?.focus()
      return
    }
    const next = items[Math.min(index, items.length - 1)]
    removeButtonRefs.current.get(next.id)?.focus()
  }, [items, emptyFocusRef])

  // Escape closes the panel from anywhere in the note. The handler sits on both the button and the
  // panel because the two are siblings in the chat header's flex row, not nested.
  function handleEscape(event: { key: string }) {
    if (event.key !== 'Escape' || !open) return
    setOpen(false)
    toggleRef.current?.focus()
  }

  return (
    <>
      {/* Kept mounted even while the note has no surface: a live region only announces changes to
          content it already had when the change happened. */}
      <Box role="status" aria-live="polite" sx={visuallyHidden}>
        {announcement}
      </Box>
      {visible && (
        <>
          <Button
            ref={toggleRef}
            size="small"
            variant="outlined"
            onClick={() => {
              setLastRemoval(null)
              setOpen((previous) => !previous)
            }}
            onKeyDown={handleEscape}
            aria-expanded={open}
            // Only while the panel exists: Collapse unmounts it, and a reference to an absent id
            // is worse than none.
            aria-controls={open ? panelId : undefined}
            sx={{ flexShrink: 0 }}
          >
            {`Gesprächsnotiz · ${items.length}`}
          </Button>
          {/* flexBasis 100%: the panel is a further item of the header's flex row and wraps onto
              its own full-width line below title and button. */}
          <Collapse in={open} unmountOnExit sx={{ flexBasis: '100%', width: '100%', minWidth: 0 }}>
            <Box
              id={panelId}
              role="region"
              aria-label="Gesprächsnotiz"
              onKeyDown={handleEscape}
              sx={{ pt: 2, pb: 0.5 }}
            >
              <Typography component="h2" sx={{ fontSize: 15, fontWeight: 600 }}>
                Gesprächsnotiz
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                Diese Angaben hat OPAA aus Ihren Nachrichten in diesem Chat festgehalten. Sie
                fließen in die nächsten Antworten ein.
              </Typography>
              <Box component="ul" sx={{ m: 0, mt: 1.5, pl: 3 }}>
                {items.map((item, index) => (
                  <Box
                    component="li"
                    key={item.id}
                    sx={{
                      display: 'flex',
                      alignItems: 'flex-start',
                      gap: 1,
                      py: 0.25,
                      minWidth: 0,
                    }}
                  >
                    {/* Wraps rather than truncates: the 200-character limit is already enforced
                        when the point is condensed. */}
                    <Typography
                      variant="body2"
                      sx={{ flexGrow: 1, minWidth: 0, overflowWrap: 'anywhere' }}
                    >
                      {item.text}
                    </Typography>
                    {canRemove && (
                      <IconButton
                        size="small"
                        aria-label="Notizpunkt entfernen"
                        ref={(element) => {
                          removeButtonRefs.current.set(item.id, element)
                        }}
                        onClick={() => {
                          pendingFocusIndexRef.current = index
                          setLastRemoval({ id: item.id, remaining: items.length - 1 })
                          onRemove(item.id)
                        }}
                        sx={{ flexShrink: 0 }}
                      >
                        <CloseIcon fontSize="small" />
                      </IconButton>
                    )}
                  </Box>
                ))}
              </Box>
            </Box>
          </Collapse>
        </>
      )}
    </>
  )
}
