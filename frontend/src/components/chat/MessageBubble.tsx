import { useCallback, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import { alpha } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import TextSnippetOutlinedIcon from '@mui/icons-material/TextSnippetOutlined'
import FormatQuoteOutlinedIcon from '@mui/icons-material/FormatQuoteOutlined'
import type { ChatMessage } from '../../types/chat'
import { blue } from '../../theme/tokens'
import { buildCitationIndex, describeEvidenceSummary } from './citations'
import MarkdownRenderer from './MarkdownRenderer'
import SourceEvidenceDrawer from './SourceEvidenceDrawer'
import DocumentTextPreviewDialog from '../DocumentTextPreviewDialog'
import { useDocumentPreview } from '../../hooks/useDocumentPreview'

interface MessageBubbleProps {
  message: ChatMessage
}

export default function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user'

  // The answer's citation markers resolve to footnote numbers; the Belege themselves live only in
  // the Belegfenster - under the answer stays a single "Belege anzeigen" with a count line.
  const citations = useMemo(
    () => buildCitationIndex(message.content, message.sources),
    [message.content, message.sources],
  )
  const evidenceSummary = describeEvidenceSummary(citations)

  const [evidenceOpen, setEvidenceOpen] = useState(false)
  // The Belege a clicked footnote covers - a range like "3–4" covers several documents.
  const [focusedDocIndexes, setFocusedDocIndexes] = useState<number[]>([])

  // One instance for the Belegfenster, rendered here as a sibling of it: the Drawer unmounts its
  // children on close, which must not close a preview dialog or download snackbar it started.
  const documentPreview = useDocumentPreview()
  const handleCitationClick = useCallback(
    (numbers: number[]) => {
      const docIndexes = [
        ...new Set(
          numbers
            .map((n) => citations.docIndexByNumber.get(n))
            .filter((i): i is number => i !== undefined),
        ),
      ]
      setFocusedDocIndexes(docIndexes)
      setEvidenceOpen(true)
    },
    [citations],
  )
  const openAllEvidence = () => {
    setFocusedDocIndexes([])
    setEvidenceOpen(true)
  }

  return (
    <Box
      sx={{
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
        mb: 2,
        px: 2,
      }}
    >
      <Box
        sx={{
          display: 'flex',
          gap: 1.5,
          maxWidth: isUser ? '78%' : '100%',
          width: isUser ? undefined : '100%',
        }}
      >
        <Box sx={{ minWidth: 0, flexGrow: isUser ? undefined : 1 }}>
          {/* The prompt a question was built from - a snapshot of its title, deliberately no
              link, since the person may no longer be allowed to read the prompt. */}
          {isUser && message.usedPromptTitle && (
            <Typography
              component="div"
              data-testid="used-prompt"
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'flex-end',
                gap: 0.5,
                mb: 0.5,
                fontSize: 12,
                color: 'text.secondary',
              }}
            >
              <TextSnippetOutlinedIcon aria-hidden sx={{ fontSize: 14 }} />
              Prompt: {message.usedPromptTitle}
            </Typography>
          )}
          {/* Mockup 1a (#658): questions sit in a quiet blue-50 bubble with navy text; answers
              are plain running text without an avatar or a bubble around them. */}
          {isUser ? (
            <Box
              sx={(theme) => ({
                px: 2,
                py: 1.5,
                borderRadius: '10px',
                border: 1,
                bgcolor:
                  theme.palette.mode === 'dark'
                    ? alpha(theme.palette.primary.main, 0.16)
                    : blue[50],
                borderColor:
                  theme.palette.mode === 'dark'
                    ? alpha(theme.palette.primary.main, 0.32)
                    : blue[100],
                color: 'text.primary',
              })}
            >
              <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>
                {message.content}
              </Typography>
            </Box>
          ) : (
            <MarkdownRenderer
              content={message.content}
              citations={citations}
              onCitationClick={handleCitationClick}
            />
          )}

          {!isUser && message.answeredWithoutKnowledge && (
            <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
              Diese Antwort wurde ohne Wissensbasis erstellt.
            </Alert>
          )}

          {/* #203/#706: distinct from answeredWithoutKnowledge above - the space is curated, but
              none of its associated libraries are readable by this caller, not a deliberate
              "ohne Wissen" choice. */}
          {!isUser && message.noKnowledgeAvailableInSpace && (
            <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
              In diesem Space ist für Sie derzeit kein Wissen verfügbar.
            </Alert>
          )}

          {/* #667, mockup 1a: an answer that substantiates nothing still says what was looked at
              - the effective search scope by name, straight from QueryMetadata#searchedLibraries.
              Only when the answer cites nothing; next to cited Belege the list is noise. */}
          {!isUser &&
            citations.docs.length === 0 &&
            (message.searchedLibraries?.length ?? 0) > 0 && (
              <Typography
                variant="body2"
                sx={{ color: 'text.secondary', mt: 1 }}
                data-testid="searched-libraries"
              >
                Durchsucht wurden: {message.searchedLibraries!.map((l) => l.name).join(', ')}
              </Typography>
            )}

          {!isUser && evidenceSummary && (
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                flexWrap: 'wrap',
                columnGap: 1,
                mt: 1.25,
              }}
            >
              <Button
                size="small"
                variant="text"
                aria-haspopup="dialog"
                onClick={openAllEvidence}
                startIcon={<FormatQuoteOutlinedIcon aria-hidden />}
                sx={{ ml: -1, px: 1, fontSize: 12.5, fontWeight: 500 }}
              >
                Belege anzeigen
              </Button>
              <Typography
                component="span"
                data-testid="evidence-summary"
                sx={{ fontSize: 11.5, color: 'text.secondary' }}
              >
                {evidenceSummary}
              </Typography>
            </Box>
          )}
          {!isUser && (
            <SourceEvidenceDrawer
              open={evidenceOpen}
              onClose={() => setEvidenceOpen(false)}
              messageId={message.id}
              citations={citations}
              focusedDocIndexes={focusedDocIndexes}
              answeredAt={message.timestamp}
              openDocument={documentPreview.openDocument}
            />
          )}

          {!isUser && (
            <DocumentTextPreviewDialog
              previewDocument={documentPreview.previewDocument}
              onClose={documentPreview.closePreview}
            />
          )}
        </Box>
      </Box>
    </Box>
  )
}
