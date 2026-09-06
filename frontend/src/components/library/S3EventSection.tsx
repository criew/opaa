import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { S3ScopeRef } from '../../types/api'
import { generateS3EventsToken, removeS3EventsToken } from '../../services/api'
import { useLibraryStore } from '../../stores/libraryStore'

interface S3EventSectionProps {
  libraryId: string
  /** From LibraryResponse.s3EventsTokenSet - a yes/no, never the token (ADR-0027). */
  tokenSet: boolean | null | undefined
  /** The library's scopes, for the per-bucket set-up commands. */
  scopes: S3ScopeRef[]
}

/**
 * The event row of an S3 library's Quellkonfiguration, for managers (ADR-0027, Entscheidung 6).
 * The token is shown exactly once, in the dialog that follows its generation, together with the
 * endpoint and the set-up commands per provider; afterwards the row only knows that one exists.
 * Rotating replaces it immediately, removing closes the endpoint.
 */
export default function S3EventSection({ libraryId, tokenSet, scopes }: S3EventSectionProps) {
  const loadLibraryDetails = useLibraryStore((s) => s.loadLibraryDetails)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [revealed, setRevealed] = useState<{ token: string; url: string } | null>(null)
  const [confirm, setConfirm] = useState<'rotate' | 'remove' | null>(null)

  const generate = async () => {
    setBusy(true)
    setError(null)
    try {
      const response = await generateS3EventsToken(libraryId)
      setRevealed({ token: response.token, url: `${window.location.origin}${response.path}` })
      await loadLibraryDetails(libraryId)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Das Ereignis-Token konnte nicht erzeugt werden',
      )
    } finally {
      setBusy(false)
    }
  }

  const remove = async () => {
    setBusy(true)
    setError(null)
    try {
      await removeS3EventsToken(libraryId)
      await loadLibraryDetails(libraryId)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Das Ereignis-Token konnte nicht entfernt werden',
      )
    } finally {
      setBusy(false)
    }
  }

  // one subscription per scope, each with its own prefix - a bucket listed twice with two
  // prefixes needs two lines, a bucket without prefix one without --prefix
  const subscriptions = Array.from(
    new Map(
      scopes.map((scope) => [
        `${scope.bucket}\u0000${scope.prefix ?? ''}`,
        `mc event add ALIAS/${scope.bucket} arn:minio:sqs::opaa:webhook --event put,delete${
          scope.prefix ? ` --prefix "${scope.prefix}"` : ''
        }`,
      ]),
    ).values(),
  )
  const insecureOrigin = window.location.protocol !== 'https:'

  return (
    <Box data-testid="s3-event-section">
      <Typography variant="body2">
        <strong>Ereignisbenachrichtigung:</strong>{' '}
        {tokenSet
          ? 'Token hinterlegt — gemeldete Änderungen werden wenige Sekunden später geprüft'
          : 'nicht eingerichtet'}
      </Typography>
      <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 0.25 }}>
        Der Objektspeicher kann OPAA über erzeugte und gelöschte Objekte benachrichtigen; die
        gemeldeten Schlüssel werden dann wenige Sekunden später einzeln geprüft. Ein Löschereignis
        entfernt das Dokument erst, wenn der Speicher das Fehlen des Objekts bestätigt. Der Zeitplan
        bleibt als Sicherheitsnetz für verlorene Ereignisse. Das Token wird nur einmal angezeigt.
      </Typography>
      <Stack direction="row" spacing={1} sx={{ mt: 0.75, flexWrap: 'wrap' }} useFlexGap>
        <Button
          size="small"
          variant="outlined"
          onClick={() => (tokenSet ? setConfirm('rotate') : void generate())}
          disabled={busy}
        >
          {tokenSet ? 'Token neu erzeugen' : 'Benachrichtigung einrichten'}
        </Button>
        {tokenSet && (
          <Button size="small" color="error" onClick={() => setConfirm('remove')} disabled={busy}>
            Benachrichtigung entfernen
          </Button>
        )}
      </Stack>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {error}
        </Alert>
      )}

      {/* Rotating or removing invalidates what the store has configured - the store keeps
          sending, OPAA answers 401, and nothing on the store's side shows it. */}
      <Dialog
        open={confirm !== null}
        onClose={() => setConfirm(null)}
        aria-labelledby="s3-event-confirm-title"
      >
        <DialogTitle id="s3-event-confirm-title">
          {confirm === 'remove' ? 'Benachrichtigung entfernen?' : 'Token neu erzeugen?'}
        </DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            {confirm === 'remove'
              ? 'Der Objektspeicher kann OPAA danach nicht mehr benachrichtigen; Benachrichtigungen mit dem bisherigen Token werden abgewiesen. Änderungen erreichen den Index dann erst mit dem nächsten geplanten Lauf.'
              : 'Das bisherige Token gilt sofort nicht mehr. Bis das neue im Objektspeicher hinterlegt ist, werden Benachrichtigungen abgewiesen.'}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(null)}>Abbrechen</Button>
          <Button
            variant="contained"
            color={confirm === 'remove' ? 'error' : 'primary'}
            onClick={() => {
              const action = confirm
              setConfirm(null)
              void (action === 'remove' ? remove() : generate())
            }}
          >
            {confirm === 'remove' ? 'Entfernen' : 'Neu erzeugen'}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog
        open={revealed !== null}
        onClose={() => setRevealed(null)}
        aria-labelledby="s3-event-token-title"
        fullWidth
        maxWidth="md"
      >
        <DialogTitle id="s3-event-token-title">Ereignis-Token</DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 2 }}>
            Dieses Token wird nur jetzt angezeigt. Hinterlegen Sie es im Objektspeicher, bevor Sie
            den Dialog schließen — danach lässt es sich nur noch neu erzeugen.
          </Alert>
          {insecureOrigin && (
            <Alert severity="error" sx={{ mb: 2 }} data-testid="s3-event-insecure-origin">
              Diese Adresse ist nicht über https erreichbar. Der Objektspeicher würde das Token
              unverschlüsselt senden — für den Betrieb OPAA hinter TLS betreiben.
            </Alert>
          )}
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            Token
          </Typography>
          <Typography
            component="code"
            data-testid="s3-event-token"
            sx={{ display: 'block', fontFamily: 'monospace', wordBreak: 'break-all', mb: 2 }}
          >
            {revealed?.token}
          </Typography>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            Adresse (URL) des Endpunkts
          </Typography>
          <Typography
            component="code"
            data-testid="s3-event-endpoint"
            sx={{ display: 'block', fontFamily: 'monospace', wordBreak: 'break-all', mb: 2 }}
          >
            {revealed?.url}
          </Typography>
          <Typography variant="body2" sx={{ mb: 0.5 }}>
            <strong>MinIO:</strong> Webhook-Ziel anlegen und je Bucket die Ereignisse abonnieren
            (der Token wird als <code>Authorization: Bearer</code> gesendet):
          </Typography>
          <Typography
            component="pre"
            sx={{ fontFamily: 'monospace', fontSize: 12.5, whiteSpace: 'pre-wrap', mb: 2 }}
          >
            {[
              `mc admin config set ALIAS notify_webhook:opaa endpoint="${revealed?.url ?? ''}" auth_token="${revealed?.token ?? ''}"`,
              'mc admin service restart ALIAS',
              ...subscriptions,
            ].join('\n')}
          </Typography>
          <Typography variant="body2" sx={{ mb: 0.5 }}>
            <strong>Ceph RGW:</strong> Ein Topic mit dem Endpunkt anlegen; da Ceph nur
            Benutzer:Passwort in der Adresse kennt, den Token als Passwort eintragen — nur über
            https:
          </Typography>
          <Typography
            component="pre"
            sx={{ fontFamily: 'monospace', fontSize: 12.5, whiteSpace: 'pre-wrap', mb: 2 }}
          >
            {`push-endpoint=${(revealed?.url ?? '').replace('://', `://opaa:${revealed?.token ?? ''}@`)}`}
          </Typography>
          <Typography variant="body2">
            <strong>AWS (EventBridge):</strong> Eine API-Destination mit dieser Adresse und einer
            Verbindung vom Typ „API-Schlüssel“ anlegen — Kopfzeile{' '}
            <code>X-OPAA-Webhook-Secret</code>, Wert: das Token — und eine Regel auf „Object
            Created“ und „Object Deleted“ für den Bucket darauf zeigen lassen. Die Testnachricht{' '}
            <code>s3:TestEvent</code> beim Einrichten wird angenommen.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRevealed(null)}>Schließen</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
