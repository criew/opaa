import { useEffect, useState } from 'react'
import { Link as RouterLink } from 'react-router'
import Alert from '@mui/material/Alert'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import type { SearchAvailabilityResponse } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import { getSearchAvailability } from '../api/searchApi'

/** Where a system administrator sets up the missing model. */
const CHAT_MODELS_ROUTE = '/admin/models/chat'
const EMBEDDING_MODEL_ROUTE = '/admin/models/embedding'

/**
 * Hints in the chat about what limits the answers right now: no chat model yet - no question is
 * answered -, no embedding model yet - nothing is indexed or found -, or a vector search suspended
 * while the index moves to a new embedding model. System administrators get the way into the model
 * administration. An error while querying shows nothing - the hints are additional information,
 * not a prerequisite for the chat; a field the server leaves out counts as set up.
 */
export default function SearchAvailabilityNotice() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const [availability, setAvailability] = useState<SearchAvailabilityResponse | null>(null)

  useEffect(() => {
    let active = true
    getSearchAvailability()
      .then((answer) => {
        if (active) setAvailability(answer)
      })
      .catch(() => {
        if (active) setAvailability(null)
      })
    return () => {
      active = false
    }
  }, [])

  if (!availability) return null
  const chatMissing = availability.chatModelConfigured === false
  const embeddingMissing = availability.embeddingModelConfigured === false
  const suspended = !embeddingMissing && availability.vectorSearch === 'SUSPENDED'
  if (!chatMissing && !embeddingMissing && !suspended) return null

  return (
    <Stack spacing={1} sx={{ mx: 2, mb: 1 }}>
      {chatMissing && (
        <Alert severity="warning">
          Noch kein Chat-Modell eingerichtet: Fragen werden erst beantwortet, wenn die
          Systemverwaltung ein Chat-Modell anlegt und aktiviert.
          {isSystemAdmin && (
            <>
              {' '}
              <Link component={RouterLink} to={CHAT_MODELS_ROUTE}>
                Chat-Modell einrichten
              </Link>
            </>
          )}
        </Alert>
      )}
      {embeddingMissing && (
        <Alert severity="warning">
          Noch kein Embedding-Modell eingerichtet: Es gibt keinen Suchindex, Dokumente lassen sich
          weder indexieren noch durchsuchen.
          {isSystemAdmin && (
            <>
              {' '}
              <Link component={RouterLink} to={EMBEDDING_MODEL_ROUTE}>
                Embedding-Modell festlegen
              </Link>
            </>
          )}
        </Alert>
      )}
      {suspended && (
        <Alert severity="info">
          Eingeschränkte Suche: Der Suchindex wird gerade auf ein neues Embedding-Modell umgestellt.
          Bis dahin findet die Suche nur Abschnitte, die die Begriffe der Frage enthalten; sinngemäß
          passende Abschnitte können fehlen.
        </Alert>
      )}
    </Stack>
  )
}
