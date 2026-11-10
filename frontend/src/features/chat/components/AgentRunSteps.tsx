import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { AgentRun, AgentRunStep } from '@/shared/api/types'

const TOOL_LABELS: Record<string, string> = {
  search_knowledge: 'Wissen durchsucht',
  read_document: 'Dokument gelesen',
  list_sources: 'Quellen aufgelistet',
  run_code: 'Code ausgeführt',
  fill_template: 'Vorlage befüllt',
  ask_user: 'Rückfrage',
  unknown: 'Unbekanntes Werkzeug',
}

const STATUS_LABELS: Record<AgentRunStep['status'], string | null> = {
  DONE: null,
  EMPTY: 'nichts gefunden',
  ARGUMENT_IGNORED: 'Einschränkung außerhalb des Bereichs ignoriert',
  INVALID_ARGUMENTS: 'nicht ausgeführt, Angaben unbrauchbar',
  UNKNOWN_TOOL: 'nicht ausgeführt, Werkzeug unbekannt',
  DENIED: 'nicht ausgeführt, keine Berechtigung',
  LIMIT: 'nicht ausgeführt, Grenze erreicht',
  ERROR: 'fehlgeschlagen',
}

const LIMIT_NOTES: Record<AgentRun['endState'], string | null> = {
  COMPLETED: null,
  LIMIT_CALLS: 'Der Agent hat die Höchstzahl an Schritten erreicht.',
  LIMIT_DURATION: 'Der Agent hat seine Höchstdauer erreicht.',
  LIMIT_VOLUME: 'Der Agent hat die Höchstmenge an gesammeltem Text erreicht.',
  ASK_USER: null,
}

function stepText(step: AgentRunStep): string {
  const parts = [TOOL_LABELS[step.tool] ?? step.tool]
  if (step.detail) parts[0] += `: ${step.detail}`
  if (step.hits != null) parts.push(`${step.hits} Treffer`)
  const status = STATUS_LABELS[step.status]
  if (status) parts.push(status)
  return parts.join(' · ')
}

interface AgentRunStepsProps {
  run: AgentRun
}

/**
 * The steps an agent took for an answer, collapsed by default. A run that stopped at a limit is
 * marked "nicht geprüft": the agent answered with what it had, which is not an error. A run that
 * asked the person back is marked as a question; the reply starts a new run. The open list names
 * the run id, which the system administration needs to read the run's step log.
 */
export default function AgentRunSteps({ run }: AgentRunStepsProps) {
  const [open, setOpen] = useState(false)
  const listId = useId()
  const limitNote = LIMIT_NOTES[run.endState]
  const count = run.steps.length

  return (
    <Box sx={{ mb: 1 }}>
      <ButtonBase
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        disabled={count === 0}
        sx={{ gap: 0.5, borderRadius: 1, color: 'text.secondary', typography: 'body2' }}
      >
        {count === 0
          ? 'Ohne Werkzeug beantwortet'
          : `${count} ${count === 1 ? 'Schritt' : 'Schritte'}`}
        {count > 0 && (
          <ExpandMoreIcon
            aria-hidden
            fontSize="small"
            sx={{ transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 150ms' }}
          />
        )}
      </ButtonBase>
      {limitNote && (
        <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.5 }}>
          <Box component="strong" sx={{ fontWeight: 600 }}>
            nicht geprüft
          </Box>
          {` – ${limitNote} Die Antwort stützt sich nur auf das bis dahin Gefundene.`}
        </Typography>
      )}
      {run.endState === 'ASK_USER' && (
        <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.5 }}>
          <Box component="strong" sx={{ fontWeight: 600 }}>
            Rückfrage
          </Box>
          {' – Der Agent braucht eine Angabe von dir. Antworte einfach in einer neuen Nachricht.'}
        </Typography>
      )}
      {open && count > 0 && (
        <Box
          component="ol"
          id={listId}
          sx={{ m: 0, mt: 0.5, pl: 3, color: 'text.secondary', typography: 'body2' }}
        >
          {run.steps.map((step, index) => (
            <li key={index}>{stepText(step)}</li>
          ))}
        </Box>
      )}
      {open && (
        <Typography
          variant="caption"
          component="p"
          sx={{ color: 'text.secondary', mt: 0.5, userSelect: 'all', wordBreak: 'break-all' }}
        >
          {`Laufkennung für die Fehlersuche: ${run.runId}`}
        </Typography>
      )}
    </Box>
  )
}
