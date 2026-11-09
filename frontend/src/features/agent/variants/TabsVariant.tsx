import { useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import AreaTabs from '@/shared/ui/AreaTabs'
import { useAgentSetup, type AgentField, type AgentSetup } from '../hooks/useAgentSetup'
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
  AgentNotFound,
} from '../components/AgentPageFrame'
import { AgentGrants, AgentHeader } from '../components/AgentHeader'
import { AgentMentionRow } from '../components/AgentSummary'
import { runOutline } from '../agentOutline'
import { resultStamp } from '../agentTexts'

/** Variant C, creation: only the mission; everything else starts from defaults. */
export function TabsCreate() {
  const setup = useAgentSetup()
  const navigate = useNavigate()
  return (
    <AgentPageFrame
      setup={setup}
      eyebrow="Neuer Katalogeintrag"
      title="Neuer Agent"
      footer={
        <>
          <CancelButton setup={setup} />
          <Box sx={{ flex: 1 }} />
          <SaveButton
            setup={setup}
            label="Agent anlegen"
            onSaved={(id) => navigate(`/agents/${id}`)}
          />
        </>
      }
    >
      <Box
        component="form"
        noValidate
        onSubmit={(e) => e.preventDefault()}
        sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}
      >
        <BasicsFields setup={setup} idPrefix="agent-tabs-new" />
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
          Der Agent sucht und liest zunächst in allem, was die aufrufende Person lesen darf, und
          antwortet im Chat. Wissen, Ablauf und Ergebnis richten Sie danach ein.
        </Typography>
      </Box>
    </AgentPageFrame>
  )
}

type TabValue = 'auftrag' | 'wissen' | 'ablauf' | 'ergebnis' | 'freigaben'
const TABS: { value: TabValue; label: string }[] = [
  { value: 'auftrag', label: 'Auftrag' },
  { value: 'wissen', label: 'Wissen' },
  { value: 'ablauf', label: 'Ablauf' },
  { value: 'ergebnis', label: 'Ergebnis' },
  { value: 'freigaben', label: 'Freigaben' },
]
const TAB_FIELDS: Record<TabValue, AgentField[]> = {
  auftrag: ['name', 'responsibleUnit', 'instruction'],
  wissen: ['tools'],
  ablauf: ['calls', 'minutes', 'pages'],
  ergebnis: ['templateIds'],
  freigaben: [],
}

function TabSave({ setup }: { setup: AgentSetup }) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        mt: 3,
        pt: 2,
        borderTop: 1,
        borderColor: 'divider',
      }}
    >
      {setup.isDirty && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Ungespeicherte Änderungen
        </Typography>
      )}
      <Box sx={{ flex: 1 }} />
      <SaveButton setup={setup} label="Speichern" onSaved={() => undefined} />
    </Box>
  )
}

/** The aside: how the agent shows in the chat and what one run of it does. */
export function AgentPreview({ setup }: { setup: AgentSetup }) {
  const steps = runOutline(setup.config, setup.libraryPicks.length, setup.templates)
  return (
    <Box
      component="aside"
      aria-labelledby="agent-preview-title"
      sx={{
        border: 1,
        borderColor: 'divider',
        borderRadius: 2.5,
        p: 2.5,
        bgcolor: 'background.paper',
      }}
    >
      <Typography
        id="agent-preview-title"
        component="h2"
        sx={{ fontSize: 15, fontWeight: 600, mb: 1.5 }}
      >
        Vorschau
      </Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary', mb: 0.75 }}>Im Chat</Typography>
      <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1.5, p: 1.25, mb: 2 }}>
        <AgentMentionRow name={setup.config.name} description={setup.config.description} />
      </Box>
      <Typography sx={{ fontSize: 12, color: 'text.secondary', mb: 0.75 }}>Ein Lauf</Typography>
      <Box
        component="ol"
        sx={{ m: 0, pl: 2.5, display: 'flex', flexDirection: 'column', gap: 0.75 }}
      >
        {steps.map((step) => (
          <Typography component="li" key={step} sx={{ fontSize: 13.5 }}>
            {step}
          </Typography>
        ))}
      </Box>
      <Typography
        sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1.5, overflowWrap: 'anywhere' }}
      >
        {resultStamp(
          setup.config.name,
          (setup.agent?.version ?? 0) + (setup.isDirty || !setup.agent ? 1 : 0),
          setup.libraryPicks.length,
          setup.config.output === 'FILE'
            ? (setup.templates.find((t) => t.id === setup.config.templateIds[0])?.name ?? null)
            : null,
        )}
      </Typography>
    </Box>
  )
}

/** Variant C, detail: the setup itself is the detail page; each tab saves on its own. */
export function TabsDetail({ agentId, tab }: { agentId: string; tab?: string }) {
  const setup = useAgentSetup(agentId)
  const active: TabValue = TABS.some((entry) => entry.value === tab) ? (tab as TabValue) : 'auftrag'
  const idPrefix = 'agent-tabs'
  if (!setup.loaded)
    return <Typography sx={{ p: 5, color: 'text.secondary' }}>Agent wird geladen …</Typography>
  if (!setup.agent) return <AgentNotFound />
  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: 'minmax(0, 1fr)', lg: 'minmax(0, 760px) 340px' },
          gap: 5,
          alignItems: 'start',
        }}
      >
        <Box sx={{ minWidth: 0 }}>
          <AgentHeader setup={setup} />
          {setup.saveError && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {setup.saveError}
            </Alert>
          )}
          <AreaTabs
            tabs={TABS}
            value={active}
            href={(value) =>
              value === 'auftrag' ? `/agents/${agentId}` : `/agents/${agentId}/${value}`
            }
            label="Bereiche des Agenten"
            idPrefix="agent"
          >
            {(value) => {
              if (value === 'freigaben') return <AgentGrants setup={setup} />
              return (
                <Box component="form" noValidate onSubmit={(e) => e.preventDefault()}>
                  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
                    {value === 'auftrag' && (
                      <BasicsFields setup={setup} idPrefix={idPrefix} instructionRows={6} />
                    )}
                    {value === 'wissen' && (
                      <>
                        <LibrariesField setup={setup} />
                        <ToolsField setup={setup} idPrefix={idPrefix} />
                      </>
                    )}
                    {value === 'ablauf' && (
                      <>
                        <PlanField setup={setup} idPrefix={idPrefix} />
                        <AdvancedSettings setup={setup} idPrefix={idPrefix} />
                      </>
                    )}
                    {value === 'ergebnis' && <OutputField setup={setup} idPrefix={idPrefix} />}
                  </Box>
                  {TAB_FIELDS[value] && <TabSave setup={setup} />}
                </Box>
              )
            }}
          </AreaTabs>
        </Box>
        <AgentPreview setup={setup} />
      </Box>
    </Box>
  )
}
