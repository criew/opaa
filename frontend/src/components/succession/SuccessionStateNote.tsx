import Typography from '@mui/material/Typography'
import type { SuccessionStateResponse } from '../../types/api'

/**
 * Die Kennzeichnung am Objekt (ADR-0036, Entscheidung 6; Personalrat Z5): **Zustand und Adressat,
 * sonst nichts** — kein Datum, kein früherer Eigentümer, kein Grund. Sie steht für jeden
 * Leseberechtigten, nie an Suchtreffern oder Quellenverweisen: Der Zustand betrifft die
 * Zuständigkeit, nicht die Richtigkeit des Inhalts.
 */
export default function SuccessionStateNote({
  succession,
  variant = 'line',
}: {
  succession: SuccessionStateResponse | null | undefined
  /** `badge` für die knappe Zeile einer Kachel, `line` für die Detailansicht mit Zusatzsatz. */
  variant?: 'badge' | 'line'
}) {
  if (!succession) return null

  const headline = `Nachfolge offen — zuständig: ${succession.addresseeLabel}`
  if (variant === 'badge') {
    return (
      <Typography sx={{ fontSize: 12, color: 'warning.main' }} data-testid="succession-note">
        {headline}
      </Typography>
    )
  }

  return (
    <Typography sx={{ fontSize: 13, mt: 0.5 }} data-testid="succession-note">
      {headline}.{' '}
      <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
        Das Objekt bleibt nutzbar, bestehende Rechte bleiben, nichts wird gelöscht. Eingefroren ist
        allein die Reichweite: keine neuen oder größeren Berechtigungen, keine größere Sichtbarkeit,
        keine neue Freigabe.
      </Typography>
    </Typography>
  )
}
