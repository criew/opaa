import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { AgentRun, AgentRunStamp } from '@/shared/api/types'
import MetaBadge from '@/shared/ui/MetaBadge'

const DRAFT_NOTICE = 'Entwurf, nicht geprüft'

function revisionText(stamp: AgentRunStamp): string {
  const current = stamp.currentAgentRevision
  if (current != null && current !== stamp.agentRevision) {
    return `mit Fassung ${stamp.agentRevision} erstellt, aktuell Fassung ${current}`
  }
  return `Fassung ${stamp.agentRevision}`
}

interface AgentRunStampLineProps {
  run: AgentRun
}

/**
 * The draft mark and version stamp under an agent answer: one quiet line with the label "Entwurf"
 * and the agent's revision - "mit Fassung N erstellt, aktuell Fassung M" once the agent has
 * changed - and, opened, the libraries, templates and model the answer was made with. The agent
 * itself is named once, at the question. A run kept before stamps were recorded shows the draft
 * mark alone.
 */
export default function AgentRunStampLine({ run }: AgentRunStampLineProps) {
  const [open, setOpen] = useState(false)
  const detailsId = useId()
  const stamp = run.stamp

  return (
    <Box sx={{ mb: 1, color: 'text.secondary', typography: 'body2' }} data-testid="agent-stamp">
      <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
        <MetaBadge ariaLabel={DRAFT_NOTICE}>Entwurf</MetaBadge>
        {stamp && (
          <ButtonBase
            onClick={() => setOpen((value) => !value)}
            aria-expanded={open}
            aria-controls={open ? detailsId : undefined}
            sx={{ gap: 0.5, borderRadius: 1, color: 'text.secondary', typography: 'body2' }}
          >
            {revisionText(stamp)}
            <ExpandMoreIcon
              aria-hidden
              fontSize="small"
              sx={{ transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 150ms' }}
            />
          </ButtonBase>
        )}
      </Box>
      {open && stamp && (
        <Box component="dl" id={detailsId} sx={{ m: 0, mt: 0.5 }}>
          <StampFact label="Bibliotheken">
            {stamp.libraries.length > 0
              ? stamp.libraries.map((library) => library.name).join(', ')
              : 'keine'}
          </StampFact>
          {stamp.templates.length > 0 && (
            <StampFact label={stamp.templates.length === 1 ? 'Vorlage' : 'Vorlagen'}>
              {stamp.templates
                .map((template) => `„${template.name}“, Fassung ${template.revision}`)
                .join('; ')}
            </StampFact>
          )}
          {stamp.model && <StampFact label="Modell">{stamp.model}</StampFact>}
          <Typography variant="body2" component="p" sx={{ color: 'text.secondary', mt: 0.5 }}>
            {`${DRAFT_NOTICE}: Die Antwort ist eine Vorlage für Ihre Bearbeitung, kein geprüftes Arbeitsergebnis.`}
          </Typography>
        </Box>
      )}
    </Box>
  )
}

function StampFact({ label, children }: { label: string; children: string }) {
  return (
    <Box sx={{ display: 'flex', gap: 0.5 }}>
      <Box component="dt" sx={{ fontWeight: 600 }}>
        {`${label}:`}
      </Box>
      <Box component="dd" sx={{ m: 0 }}>
        {children}
      </Box>
    </Box>
  )
}
