import { useEffect, useId, useRef } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { ChatSearch } from '../../hooks/useChatSearch'
import type { ChatSearchHit } from '../../types/api'
import { chatTitle } from './chatListGroups'
import HighlightedExcerpt from './HighlightedExcerpt'

const dateTimeFormat = new Intl.DateTimeFormat('de-DE', { dateStyle: 'medium', timeStyle: 'short' })

/** The chat at the hit's message; a hit in the title alone opens the chat as usual. */
function chatSearchHitPath(spaceId: string, hit: ChatSearchHit): string {
  const chatPath = `/spaces/${spaceId}/chats/${hit.chatId}`
  return hit.messageId ? `${chatPath}?message=${encodeURIComponent(hit.messageId)}` : chatPath
}

function placeOf(hit: ChatSearchHit): string {
  if (hit.role === 'USER') return 'Frage'
  if (hit.role === 'ASSISTANT') return 'Antwort'
  return 'Titel'
}

function statusText(hits: number, hasMore: boolean): string {
  if (hits === 0) return 'Keine Treffer'
  const found = hits === 1 ? '1 Chat gefunden' : `${hits} Chats gefunden`
  return hasMore ? `${found}, weitere vorhanden` : found
}

interface ChatSearchResultsProps {
  spaceId: string
  search: ChatSearch
  onOpen: (path: string) => void
}

/**
 * The hits of the chat search (docs/features/chat-list.md, "Chatsuche"): one per chat with title,
 * excerpt, place, date and the chat archive mark, paged by "Weitere laden".
 */
export default function ChatSearchResults({ spaceId, search, onOpen }: ChatSearchResultsProps) {
  const idPrefix = useId()
  // After "Weitere laden" the focus moves to the first hit the page added.
  const focusIndexRef = useRef<number | null>(null)
  const { result, isLoading, isLoadingMore } = search
  const hitCount = result?.hits.length ?? 0

  useEffect(() => {
    const index = focusIndexRef.current
    if (index === null || index >= hitCount) return
    focusIndexRef.current = null
    document.getElementById(`${idPrefix}-hit-${index}`)?.focus()
  }, [hitCount, idPrefix])

  if (isLoading) {
    return (
      <Box sx={{ py: 3, display: 'flex', justifyContent: 'center' }}>
        <CircularProgress size={24} aria-label="Suche läuft" />
      </Box>
    )
  }
  if (!result) return null

  return (
    <Box>
      <Box role="status" sx={visuallyHidden}>
        {result.error ? '' : statusText(hitCount, result.hasMore)}
      </Box>
      {hitCount === 0 && !result.error && (
        <Box sx={{ py: 1 }}>
          <Typography>Kein Chat enthält „{result.term}“.</Typography>
          <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.5 }}>
            Durchsucht werden nur Ihre eigenen Chats in diesem Space – Titel, Fragen und Antworten,
            auch im Chat-Archiv.
          </Typography>
        </Box>
      )}
      {hitCount > 0 && (
        <Box
          component="ul"
          role="list"
          aria-label="Suchtreffer"
          sx={{ listStyle: 'none', m: 0, p: 0 }}
        >
          {result.hits.map((hit, index) => {
            const path = chatSearchHitPath(spaceId, hit)
            return (
              <Box
                component="li"
                key={hit.chatId}
                sx={{ py: 1.5, borderBottom: 1, borderColor: 'divider' }}
              >
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                  <Link
                    id={`${idPrefix}-hit-${index}`}
                    href={path}
                    onClick={(event) => {
                      event.preventDefault()
                      onOpen(path)
                    }}
                    underline="hover"
                    sx={{ fontWeight: 600 }}
                  >
                    {chatTitle(hit)}
                  </Link>
                  {hit.archivedAt && (
                    // The person's chat archive, not an archived space.
                    <Chip size="small" variant="outlined" label="Archiviert" />
                  )}
                </Stack>
                <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.25 }}>
                  {placeOf(hit)}
                  {hit.messageCreatedAt &&
                    ` · ${dateTimeFormat.format(new Date(hit.messageCreatedAt))}`}
                </Typography>
                <Typography variant="body2" sx={{ mt: 0.5, overflowWrap: 'anywhere' }}>
                  <HighlightedExcerpt text={hit.excerpt} highlights={hit.highlights} />
                </Typography>
              </Box>
            )
          })}
        </Box>
      )}
      {result.error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {result.error}
        </Alert>
      )}
      {result.hasMore && (
        <Button
          variant="outlined"
          size="small"
          sx={{ mt: 2 }}
          disabled={isLoadingMore}
          onClick={() => {
            focusIndexRef.current = hitCount
            search.loadMore()
          }}
        >
          Weitere laden
        </Button>
      )}
    </Box>
  )
}
