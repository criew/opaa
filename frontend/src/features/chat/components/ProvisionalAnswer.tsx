import Box from '@mui/material/Box'
import MarkdownRenderer from '@/shared/ui/markdown/MarkdownRenderer'

export const PROVISIONAL_ANSWER_LABEL =
  'Antwort wird geschrieben … Vorläufig: Die Belege werden geprüft, sobald sie fertig ist.'

/** A citation marker the model has begun but not yet closed, at the end of the text. */
const OPEN_MARKER_AT_END = /【[^】]*$/

interface ProvisionalAnswerProps {
  /** The provisional text so far, citation markers included. */
  text: string
}

/**
 * The answer while the model writes it. Marked as provisional and without any action: it is not
 * validated yet and cannot be copied or exported; the final answer replaces it. The growing text is
 * no live region, so a screen reader is not read every word; {@link PROVISIONAL_ANSWER_LABEL} is
 * shown by the list's loading status above it, which stays mounted from "Denkt nach …" on so the
 * change is announced once.
 */
export default function ProvisionalAnswer({ text }: ProvisionalAnswerProps) {
  return (
    <Box data-testid="provisional-answer" aria-busy="true" sx={{ px: 2, mb: 2 }}>
      <Box sx={{ borderLeft: 3, borderColor: 'divider', borderLeftStyle: 'dashed', pl: 2 }}>
        {/* Without a citation index the renderer drops every marker; the open one at the end it
            cannot recognise yet. */}
        <MarkdownRenderer content={text.replace(OPEN_MARKER_AT_END, '')} />
      </Box>
    </Box>
  )
}
