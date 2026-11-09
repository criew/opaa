import Box from '@mui/material/Box'
import { useNavigate } from 'react-router'
import { useAgentSetup } from '../hooks/useAgentSetup'
import {
  BasicsFields,
  LibrariesField,
  OutputField,
  PlanField,
  ToolsField,
} from '../components/AgentFields'
import {
  AdvancedSettings,
  AgentPageFrame,
  CancelButton,
  SaveButton,
  SectionTitle,
} from '../components/AgentPageFrame'

/**
 * Variant A: everything on one page in four sections; limits, contradictions and model wait
 * folded under „Erweitert“.
 */
export default function FormVariant({ agentId }: { agentId?: string }) {
  const setup = useAgentSetup(agentId)
  const navigate = useNavigate()
  const editing = agentId !== undefined
  const idPrefix = 'agent-form'
  return (
    <AgentPageFrame
      setup={setup}
      eyebrow={editing ? (setup.agent?.name ?? 'Agent') : 'Neuer Katalogeintrag'}
      title={editing ? 'Agent bearbeiten' : 'Neuer Agent'}
      footer={
        <>
          <CancelButton setup={setup} to={editing ? `/agents/${agentId}` : undefined} />
          <Box sx={{ flex: 1 }} />
          <SaveButton
            setup={setup}
            label={editing ? 'Speichern' : 'Agent anlegen'}
            onSaved={(id) => navigate(`/agents/${id}`)}
          />
        </>
      }
    >
      <Box component="form" noValidate onSubmit={(e) => e.preventDefault()}>
        <Box component="section" sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, mb: 4 }}>
          <SectionTitle>Auftrag</SectionTitle>
          <BasicsFields setup={setup} idPrefix={idPrefix} />
        </Box>
        <Box
          component="section"
          sx={{
            display: 'flex',
            flexDirection: 'column',
            gap: 2.5,
            mb: 4,
            borderTop: 1,
            borderColor: 'divider',
            pt: 3,
          }}
        >
          <SectionTitle>Wissen und Werkzeuge</SectionTitle>
          <LibrariesField setup={setup} />
          <ToolsField setup={setup} idPrefix={idPrefix} />
        </Box>
        <Box
          component="section"
          sx={{
            display: 'flex',
            flexDirection: 'column',
            gap: 2.5,
            mb: 4,
            borderTop: 1,
            borderColor: 'divider',
            pt: 3,
          }}
        >
          <SectionTitle>Ablauf und Ergebnis</SectionTitle>
          <PlanField setup={setup} idPrefix={idPrefix} />
          <OutputField setup={setup} idPrefix={idPrefix} />
        </Box>
        <AdvancedSettings setup={setup} idPrefix={idPrefix} />
      </Box>
    </AgentPageFrame>
  )
}
