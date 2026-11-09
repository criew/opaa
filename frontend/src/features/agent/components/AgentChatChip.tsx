import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Typography from '@mui/material/Typography'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import { behaviourSummary, resultStamp } from '../agentTexts'
import type { AgentResponse } from '../types'

/**
 * The chosen agent above the input: a removable chip and one line on how it works. `replacesScope`
 * says the agent brings its own knowledge in place of the search scope chips.
 */
export default function AgentChatChip({
  agent,
  replacesScope,
  onRemove,
  disabled,
  templateName = null,
}: {
  agent: AgentResponse
  /** The first template's name, when the result is a file. */
  templateName?: string | null
  replacesScope: boolean
  onRemove: () => void
  disabled?: boolean
}) {
  const knowledge = replacesScope
    ? agent.libraryIds.length === 0
      ? 'sucht in allem, was Sie lesen dürfen'
      : `sucht in ${agent.libraryIds.length === 1 ? '1 Bibliothek' : `${agent.libraryIds.length} Bibliotheken`} des Agenten`
    : 'sucht im gewählten Suchbereich'
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1, minWidth: 0 }}>
      <Chip
        icon={<SmartToyOutlinedIcon />}
        label={agent.name}
        onDelete={disabled ? undefined : onRemove}
        size="small"
        color="primary"
        variant="outlined"
        aria-label={`Agent ${agent.name} entfernen`}
        sx={{ maxWidth: '100%' }}
      />
      <Typography
        sx={{ fontSize: 12.5, color: 'text.secondary', minWidth: 0, overflowWrap: 'anywhere' }}
      >
        {knowledge} · {behaviourSummary(agent)}
      </Typography>
      <Typography
        sx={{
          flexBasis: '100%',
          fontSize: 12.5,
          color: 'text.secondary',
          overflowWrap: 'anywhere',
        }}
      >
        Ergebnis trägt:{' '}
        {resultStamp(agent.name, agent.version, agent.libraryIds.length, templateName)}
      </Typography>
    </Box>
  )
}
