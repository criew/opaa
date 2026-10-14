import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import { getSearchAvailability } from '../api/searchApi'

/**
 * Hint in the chat while the vector search is suspended: after a change of the
 * embedding model only the full-text search answers until the re-embedding completes. An
 * error while querying shows nothing - the hint is additional information, not a
 * prerequisite for the chat.
 */
export default function LimitedSearchNotice() {
  const [suspended, setSuspended] = useState(false)

  useEffect(() => {
    let active = true
    getSearchAvailability()
      .then((availability) => {
        if (active) setSuspended(availability.vectorSearch === 'SUSPENDED')
      })
      .catch(() => {
        if (active) setSuspended(false)
      })
    return () => {
      active = false
    }
  }, [])

  if (!suspended) return null
  return (
    <Alert severity="info" sx={{ mx: 2, mb: 1 }}>
      Eingeschränkte Suche: Der Suchindex wird gerade auf ein neues Embedding-Modell umgestellt. Bis
      dahin findet die Suche nur Abschnitte, die die Begriffe der Frage enthalten; sinngemäß
      passende Abschnitte können fehlen.
    </Alert>
  )
}
