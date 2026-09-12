import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { LocalUserSummaryResponse } from '../../../types/api'

export const REVIEW_OBLIGATION_TEXT =
  'Lokale Konten sind begründet und befristet zu führen und regelmäßig zu überprüfen; ' +
  'die Systemverwaltung erhält dazu einmal im Quartal eine Wiedervorlage.'

interface LocalUserReviewNoticeProps {
  summary: LocalUserSummaryResponse | null
  /** Jumps the list to „ohne Ablaufdatum". */
  onShowWithoutExpiry: () => void
  /** Jumps the list to the open invitations (state „Eingeladen"). */
  onShowInvited: () => void
}

/**
 * Der stehende Hinweis zur Auflage (#1541, ADR-0033 Entscheidung 11): „Die Prüfung der Auflage ist
 * ein Vorgang, kein Zähler" – deshalb führt jede Zahl über eine Schaltfläche in den zugehörigen
 * Filter der Liste und nicht nur auf sich selbst. Ohne Konten ohne Ablaufdatum und ohne offene
 * Einladung verschwindet der Hinweis; der Auflagensatz steht dann nicht als Mahnung ohne Anlass.
 */
export default function LocalUserReviewNotice({
  summary,
  onShowWithoutExpiry,
  onShowInvited,
}: LocalUserReviewNoticeProps) {
  if (!summary) return null
  const withoutExpiry = summary.withoutExpiry
  const invited = summary.invitedPending
  if (withoutExpiry === 0 && invited === 0) return null

  const parts: string[] = []
  if (withoutExpiry > 0) {
    parts.push(
      withoutExpiry === 1
        ? '1 lokales Konto ohne Ablaufdatum'
        : `${withoutExpiry} lokale Konten ohne Ablaufdatum`,
    )
  }
  if (invited > 0) {
    parts.push(invited === 1 ? '1 offene Einladung' : `${invited} offene Einladungen`)
  }

  return (
    <Alert severity="warning" sx={{ mb: 2.5 }} data-testid="local-user-review-notice">
      <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>{parts.join(' · ')}</Typography>
      <Typography sx={{ fontSize: 12.5, mt: 0.25 }}>
        {REVIEW_OBLIGATION_TEXT}
        {summary.lastReviewHint ? ` ${summary.lastReviewHint}` : ''}
      </Typography>
      <Stack direction="row" spacing={1} sx={{ mt: 1, flexWrap: 'wrap' }}>
        {withoutExpiry > 0 && (
          <Button size="small" variant="outlined" onClick={onShowWithoutExpiry}>
            Konten ohne Ablaufdatum anzeigen
          </Button>
        )}
        {invited > 0 && (
          <Button size="small" variant="outlined" onClick={onShowInvited}>
            Offene Einladungen anzeigen
          </Button>
        )}
      </Stack>
    </Alert>
  )
}
