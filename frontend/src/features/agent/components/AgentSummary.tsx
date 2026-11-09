import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import ValueRow from '@/shared/ui/ValueRow'
import { TextAction } from '@/shared/ui/DetailSection'
import { limitsSummary, planStepLabel, toolLabel } from '../agentTexts'
import type { AgentSetup } from '../hooks/useAgentSetup'
import { outputSummary } from '../agentOutline'

export type SummaryArea = 'auftrag' | 'stelle' | 'wissen' | 'werkzeuge' | 'ablauf' | 'ergebnis'

/**
 * The agent's setup as rows „Titel · Wert · Ändern“ (guidelines 5.12), for the review step and the
 * detail page. Without `onChange` the rows only read.
 */
export function AgentSummaryRows({
  setup,
  onChange,
  areas = ['auftrag', 'stelle', 'wissen', 'werkzeuge', 'ablauf', 'ergebnis'],
}: {
  setup: AgentSetup
  onChange?: (area: SummaryArea) => void
  areas?: SummaryArea[]
}) {
  const { config, libraryPicks, templates } = setup
  const action = (area: SummaryArea, title: string) =>
    onChange ? (
      <TextAction context={title} onClick={() => onChange(area)}>
        Ändern
      </TextAction>
    ) : undefined
  const rows: Record<SummaryArea, { title: string; value: string }> = {
    auftrag: {
      title: 'Auftrag',
      value: config.instruction.trim() || 'Noch keine Anweisung',
    },
    stelle: {
      title: 'Verantwortlich',
      value: config.responsibleUnit.trim() || 'Noch nicht angegeben',
    },
    wissen: {
      title: 'Wissen',
      value:
        libraryPicks.length === 0
          ? 'Alles, was die aufrufende Person lesen darf'
          : libraryPicks.map((pick) => pick.name).join(' · '),
    },
    werkzeuge: {
      title: 'Werkzeuge',
      value: config.tools.map(toolLabel).join(' · ') || 'Keine',
    },
    ablauf: {
      title: 'Ablauf',
      value: [
        planStepLabel(config.planStep),
        config.namesContradictions ? 'Widersprüche benennen' : null,
        limitsSummary(config.limits),
      ]
        .filter(Boolean)
        .join(' · '),
    },
    ergebnis: { title: 'Ergebnis', value: outputSummary(config, templates) },
  }
  return (
    <Box>
      {areas.map((area) => (
        <ValueRow
          key={area}
          title={rows[area].title}
          value={
            <Typography
              sx={{
                fontSize: 13.5,
                overflowWrap: 'anywhere',
                display: '-webkit-box',
                WebkitLineClamp: 3,
                WebkitBoxOrient: 'vertical',
                overflow: 'hidden',
              }}
            >
              {rows[area].value}
            </Typography>
          }
          action={action(area, rows[area].title)}
        />
      ))}
    </Box>
  )
}

/** The agent as the '@' list of the chat shows it: robot, name, description. */
export function AgentMentionRow({ name, description }: { name: string; description: string }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.25, minWidth: 0 }}>
      <SmartToyOutlinedIcon sx={{ fontSize: 18, mt: 0.25 }} aria-hidden />
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontSize: 14, fontWeight: 500, overflowWrap: 'anywhere' }}>
          @{name || 'Name'}
        </Typography>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', overflowWrap: 'anywhere' }}>
          {description || 'Ohne Beschreibung'}
        </Typography>
      </Box>
    </Box>
  )
}
