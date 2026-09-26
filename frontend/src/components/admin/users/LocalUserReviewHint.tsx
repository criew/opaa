import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { LocalUserSummaryResponse } from '../../../types/api'
import HintLink from '../list/HintLink'

export const REVIEW_OBLIGATION_TEXT =
  'Lokale Konten sind begründet und befristet zu führen und regelmäßig zu überprüfen; ' +
  'die Systemverwaltung erhält dazu einmal im Quartal eine Wiedervorlage.'

interface LocalUserReviewHintProps {
  summary: LocalUserSummaryResponse | null
  /** Jumps the list to „ohne Ablaufdatum". */
  onShowWithoutExpiry: () => void
  /** Jumps the list to the open invitations (state „Eingeladen"). */
  onShowInvited: () => void
}

function withoutExpiryText(count: number): string {
  return count === 1
    ? '1 lokales Konto ohne Ablaufdatum'
    : `${count} lokale Konten ohne Ablaufdatum`
}

function invitedText(count: number): string {
  return count === 1 ? '1 offene Einladung' : `${count} offene Einladungen`
}

/**
 * Der Hinweis zur Auflage (#1541, ADR-0033 Entscheidung 11) als Link in der Kopfzeile der
 * Kontenliste: Die Zahlen stehen knapp im Link, Auflage und Sprünge in die zugehörigen Filter im
 * Popover dahinter – „die Prüfung der Auflage ist ein Vorgang, kein Zähler". Ohne Konten ohne
 * Ablaufdatum und ohne offene Einladung erscheint nichts; der Auflagensatz steht dann nicht als
 * Mahnung ohne Anlass.
 */
export default function LocalUserReviewHint({
  summary,
  onShowWithoutExpiry,
  onShowInvited,
}: LocalUserReviewHintProps) {
  if (!summary) return null
  const withoutExpiry = summary.withoutExpiry
  const invited = summary.invitedPending
  if (withoutExpiry === 0 && invited === 0) return null

  const parts: string[] = []
  if (withoutExpiry > 0) parts.push(withoutExpiryText(withoutExpiry))
  if (invited > 0) parts.push(invitedText(invited))

  return (
    <HintLink text={parts.join(' · ')} title="Hinweise zur Kontenprüfung">
      {(close) => (
        <>
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1.5 }}>
            {REVIEW_OBLIGATION_TEXT}
            {summary.lastReviewHint ? ` ${summary.lastReviewHint}` : ''}
          </Typography>
          <Stack spacing={1}>
            {withoutExpiry > 0 && (
              <HintRow
                text={withoutExpiryText(withoutExpiry)}
                actionLabel="Konten ohne Ablaufdatum anzeigen"
                onAction={() => {
                  close()
                  onShowWithoutExpiry()
                }}
              />
            )}
            {invited > 0 && (
              <HintRow
                text={invitedText(invited)}
                actionLabel="Offene Einladungen anzeigen"
                onAction={() => {
                  close()
                  onShowInvited()
                }}
              />
            )}
          </Stack>
        </>
      )}
    </HintLink>
  )
}

function HintRow({
  text,
  actionLabel,
  onAction,
}: {
  text: string
  actionLabel: string
  onAction: () => void
}) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1.5 }}>
      <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>{text}</Typography>
      {/* Sichtbar knapp, damit jede Zeile einzeilig bleibt; der Name sagt, wohin es geht. */}
      <Button
        size="small"
        variant="outlined"
        onClick={onAction}
        aria-label={actionLabel}
        sx={{ flexShrink: 0 }}
      >
        Anzeigen
      </Button>
    </Box>
  )
}
