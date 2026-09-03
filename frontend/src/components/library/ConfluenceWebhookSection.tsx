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
import { generateConfluenceWebhookSecret, removeConfluenceWebhookSecret } from '../../services/api'
import { useLibraryStore } from '../../stores/libraryStore'

interface ConfluenceWebhookSectionProps {
  libraryId: string
  /** From LibraryResponse.confluenceWebhookSecretSet - a yes/no, never the secret (#1140). */
  secretSet: boolean | null | undefined
}

/**
 * #1140: the webhook row of a Confluence library's Quellkonfiguration, for managers. The secret is
 * shown exactly once, in the dialog that follows its generation; afterwards the row only knows that
 * one exists. Rotating replaces it immediately, removing closes the endpoint.
 */
export default function ConfluenceWebhookSection({
  libraryId,
  secretSet,
}: ConfluenceWebhookSectionProps) {
  const loadLibraryDetails = useLibraryStore((s) => s.loadLibraryDetails)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [revealed, setRevealed] = useState<{ secret: string; url: string } | null>(null)

  const endpointUrl = `${window.location.origin}/api/v1/libraries/${libraryId}/confluence-webhook`

  const generate = async () => {
    setBusy(true)
    setError(null)
    try {
      const response = await generateConfluenceWebhookSecret(libraryId)
      setRevealed({ secret: response.secret, url: `${window.location.origin}${response.path}` })
      await loadLibraryDetails(libraryId)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Das Webhook-Geheimnis konnte nicht erzeugt werden',
      )
    } finally {
      setBusy(false)
    }
  }

  const remove = async () => {
    setBusy(true)
    setError(null)
    try {
      await removeConfluenceWebhookSecret(libraryId)
      await loadLibraryDetails(libraryId)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Das Webhook-Geheimnis konnte nicht entfernt werden',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <Box data-testid="confluence-webhook-section">
      <Typography variant="body2">
        <strong>Webhook:</strong>{' '}
        {secretSet ? 'eingerichtet — Änderungen werden sofort aufgenommen' : 'nicht eingerichtet'}
      </Typography>
      <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 0.25 }}>
        Confluence kann OPAA über geänderte Seiten benachrichtigen; die gemeldeten Seiten werden
        dann gezielt neu geholt. Löschungen übernimmt weiterhin nur der Vollabgleich, sofern
        Confluence die Seite nicht selbst als im Papierkorb ausweist. Das Geheimnis wird nur einmal
        angezeigt.
      </Typography>
      <Stack direction="row" spacing={1} sx={{ mt: 0.75, flexWrap: 'wrap' }} useFlexGap>
        <Button size="small" variant="outlined" onClick={() => void generate()} disabled={busy}>
          {secretSet ? 'Geheimnis neu erzeugen' : 'Webhook einrichten'}
        </Button>
        {secretSet && (
          <Button size="small" color="error" onClick={() => void remove()} disabled={busy}>
            Webhook entfernen
          </Button>
        )}
      </Stack>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {error}
        </Alert>
      )}

      <Dialog
        open={revealed !== null}
        onClose={() => setRevealed(null)}
        aria-labelledby="confluence-webhook-secret-title"
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle id="confluence-webhook-secret-title">Webhook-Geheimnis</DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 2 }}>
            Dieses Geheimnis wird nur jetzt angezeigt. Notieren Sie es in Confluence, bevor Sie den
            Dialog schließen — danach lässt es sich nur noch neu erzeugen.
          </Alert>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            Geheimnis
          </Typography>
          <Typography
            component="code"
            data-testid="confluence-webhook-secret"
            sx={{ display: 'block', fontFamily: 'monospace', wordBreak: 'break-all', mb: 2 }}
          >
            {revealed?.secret}
          </Typography>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            Adresse (URL) des Webhooks
          </Typography>
          <Typography
            component="code"
            sx={{ display: 'block', fontFamily: 'monospace', wordBreak: 'break-all', mb: 2 }}
          >
            {revealed?.url ?? endpointUrl}
          </Typography>
          <Typography variant="body2" sx={{ mb: 1 }}>
            <strong>Data Center:</strong> In der Confluence-Administration unter „Webhooks“ einen
            Webhook mit dieser Adresse anlegen, das Geheimnis als „Secret“ hinterlegen und die
            Seiten-Ereignisse (erstellt, aktualisiert, entfernt, in den Papierkorb verschoben) sowie
            die Anhangs-Ereignisse auswählen. Confluence signiert jede Nachricht damit
            (X-Hub-Signature).
          </Typography>
          <Typography variant="body2">
            <strong>Cloud:</strong> Eine Automation-Regel „Web-Anfrage senden“ mit dieser Adresse
            anlegen, den HTTP-Header <code>X-OPAA-Webhook-Secret</code> mit dem Geheimnis setzen und
            als Inhalt die Seiten-ID mitgeben, z. B. <code>{'{"pageId": "{{page.id}}"}'}</code>.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRevealed(null)}>Schließen</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
