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
  const [open, setOpen] = useState(false)
  const [status, setStatus] = useState('')
  const panelId = useId()
  const toggleRef = useRef<HTMLButtonElement>(null)
  const removeButtonRefs = useRef(new Map<string, HTMLButtonElement | null>())
  // Index of the point just removed - read once the shortened list has rendered, to put focus on
  // whatever now sits at that position.
  const pendingFocusIndexRef = useRef<number | null>(null)

  const visible = isConversationNoteVisible(items, completedRounds)

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

  function handleRemove(index: number, item: ChatNoteItem) {
    const wasLast = items.length === 1
    pendingFocusIndexRef.current = index
    // Optimistic, like the removal itself: the panel goes with the last point, since an empty note
    // has no surface.
    setStatus(
      wasLast ? 'Notizpunkt entfernt. Die Gesprächsnotiz ist jetzt leer.' : 'Notizpunkt entfernt.',
    )
    if (wasLast) setOpen(false)
    onRemove(item.id)
  }

  return (
    <>
      {/* Kept mounted even while the note has no surface: a live region only announces changes to
          content it already had when the change happened. */}
      <Box role="status" aria-live="polite" sx={visuallyHidden}>
        {status}
      </Box>
      {visible && (
        <>
          <Button
            ref={toggleRef}
            size="small"
            variant="outlined"
            onClick={() => {
              setStatus('')
              setOpen((previous) => !previous)
            }}
            aria-expanded={open}
            aria-controls={panelId}
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
              onKeyDown={(event) => {
                if (event.key !== 'Escape') return
                setOpen(false)
                toggleRef.current?.focus()
              }}
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
                        onClick={() => handleRemove(index, item)}
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
