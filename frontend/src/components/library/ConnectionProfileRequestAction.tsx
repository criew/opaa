import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { ConnectionProfileRequestResponse, SourceTypeDescriptor } from '../../types/api'
import {
  listMyConnectionProfileRequests,
  submitConnectionProfileRequest,
} from '../../services/connectionProfileApi'
import { notify } from '../../stores/notificationStore'
import { requestStateLabel } from '../admin/connections/connectionProfileLabels'

const MAX_REASON = 500

function addressHint(descriptor: SourceTypeDescriptor): string {
  const schemes = descriptor.serverAddress.schemes.map((scheme) => `${scheme}://`).join(' oder ')
  return `Beginnt mit ${schemes}. OPAA ruft die Adresse nicht auf; die Systemverwaltung prüft sie.`
}

function requestOutcome(request: ConnectionProfileRequestResponse): string | null {
  if (request.profile) return `Zugang „${request.profile.name}“`
  return null
}

interface ConnectionProfileRequestActionProps {
  descriptor: SourceTypeDescriptor
  idPrefix: string
  /** The sentence before the action; by default it asks whether no profile fits. */
  prompt?: string
}

/**
 * „Zugang vorschlagen“: asks the system administration for a profile of this source type with a
 * server address and an optional reason, and lists the person's own requests for the type with
 * what became of them. Every entered text is rendered as text only.
 */
export default function ConnectionProfileRequestAction({
  descriptor,
  idPrefix,
  prompt = 'Kein passender Zugang dabei? Zugänge legt die Systemverwaltung an.',
}: ConnectionProfileRequestActionProps) {
  const [open, setOpen] = useState(false)
  const [serverUrl, setServerUrl] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [mine, setMine] = useState<ConnectionProfileRequestResponse[]>([])
  const [reloadKey, setReloadKey] = useState(0)
  const fixedAddress = descriptor.serverAddress.fixed ?? null

  useEffect(() => {
    let cancelled = false
    void listMyConnectionProfileRequests()
      .then((loaded) => {
        if (!cancelled) setMine(loaded)
      })
      .catch(() => {
        // The own list is a convenience beside the action; without it the action still works.
        if (!cancelled) setMine([])
      })
    return () => {
      cancelled = true
    }
  }, [reloadKey])

  const ofType = mine.filter((request) => request.sourceType === descriptor.type)

  function close() {
    if (submitting) return
    setOpen(false)
    setError(null)
  }

  async function handleSubmit() {
    setSubmitting(true)
    setError(null)
    try {
      const { created } = await submitConnectionProfileRequest({
        sourceType: descriptor.type,
        serverUrl: fixedAddress ?? serverUrl.trim(),
        reason: reason.trim() === '' ? null : reason.trim(),
      })
      notify(
        created
          ? 'Ihr Zugangswunsch ist bei der Systemverwaltung eingegangen. Sie legt Zugänge an und gibt sie frei; über das Ergebnis werden Sie benachrichtigt.'
          : 'Diesen Zugangswunsch haben Sie bereits gestellt; er liegt der Systemverwaltung vor.',
        'success',
      )
      setOpen(false)
      setServerUrl('')
      setReason('')
      setReloadKey((key) => key + 1)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Der Zugangswunsch konnte nicht gesendet werden.',
      )
    } finally {
      setSubmitting(false)
    }
  }

  const titleId = `${idPrefix}-request-title`
  const complete = fixedAddress !== null || serverUrl.trim() !== ''

  return (
    <Box sx={{ mt: 1.5 }} data-testid={`${idPrefix}-connection-request`}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>{prompt}</Typography>
        <Button
          size="small"
          onClick={() => setOpen(true)}
          aria-label={`Zugang für ${descriptor.displayName} vorschlagen`}
        >
          Zugang vorschlagen
        </Button>
      </Stack>
      {ofType.length > 0 && (
        <Box component="ul" sx={{ m: 0, mt: 1, pl: 2.5 }} aria-label="Ihre Zugangswünsche">
          {ofType.map((request) => {
            const state = requestStateLabel(request.state)
            const outcome = requestOutcome(request)
            return (
              <Box component="li" key={request.id} sx={{ fontSize: 12.5, mb: 0.5 }}>
                <Box component="span" sx={{ wordBreak: 'break-all' }}>
                  {request.serverUrl}
                </Box>{' '}
                <Chip size="small" color={state.color} label={state.label} />
                {outcome && <> · {outcome}</>}
                {request.answer && (
                  <Typography component="span" sx={{ fontSize: 12.5, display: 'block' }}>
                    Antwort der Systemverwaltung: {request.answer}
                  </Typography>
                )}
              </Box>
            )
          })}
        </Box>
      )}

      <Dialog open={open} onClose={close} maxWidth="sm" fullWidth aria-labelledby={titleId}>
        <DialogTitle id={titleId}>Zugang vorschlagen</DialogTitle>
        <DialogContent>
          {error && (
            <Alert severity="error" sx={{ mb: 2 }} data-testid={`${idPrefix}-request-error`}>
              {error}
            </Alert>
          )}
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Typography variant="body2">
              Quellart: <strong>{descriptor.displayName}</strong>. Die Systemverwaltung erhält Ihren
              Vorschlag und entscheidet, ob sie einen Zugang anlegt und für Sie freigibt.
            </Typography>
            {fixedAddress === null ? (
              <TextField
                label="Server-Adresse"
                required
                size="small"
                value={serverUrl}
                onChange={(e) => setServerUrl(e.target.value)}
                helperText={addressHint(descriptor)}
              />
            ) : (
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                Die Quellart hat eine feste Adresse: {fixedAddress}
              </Typography>
            )}
            <TextField
              label="Begründung"
              size="small"
              multiline
              minRows={2}
              value={reason}
              onChange={(e) => setReason(e.target.value.slice(0, MAX_REASON))}
              helperText={`Optional, höchstens ${MAX_REASON} Zeichen – wofür Sie den Zugang brauchen.`}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={close} disabled={submitting}>
            Abbrechen
          </Button>
          <Button
            variant="contained"
            onClick={() => void handleSubmit()}
            disabled={!complete || submitting}
          >
            Vorschlagen
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
