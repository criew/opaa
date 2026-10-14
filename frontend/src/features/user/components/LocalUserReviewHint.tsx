import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { LocalUserSummaryResponse } from '@/shared/api/types'
import HintLink from '@/shared/ui/list/HintLink'

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
 * The hint about the requirement (ADR-0033 Entscheidung 11) as a link in the head row of the
 * account list: the numbers are in the link briefly, the requirement and jumps into the matching
 * filters in the popover behind it – „die Prüfung der Auflage ist ein Vorgang, kein Zähler".
 * Without accounts without expiry date and without an open invitation nothing appears; the
 * requirement sentence is then not shown as a reminder without cause.
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
      {/* Visibly short, so that every row stays on one line; the name says where it leads. */}
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
