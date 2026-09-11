import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import type { MailTemplatePreviewResponse } from '../../../types/api'
import { radius } from '../../../theme/tokens'
import SectionHead from '../../SectionHead'

interface MailTemplatePreviewPaneProps {
  preview: MailTemplatePreviewResponse | null
  error: string | null
  isLoading: boolean
}

/**
 * Betreff, Textfassung und gerenderte HTML-Fassung der Vorschau.
 *
 * Das HTML läuft in einem `iframe` mit leerem `sandbox` — kein Skript, keine Formulare, kein
 * Zugriff auf das umgebende Dokument. Eine Vorlage ist bearbeitbarer Text; sie wird hier
 * angesehen, nicht ausgeführt.
 */
export default function MailTemplatePreviewPane({
  preview,
  error,
  isLoading,
}: MailTemplatePreviewPaneProps) {
  return (
    <Box component="section" aria-labelledby="mail-preview-head">
      <SectionHead id="mail-preview-head">Vorschau mit Beispielwerten</SectionHead>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {!preview && !error && (
        <Box aria-busy={isLoading}>
          <Skeleton variant="rounded" height={180} />
        </Box>
      )}

      {preview && (
        <Box sx={{ opacity: isLoading ? 0.6 : 1 }}>
          <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Betreff</Typography>
          <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 2 }}>{preview.subject}</Typography>

          <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Textfassung</Typography>
          <Box
            component="pre"
            sx={{
              fontFamily: 'monospace',
              fontSize: 12.5,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              border: 1,
              borderColor: 'divider',
              borderRadius: `${radius.sm}px`,
              p: 1.5,
              mt: 0.5,
              mb: 2,
            }}
          >
            {preview.bodyPlain}
          </Box>

          <Typography sx={{ fontSize: 12, color: 'text.secondary', mb: 0.5 }}>
            HTML-Fassung
          </Typography>
          <Box
            component="iframe"
            title="Vorschau der HTML-Fassung"
            sandbox=""
            srcDoc={preview.bodyHtml}
            sx={{
              width: '100%',
              minHeight: 260,
              border: 1,
              borderColor: 'divider',
              borderRadius: `${radius.sm}px`,
              bgcolor: '#FFFFFF',
            }}
          />
        </Box>
      )}
    </Box>
  )
}
