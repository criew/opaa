import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  ChatCleanupHoldPurpose,
  LocalUserChatCleanupResponse,
  LocalUserResponse,
} from '@/shared/api/types'
import { notify } from '@/shared/stores/notificationStore'
import {
  approveLocalUserChatCleanupHold,
  getLocalUserChatCleanup,
  rejectLocalUserChatCleanupHold,
  requestLocalUserChatCleanupHold,
  resumeLocalUserChatCleanup,
} from '../api/localUserApi'
import {
  CHAT_CLEANUP_HOLD_PURPOSES,
  CHAT_CLEANUP_HOLD_PURPOSE_LABEL,
  chatCleanupHoldPurposeText,
  chatCleanupText,
  formatExpiry,
  localUserErrorMessage,
  remainingDays,
} from './localUserLabels'

export const CHAT_CLEANUP_HEADING = 'Chats nach der Sperre'

/** Bounds of the reason, as the specification sets them. */
export const HOLD_REASON_MIN = 10
export const HOLD_REASON_MAX = 1000

interface LocalUserChatCleanupSectionProps {
  user: LocalUserResponse
}

const smallText = { fontSize: 13, color: 'text.secondary' } as const

/**
 * The deletion of the account's chats after a lock by the administration, in the single view of the
 * account only – never in the list. Holding it takes two administrators: one requests with a
 * purpose of the closed list and a reason, a second one approves; the hold then ends by itself
 * after the maximum duration. Lifting a hold or rejecting a request takes one.
 */
