import { AgentNotFound } from '../components/AgentPageFrame'
import { Navigate, useNavigate, useParams } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import AreaTabs from '@/shared/ui/AreaTabs'
import { currentUiLabVariant } from '@/shared/uiLabVariant'
import { useAgentSetup } from '../hooks/useAgentSetup'
import FormVariant from '../variants/FormVariant'
import WizardVariant from '../variants/WizardVariant'
import BuilderVariant from '../variants/BuilderVariant'
import { TabsCreate, TabsDetail } from '../variants/TabsVariant'
import { AgentGrants, AgentHeader } from '../components/AgentHeader'
import { AgentSummaryRows } from '../components/AgentSummary'

/** The lab's variant; without a choice the one-page form stands in. */
function variant(): string {
  return currentUiLabVariant() ?? 'formular'
}

export function AgentCreatePage() {
  switch (variant()) {
    case 'assistent':
      return <WizardVariant />
    case 'reiter':
      return <TabsCreate />
    case 'baukasten':
      return <BuilderVariant />
    default:
      return <FormVariant />
  }
}

export function AgentEditPage() {
  const { agentId = '' } = useParams()
  switch (variant()) {
    case 'assistent':
      return <WizardVariant agentId={agentId} />
    case 'reiter':
      return <Navigate to={`/agents/${agentId}`} replace />
    case 'baukasten':
      return <BuilderVariant agentId={agentId} />
    default:
      return <FormVariant agentId={agentId} />
  }
}

function ReadDetail({ agentId, tab }: { agentId: string; tab?: string }) {
  const setup = useAgentSetup(agentId)
  const navigate = useNavigate()
  if (!setup.loaded)
    return <Typography sx={{ p: 5, color: 'text.secondary' }}>Agent wird geladen …</Typography>
  if (!setup.agent) return <AgentNotFound />
  const active = tab === 'freigaben' ? 'freigaben' : 'einrichtung'
  const canEdit = ['OWNER', 'MANAGER', 'EDITOR'].includes(setup.agent.myRole)
  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: 880 }}>
        <AgentHeader
          setup={setup}
          actions={
            canEdit ? (
              <Button
                variant="outlined"
                size="small"
                onClick={() => navigate(`/agents/${agentId}/edit`)}
              >
                Bearbeiten
              </Button>
            ) : undefined
          }
        />
        <AreaTabs
          tabs={[
            { value: 'einrichtung', label: 'Einrichtung' },
            { value: 'freigaben', label: 'Freigaben' },
          ]}
          value={active}
          href={(value) =>
            value === 'einrichtung' ? `/agents/${agentId}` : `/agents/${agentId}/${value}`
          }
          label="Bereiche des Agenten"
          idPrefix="agent"
        >
          {(value) =>
            value === 'freigaben' ? (
              <AgentGrants setup={setup} />
            ) : (
              <AgentSummaryRows setup={setup} />
            )
          }
        </AreaTabs>
      </Box>
    </Box>
  )
}

export function AgentDetailPage() {
  const { agentId = '', tab } = useParams()
  if (variant() === 'reiter') return <TabsDetail agentId={agentId} tab={tab} />
  return <ReadDetail agentId={agentId} tab={tab} />
}
