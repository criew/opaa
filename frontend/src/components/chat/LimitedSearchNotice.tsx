import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import { getSearchAvailability } from '../../services/searchApi'

/**
 * Hinweis im Chat, solange die Vektorsuche ausgesetzt ist: Nach einem Wechsel des
 * Embedding-Modells antwortet bis zum Abschluss der Neueinbettung nur die Volltextsuche. Ein
 * Fehler beim Abfragen zeigt nichts an - der Hinweis ist eine Zusatzinformation, keine
 * Voraussetzung für den Chat.
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
