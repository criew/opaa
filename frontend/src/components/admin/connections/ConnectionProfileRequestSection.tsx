import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { ConnectionProfileRequestResponse } from '../../../types/api'
import {
  listConnectionProfileRequests,
  resolveConnectionProfileRequest,
} from '../../../services/connectionProfileApi'
import { notify } from '../../../stores/notificationStore'

const PAGE_SIZE = 50
const MAX_ANSWER = 500

function formatDay(iso: string) {
  return new Date(iso).toLocaleDateString('de-DE')
}

interface ConnectionProfileRequestSectionProps {
  /** Changes whenever the page reloads, e.g. after a profile was created for a request. */
  reloadKey: number
  displayNameOf: (sourceType: string) => string
  /** Opens the profile form preset with the request's type and address. */
  onCreateProfile: (request: ConnectionProfileRequestResponse) => void
}

/**
 * The open connection profile requests ("Zugangswünsche") of the organization, oldest first: each
 * with type, address, the person's reason and who asked, „Zugang anlegen“ and „Ablehnen“ with an
 * optional answer. Reason and answer are rendered as text only.
 */
export default function ConnectionProfileRequestSection({
  reloadKey,
  displayNameOf,
  onCreateProfile,
}: ConnectionProfileRequestSectionProps) {
  const [requests, setRequests] = useState<ConnectionProfileRequestResponse[]>([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [ownReload, setOwnReload] = useState(0)
  const [declining, setDeclining] = useState<ConnectionProfileRequestResponse | null>(null)
  const [answer, setAnswer] = useState('')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    let cancelled = false
    void listConnectionProfileRequests('OPEN', 0, PAGE_SIZE)
      .then((page) => {
        if (cancelled) return
        setRequests(page.items)
        setTotal(page.total)
        setError(null)
      })
      .catch((err) => {
        if (!cancelled) {
          setError(
            err instanceof Error ? err.message : 'Zugangswünsche konnten nicht geladen werden.',
          )
        }
      })
      .finally(() => {
        if (!cancelled) setLoaded(true)
      })
    return () => {
      cancelled = true
    }
  }, [reloadKey, ownReload])

  function closeDecline() {
    if (submitting) return
    setDeclining(null)
    setAnswer('')
  }

  async function handleDecline() {
    if (!declining) return
    setSubmitting(true)
    try {
      await resolveConnectionProfileRequest(declining.id, {
        state: 'DECLINED',
        answer: answer.trim() === '' ? null : answer.trim(),
      })
      notify(`Der Zugangswunsch von ${declining.requestedByName} ist abgelehnt.`, 'success')
      setDeclining(null)
      setAnswer('')
      setOwnReload((key) => key + 1)
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Ablehnen fehlgeschlagen.', 'error')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Box data-testid="connection-profile-requests">
      <Typography
        id="connection-profile-requests-heading"
        component="h2"
        sx={{ fontSize: 18, fontWeight: 600, mb: 1 }}
      >
        Zugangswünsche
      </Typography>
      <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1.5 }}>
        Wer im Wissens-Assistenten keinen passenden Zugang findet, kann einen vorschlagen; die
        Wünsche erscheinen hier. „Zugang anlegen“ übernimmt Quellart und Adresse und erledigt den
        Wunsch beim Speichern; die Freigabe des neuen Zugangs erteilen Sie wie bei jedem Zugang
        unter „Anlegerechte“.
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {!loaded ? (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Zugangswünsche werden geladen …
        </Typography>
      ) : requests.length === 0 ? (
        !error && (
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            Es liegen keine offenen Zugangswünsche vor.
          </Typography>
        )
      ) : (
        <>
          <Table size="small" aria-labelledby="connection-profile-requests-heading">
            <TableHead>
              <TableRow>
                <TableCell>Quellart</TableCell>
                <TableCell>Server-Adresse</TableCell>
                <TableCell>Begründung</TableCell>
                <TableCell>Von</TableCell>
                <TableCell align="right">Aktionen</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {requests.map((request) => (
                <TableRow key={request.id}>
                  <TableCell>{displayNameOf(request.sourceType)}</TableCell>
                  <TableCell sx={{ wordBreak: 'break-all' }}>{request.serverUrl}</TableCell>
                  <TableCell sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                    {request.reason ?? '–'}
                  </TableCell>
                  <TableCell>
                    {request.requestedByName}
                    <Typography variant="caption" component="div" sx={{ color: 'text.secondary' }}>
                      am {formatDay(request.createdAt)}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Stack
                      direction="row"
                      spacing={1}
                      sx={{ justifyContent: 'flex-end', flexWrap: 'wrap' }}
                    >
                      <Button
                        size="small"
                        onClick={() => onCreateProfile(request)}
                        aria-label={`Zugang für ${request.serverUrl} anlegen`}
                      >
                        Zugang anlegen
                      </Button>
                      <Button
                        size="small"
                        color="error"
                        onClick={() => setDeclining(request)}
                        aria-label={`Zugangswunsch für ${request.serverUrl} ablehnen`}
                      >
                        Ablehnen
                      </Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {total > requests.length && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
              Es werden die {requests.length} ältesten von {total} offenen Wünschen gezeigt.
            </Typography>
          )}
        </>
      )}

      <Dialog
        open={declining !== null}
        onClose={closeDecline}
        maxWidth="sm"
        fullWidth
        aria-labelledby="connection-profile-request-decline-title"
      >
        <DialogTitle id="connection-profile-request-decline-title">
          Zugangswunsch ablehnen?
        </DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>
            {declining &&
              `${declining.requestedByName} wird benachrichtigt, dass der Wunsch für ${declining.serverUrl} abgelehnt ist.`}
          </Typography>
          <TextField
            label="Antwort"
            size="small"
            multiline
            minRows={2}
            fullWidth
            value={answer}
            onChange={(e) => setAnswer(e.target.value.slice(0, MAX_ANSWER))}
            helperText={`Optional, höchstens ${MAX_ANSWER} Zeichen – etwa welcher Zugang stattdessen passt.`}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={closeDecline} disabled={submitting}>
            Abbrechen
          </Button>
          <Button
            variant="contained"
            color="error"
            onClick={() => void handleDecline()}
            disabled={submitting}
          >
            Ablehnen
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
