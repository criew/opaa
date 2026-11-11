import Box from '@mui/material/Box'
import MarkdownRenderer from '@/shared/ui/markdown/MarkdownRenderer'
import type { AgentRun } from '@/shared/api/types'
import AgentRunSteps from './AgentRunSteps'

export const PROVISIONAL_ANSWER_LABEL =
  'Antwort wird geschrieben … Vorläufig, Belege noch nicht geprüft.'

/** The status of an agent's answer: it arrives whole, then its Belege are checked. */
export const AGENT_PROVISIONAL_ANSWER_LABEL = 'Belege werden geprüft … Vorläufig.'

/** A citation marker the model has begun but not yet closed, at the end of the text. */
const OPEN_MARKER_AT_END = /【[^】]*$/

interface ProvisionalAnswerProps {
  /** The provisional text so far, citation markers included. */
  text: string
  /** The ended agent run behind the answer, shown where the kept answer shows its steps. */
  run?: AgentRun
}

/**
 * The answer while the model writes it. Marked as provisional and without any action: it is not
 * validated yet and cannot be copied or exported; the final answer replaces it. The growing text is
 * no live region, so a screen reader is not read every word; {@link PROVISIONAL_ANSWER_LABEL} is
 * shown once, by the chat's status line (`ChatStatusLine`) above it.
 */
export default function ProvisionalAnswer({ text, run }: ProvisionalAnswerProps) {
  return (
    <Box data-testid="provisional-answer" aria-busy="true" sx={{ px: 2, mb: 2 }}>
      <Box sx={{ borderLeft: 3, borderColor: 'divider', borderLeftStyle: 'dashed', pl: 2 }}>
        {/* Without a citation index the renderer drops every marker; the open one at the end it
            cannot recognise yet. */}
        <MarkdownRenderer reading content={text.replace(OPEN_MARKER_AT_END, '')} />
      </Box>
      {run && <AgentRunSteps run={run} />}
    </Box>
  )
}
