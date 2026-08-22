import { useCallback, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import { alpha } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { ChatMessage } from '../../types/chat'
import { blue } from '../../theme/tokens'
import { buildCitationIndex } from './citations'
import MarkdownRenderer from './MarkdownRenderer'
import SourceEvidenceDrawer from './SourceEvidenceDrawer'
import SourceFootnotes from './SourceFootnotes'
import FeedbackButtons from './FeedbackButtons'

interface MessageBubbleProps {
  message: ChatMessage
}

export default function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user'

  // Mockup 1a (#590): the answer's citation markers resolve to footnote numbers, rendered as
  // superscripts in the text and as the Fundstellen block below it.
  const citations = useMemo(
    () => buildCitationIndex(message.content, message.sources),
    [message.content, message.sources],
  )

  // A clicked footnote highlights every row it covers - the URL hash can only carry one target,
  // a range like "3–4" covers several (#590 Nachbesserung). Transient, so the flash reads as a
  // pointer rather than a persistent selection.
  const [highlightedDocIndexes, setHighlightedDocIndexes] = useState<number[]>([])
  const [evidenceOpen, setEvidenceOpen] = useState(false)
  const highlightTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const handleCitationClick = useCallback(
    (numbers: number[]) => {
      const docIndexes = [
        ...new Set(
          numbers
            .map((n) => citations.docIndexByNumber.get(n))
            .filter((i): i is number => i !== undefined),
        ),
      ]
      setHighlightedDocIndexes(docIndexes)
      if (highlightTimer.current) clearTimeout(highlightTimer.current)
      highlightTimer.current = setTimeout(() => setHighlightedDocIndexes([]), 2400)
    },
    [citations],
  )

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
              messageId={message.id}
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
              Only when the answer cites nothing; under a Fundstellen block the list is noise. */}
          {!isUser &&
            citations.docs.length === 0 &&
            (message.searchedLibraries?.length ?? 0) > 0 && (
              <Typography
                variant="body2"
                color="text.secondary"
                sx={{ mt: 1 }}
                data-testid="searched-libraries"
              >
                Durchsucht wurden: {message.searchedLibraries!.map((l) => l.name).join(', ')}
              </Typography>
            )}

          {!isUser && (
            <SourceFootnotes
              messageId={message.id}
              citations={citations}
              highlightedDocIndexes={highlightedDocIndexes}
              onOpenEvidence={
                citations.docs.length > 0 || citations.uncited.length > 0
                  ? () => setEvidenceOpen(true)
                  : undefined
              }
            />
          )}
          {!isUser && (
            <SourceEvidenceDrawer
              open={evidenceOpen}
              onClose={() => setEvidenceOpen(false)}
              citations={citations}
              answeredAt={message.timestamp}
            />
          )}

          {!isUser && (
            <Box sx={{ mt: 0.5 }}>
              <FeedbackButtons />
            </Box>
          )}
        </Box>
      </Box>
    </Box>
  )
}
