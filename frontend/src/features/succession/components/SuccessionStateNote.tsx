import Typography from '@mui/material/Typography'
import type { SuccessionStateResponse } from '@/shared/api/types'

/**
 * The marking on the object (ADR-0036, Entscheidung 6; staff council Z5): **state and addressee,
 * nothing else** — no date, no previous owner, no reason. It is shown to everyone with
 * read access, never on search hits or source references: the state concerns the
 * responsibility, not the correctness of the content.
 */
export default function SuccessionStateNote({
  succession,
  variant = 'line',
}: {
  succession: SuccessionStateResponse | null | undefined
  /** `badge` for a tile's short line, `line` for the detail view with an additional sentence. */
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
