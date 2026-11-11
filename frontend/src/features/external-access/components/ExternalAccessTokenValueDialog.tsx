import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ContentCopyOutlinedIcon from '@mui/icons-material/ContentCopyOutlined'
import type { CreatedExternalAccessTokenResponse } from '@/shared/api/types'
import { copyOnce } from '@/shared/utils/copyOnce'
import { fontFamily, fontSize, radius } from '@/shared/theme/tokens'
import { SHOWN_ONCE_HINT, clientSetupSnippets } from './tokenLabels'
import SecretDialog from '@/shared/ui/dialog/SecretDialog'

function Snippet({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: 'center', justifyContent: 'space-between' }}
      >
        <Typography sx={{ fontSize: fontSize.meta, fontWeight: 500 }}>{label}</Typography>
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
          fontSize: fontSize.meta,
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
 * The one-time display of the token value (ADR-0035 Entscheidung 2).
 *
 * Neither Escape nor a click outside closes it: the value exists exactly here and nowhere
 * else - afterwards the server only knows its hash and its prefix.
 *
 * The setup snippets are deliberately here and only here: they carry the value, and without it
 * they would be useless anywhere else. The detailed instructions stay in the manual.
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
    <SecretDialog
      titleId="token-value-title"
      title="Token erzeugt"
      onCopy={() => void copyOnce(created.token, 'Der Tokenwert')}
      onClose={onClose}
    >
      <Typography sx={{ fontSize: fontSize.text, mb: 1.5 }}>Für „{created.name}“</Typography>
      <Box
        data-testid="external-access-token-value"
        sx={{
          fontFamily: fontFamily.mono,
          fontSize: fontSize.title,
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
      <Alert severity="warning" sx={{ mt: 2 }}>
        {SHOWN_ONCE_HINT}
      </Alert>

      <Typography sx={{ fontSize: fontSize.text, fontWeight: 600, mt: 3 }}>
        So richten Sie Ihr Werkzeug ein
      </Typography>
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mb: 1.5 }}>
        Die Adresse ist {snippets.url}; der Wert geht als Kopfzeile „Authorization: Bearer …“ mit.
        Weicht die Adresse Ihrer Installation von dieser ab, tragen Sie dort die richtige ein. Den
        Wert im Klartext trägt nur der Claude-Code-Befehl, der ihn in Ihre benutzereigene
        Konfiguration schreibt; die beiden JSON-Schnipsel fragen ihn ab (VS Code) oder lesen ihn aus
        der Umgebungsvariablen OPAA_TOKEN (Cursor), damit er nicht in einer eingecheckten Datei
        landet. Ausführliche Anleitung im Handbuch, Kapitel Externe KI-Werkzeuge.
      </Typography>
      <Stack spacing={2}>
        <Snippet label="Claude Code" value={snippets.claudeCode} />
        <Snippet label="VS Code" value={snippets.vsCode} />
        <Snippet label="Cursor" value={snippets.cursor} />
      </Stack>
    </SecretDialog>
  )
}
