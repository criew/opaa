import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ContentCopyOutlinedIcon from '@mui/icons-material/ContentCopyOutlined'
import type { CreatedExternalAccessTokenResponse } from '../../types/api'
import { copyOnce } from '../../utils/copyOnce'
import { fontFamily, radius } from '../../theme/tokens'
import { SHOWN_ONCE_HINT, clientSetupSnippets } from './tokenLabels'

function Snippet({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: 'center', justifyContent: 'space-between' }}
      >
        <Typography sx={{ fontSize: 12.5, fontWeight: 500 }}>{label}</Typography>
        <IconButton
          size="small"
          aria-label={`${label} kopieren`}
          onClick={() => void copyOnce(value, `Die Einrichtung für ${label}`)}
        >
          <ContentCopyOutlinedIcon fontSize="small" />
        </IconButton>
      </Stack>
      <Box
        component="pre"
        sx={{
          fontFamily: fontFamily.mono,
          fontSize: 12,
          m: 0,
          p: 1.25,
          border: 1,
          borderColor: 'divider',
          borderRadius: `${radius.sm}px`,
          overflowX: 'auto',
          whiteSpace: 'pre-wrap',
          overflowWrap: 'anywhere',
        }}
      >
        {value}
      </Box>
    </Box>
  )
}

/**
 * Die einmalige Anzeige des Tokenwerts (#1719, ADR-0035 Entscheidung 2).
 *
 * Weder Escape noch ein Klick daneben schließen: Der Wert existiert genau hier und nirgends
 * sonst - danach kennt der Server nur noch seinen Hash und sein Präfix.
 *
 * Die Einrichtungsschnipsel stehen bewusst hier und nur hier: Sie tragen den Wert, und ohne ihn
 * wären sie an jeder anderen Stelle nutzlos. Die ausführliche Anleitung bleibt beim Handbuch.
 */
export default function ExternalAccessTokenValueDialog({
  created,
  onClose,
}: {
  created: CreatedExternalAccessTokenResponse | null
  onClose: () => void
}) {
  if (!created) return null

  const snippets = clientSetupSnippets(window.location.origin, created.token)

  return (
    <Dialog open fullWidth maxWidth="sm" aria-labelledby="token-value-title">
      <DialogTitle id="token-value-title">Token erzeugt</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>Für „{created.name}“</Typography>
        <Box
          data-testid="external-access-token-value"
          sx={{
            fontFamily: fontFamily.mono,
            fontSize: 15,
            fontWeight: 500,
            p: 1.5,
            border: 1,
            borderColor: 'divider',
            borderRadius: `${radius.sm}px`,
            overflowWrap: 'anywhere',
          }}
        >
          {created.token}
        </Box>
        <Box sx={{ mt: 1 }}>
          <Button
            variant="outlined"
            size="small"
            startIcon={<ContentCopyOutlinedIcon />}
            onClick={() => void copyOnce(created.token, 'Der Tokenwert')}
          >
            Kopieren
          </Button>
        </Box>
        <Alert severity="warning" sx={{ mt: 2 }}>
          {SHOWN_ONCE_HINT}
        </Alert>

        <Typography sx={{ fontSize: 13.5, fontWeight: 600, mt: 3 }}>
          So richten Sie Ihr Werkzeug ein
        </Typography>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1.5 }}>
          Die Adresse ist {snippets.url}; der Wert geht als Kopfzeile „Authorization: Bearer …“ mit.
          Weicht die Adresse Ihrer Installation von dieser ab, tragen Sie dort die richtige ein. Der
          Zugang über diese Adresse steht bereit, sobald Ihre Installation den MCP-Server enthält.
        </Typography>
        <Stack spacing={2}>
          <Snippet label="Claude Code" value={snippets.claudeCode} />
          <Snippet label="Cursor oder VS Code" value={snippets.json} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button variant="contained" onClick={onClose}>
          Kopiert, schließen
        </Button>
      </DialogActions>
    </Dialog>
  )
}