export default function LocalUserChatCleanupSection({ user }: LocalUserChatCleanupSectionProps) {
  const [state, setState] = useState<LocalUserChatCleanupResponse | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [formOpen, setFormOpen] = useState(false)
  const [purpose, setPurpose] = useState<ChatCleanupHoldPurpose | ''>('')
  const [reason, setReason] = useState('')

  useEffect(() => {
    let active = true
    getLocalUserChatCleanup(user.id)
      .then((loaded) => {
        if (active) setState(loaded)
      })
      .catch(() => {
        if (active) setFailed(true)
      })
    return () => {
      active = false
    }
  }, [user.id])

  async function act(run: () => Promise<LocalUserChatCleanupResponse>, success: string) {
    setBusy(true)
    try {
      setState(await run())
      notify(success, 'success')
      return true
    } catch (err) {
      notify(localUserErrorMessage(err, 'Die Änderung ist fehlgeschlagen.'), 'error')
      return false
    } finally {
      setBusy(false)
    }
  }

  const trimmedReason = reason.trim()
  const reasonValid =
    trimmedReason.length >= HOLD_REASON_MIN && trimmedReason.length <= HOLD_REASON_MAX
  const name = `„${user.displayName}“`

  async function submitRequest() {
    if (!purpose || !reasonValid) return
    const ok = await act(
      () => requestLocalUserChatCleanupHold(user.id, { purpose, reason: trimmedReason }),
      `Der Antrag für ${name} ist gestellt. Eine zweite Person der Systemverwaltung gibt ihn frei.`,
    )
    if (ok) {
      setFormOpen(false)
      setPurpose('')
      setReason('')
    }
  }

  const hold = state?.hold
  const pending = state?.pendingRequest

  return (
    <Box component="section" aria-labelledby="user-form-chat-cleanup-heading">
      <Typography
        id="user-form-chat-cleanup-heading"
        component="h3"
        sx={{ fontSize: 12, fontWeight: 600, mb: '5px' }}
      >
        {CHAT_CLEANUP_HEADING}
      </Typography>
      {failed && (
        <Typography sx={smallText}>Der Stand der Löschung konnte nicht geladen werden.</Typography>
      )}
      {state && (
        <Stack spacing={1}>
          <Typography sx={smallText}>{chatCleanupText(state)}</Typography>

          {hold && (
            <Box data-testid="chat-cleanup-hold">
              <Typography sx={smallText}>
                {hold.heldUntil
                  ? `Angehalten seit ${formatExpiry(hold.heldSince)} bis ${formatExpiry(hold.heldUntil)} (noch ${remainingDays(hold.heldUntil)} Tage).`
                  : `Angehalten seit ${formatExpiry(hold.heldSince)}; das Ende setzt der nächste nächtliche Lauf auf ${state.holdMaxDays} Tage.`}
              </Typography>
              <Typography sx={smallText}>
                Zweck: {chatCleanupHoldPurposeText(hold.purpose)}
              </Typography>
              {hold.reason && <Typography sx={smallText}>Begründung: {hold.reason}</Typography>}
            </Box>
          )}

          {pending && (
            <Alert severity="info" data-testid="chat-cleanup-pending">
              <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
                {pending.extension
                  ? 'Verlängerung beantragt, wartet auf Freigabe'
                  : 'Anhaltung beantragt, wartet auf Freigabe'}
              </Typography>
              <Typography sx={{ fontSize: 13 }}>
                Zweck: {CHAT_CLEANUP_HOLD_PURPOSE_LABEL[pending.purpose]}
              </Typography>
              <Typography sx={{ fontSize: 13 }}>Begründung: {pending.reason}</Typography>
              <Typography sx={{ fontSize: 13 }}>
                Beantragt am {formatExpiry(pending.requestedAt)}
                {pending.requestedByMe ? ' von Ihnen' : ''}. Ohne Antwort verfällt der Antrag nach
                14 Tagen.
              </Typography>
              {pending.requestedByMe && (
                <Typography sx={{ fontSize: 13, mt: 0.5 }}>
                  Freigeben kann nur eine zweite Person der Systemverwaltung.
                </Typography>
              )}
              <Stack direction="row" spacing={1} sx={{ mt: 1, flexWrap: 'wrap' }}>
                {!pending.requestedByMe && (
                  <Button
                    size="small"
                    variant="contained"
                    disabled={busy}
                    onClick={() =>
                      void act(
                        () => approveLocalUserChatCleanupHold(user.id),
                        pending.extension
                          ? `Die Anhaltung der Löschung der Chats von ${name} ist verlängert.`
                          : `Die Löschung der Chats von ${name} ist angehalten.`,
                      )
                    }
                  >
                    Freigeben
                  </Button>
                )}
                <Button
                  size="small"
                  variant="outlined"
                  disabled={busy}
                  onClick={() =>
                    void act(
                      () => rejectLocalUserChatCleanupHold(user.id),
                      pending.requestedByMe
                        ? 'Der Antrag ist zurückgezogen.'
                        : 'Der Antrag ist abgelehnt.',
                    )
                  }
                >
                  {pending.requestedByMe ? 'Antrag zurückziehen' : 'Ablehnen'}
                </Button>
              </Stack>
            </Alert>
          )}

          {formOpen && !pending && (
            <Box
              component="form"
              aria-labelledby="chat-cleanup-hold-form-heading"
              onSubmit={(event) => {
                event.preventDefault()
                void submitRequest()
              }}
            >
              <Typography
                id="chat-cleanup-hold-form-heading"
                component="h4"
                sx={{ fontSize: 12.5, fontWeight: 600, mb: 0.5 }}
              >
                {hold ? 'Verlängerung beantragen' : 'Anhaltung beantragen'}
              </Typography>
              <Typography sx={{ ...smallText, mb: 1 }}>
                Die Anhaltung greift erst, wenn eine zweite Person der Systemverwaltung freigibt,
                und endet nach höchstens {state.holdMaxDays} Tagen von selbst. Ein
                disziplinarischer, arbeitsrechtlicher oder leistungsbezogener Zweck ist nicht
                zulässig.
              </Typography>
              <Typography
                id="chat-cleanup-hold-purpose-label"
                component="span"
                sx={{ display: 'block', fontSize: 12.5, fontWeight: 500, mb: 0.5 }}
              >
                Zweck
              </Typography>
              <TextField
                id="chat-cleanup-hold-purpose"
                select
                fullWidth
                size="small"
                required
                slotProps={{
                  select: {
                    SelectDisplayProps: { 'aria-labelledby': 'chat-cleanup-hold-purpose-label' },
                  },
                }}
                value={purpose}
                onChange={(e) => setPurpose(e.target.value as ChatCleanupHoldPurpose)}
                disabled={busy}
              >
                {CHAT_CLEANUP_HOLD_PURPOSES.map((entry) => (
                  <MenuItem key={entry} value={entry}>
                    {CHAT_CLEANUP_HOLD_PURPOSE_LABEL[entry]}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                id="chat-cleanup-hold-reason"
                label="Begründung"
                multiline
                minRows={2}
                fullWidth
                size="small"
                required
                sx={{ mt: 1.5 }}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                disabled={busy}
                error={reason.length > 0 && !reasonValid}
                helperText={`Anlass, z. B. Aktenzeichen der Anordnung; ${HOLD_REASON_MIN} bis ${HOLD_REASON_MAX} Zeichen.`}
                slotProps={{ htmlInput: { maxLength: HOLD_REASON_MAX } }}
              />
              <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                <Button
                  type="submit"
                  size="small"
                  variant="contained"
                  disabled={busy || !purpose || !reasonValid}
                >
                  Antrag stellen
                </Button>
                <Button size="small" onClick={() => setFormOpen(false)} disabled={busy}>
                  Abbrechen
                </Button>
              </Stack>
            </Box>
          )}

          {!formOpen && (
            <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
              {!pending && (state.status !== 'SWITCHED_OFF' || hold) && (
                <Button size="small" variant="outlined" onClick={() => setFormOpen(true)}>
                  {hold ? 'Verlängerung beantragen' : 'Anhaltung beantragen'}
                </Button>
              )}
              {hold && (
                <Button
                  size="small"
                  variant="outlined"
                  disabled={busy}
                  onClick={() =>
                    void act(
                      () => resumeLocalUserChatCleanup(user.id),
                      `Die Löschung der Chats von ${name} wird fortgesetzt.`,
                    )
                  }
                >
                  Löschung fortsetzen
                </Button>
              )}
            </Stack>
          )}
        </Stack>
      )}
    </Box>
  )
}
