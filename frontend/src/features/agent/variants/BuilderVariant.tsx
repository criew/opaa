import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import { TextAction } from '@/shared/ui/DetailSection'
import { useAgentSetup, type AgentSetup } from '../hooks/useAgentSetup'
import { BasicsFields, LibrariesField, OutputField, PlanField } from '../components/AgentFields'
import {
  AdvancedSettings,
  AgentPageFrame,
  CancelButton,
  SaveButton,
  SectionTitle,
} from '../components/AgentPageFrame'
import { runOutline } from '../agentOutline'
import { AGENT_TOOLS, resultStamp } from '../agentTexts'
import type { AgentToolId } from '../types'

type BlockId = 'libraries' | AgentToolId | 'plan' | 'file'

const BLOCKS: { id: BlockId; label: string; description: string }[] = [
  {
    id: 'libraries',
    label: 'Wissen eingrenzen',
    description: 'Nur bestimmte Bibliotheken durchsuchen.',
  },
  ...AGENT_TOOLS.filter((tool) => tool.id !== 'search_knowledge').map((tool) => ({
    id: tool.id as BlockId,
    label: tool.label,
    description: tool.description,
  })),
  { id: 'plan', label: 'Plan', description: 'Vor dem Start einen Plan zeigen.' },
  { id: 'file', label: 'Datei nach Vorlage', description: 'Ergebnis als PDF oder Word ablegen.' },
]

function blockActive(setup: AgentSetup, id: BlockId, added: Set<BlockId>): boolean {
  const { config } = setup
  if (id === 'libraries') return added.has(id) || config.libraryIds.length > 0
  if (id === 'plan') return config.planStep !== 'OFF'
  if (id === 'file') return config.output === 'FILE'
  return config.tools.includes(id)
}

function BlockCard({
  title,
  description,
  onRemove,
  children,
}: {
  title: string
  description?: string
  onRemove?: () => void
  children?: ReactNode
}) {
  return (
    <Box
      component="section"
      aria-label={title}
      sx={{ border: 1, borderColor: 'divider', borderRadius: 2.5, p: 2 }}
    >
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2 }}>
        <Typography component="h3" sx={{ fontSize: 14.5, fontWeight: 600, flex: 1 }}>
          {title}
        </Typography>
        {onRemove && (
          <TextAction context={title} onClick={onRemove}>
            Entfernen
          </TextAction>
        )}
      </Box>
      {description && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>{description}</Typography>
      )}
      {children && (
        <Box sx={{ mt: 1.5, display: 'flex', flexDirection: 'column', gap: 2 }}>{children}</Box>
      )}
    </Box>
  )
}

const SAMPLE_QUESTION = 'Welche Fristen gelten für Widersprüche gegen Bescheide?'

/** A simulated chat turn that follows the setup as it changes. */
function LivePreview({ setup }: { setup: AgentSetup }) {
  const steps = runOutline(setup.config, setup.libraryPicks.length, setup.templates)
  const name = setup.config.name.trim() || 'Agent'
  return (
    <Box
      component="aside"
      aria-labelledby="agent-live-title"
      sx={{
        border: 1,
        borderColor: 'divider',
        borderRadius: 2.5,
        bgcolor: 'background.paper',
        overflow: 'hidden',
      }}
    >
      <Typography
        id="agent-live-title"
        component="h2"
        sx={{
          fontSize: 15,
          fontWeight: 600,
          px: 2.5,
          py: 1.5,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        Vorschau im Chat
      </Typography>
      <Box sx={{ p: 2.5, display: 'flex', flexDirection: 'column', gap: 1.5 }}>
        <Box
          sx={{
            alignSelf: 'flex-end',
            maxWidth: '90%',
            bgcolor: 'action.hover',
            borderRadius: 2.5,
            px: 1.5,
            py: 1,
          }}
        >
          <Typography component="span" sx={{ fontSize: 13.5, fontWeight: 600 }}>
            @{name}{' '}
          </Typography>
          <Typography component="span" sx={{ fontSize: 13.5 }}>
            {SAMPLE_QUESTION}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
          <SmartToyOutlinedIcon aria-hidden sx={{ fontSize: 18, mt: 0.25 }} />
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontSize: 13.5, fontWeight: 600, mb: 0.5, overflowWrap: 'anywhere' }}>
              {name}
            </Typography>
            <Box
              component="ol"
              sx={{ m: 0, pl: 2.5, display: 'flex', flexDirection: 'column', gap: 0.5 }}
            >
              {steps.map((step) => (
                <Typography component="li" key={step} sx={{ fontSize: 13.5 }}>
                  {step}
                </Typography>
              ))}
            </Box>
          </Box>
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
    </Box>
  )
}

/**
 * Variant D: a small base (mission plus search) and building blocks that add one capability
 * each; the preview beside it plays the run in words as the setup changes.
 */
export default function BuilderVariant({ agentId }: { agentId?: string }) {
  const setup = useAgentSetup(agentId)
  const navigate = useNavigate()
  const editing = agentId !== undefined
  const [added, setAdded] = useState<Set<BlockId>>(new Set())
  const idPrefix = 'agent-builder'
  const { config, patch } = setup

  const addBlock = (id: BlockId) => {
    setAdded(new Set([...added, id]))
    if (id === 'plan') patch({ planStep: 'SHOW' })
    else if (id === 'file') patch({ output: 'FILE' })
    else if (id !== 'libraries') patch({ tools: [...config.tools, id] })
  }
  const removeBlock = (id: BlockId) => {
    const next = new Set(added)
    next.delete(id)
    setAdded(next)
    if (id === 'plan') patch({ planStep: 'OFF' })
    else if (id === 'file') patch({ output: 'CHAT', templateIds: [] })
    else if (id === 'libraries') setup.setLibraries([])
    else patch({ tools: config.tools.filter((tool) => tool !== id) })
  }

  const active = BLOCKS.filter((block) => blockActive(setup, block.id, added))
  const available = BLOCKS.filter((block) => !blockActive(setup, block.id, added))
  const fileDisabled = setup.templates.length === 0

  return (
    <AgentPageFrame
      setup={setup}
      eyebrow={editing ? (setup.agent?.name ?? 'Agent') : 'Neuer Katalogeintrag'}
      title={editing ? 'Agent bearbeiten' : 'Neuer Agent'}
      maxWidth={680}
      aside={<LivePreview setup={setup} />}
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
      <Box
        component="form"
        noValidate
        onSubmit={(e) => e.preventDefault()}
        sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}
      >
        <BasicsFields setup={setup} idPrefix={idPrefix} instructionRows={3} />
        <Box sx={{ borderTop: 1, borderColor: 'divider', pt: 3 }}>
          <SectionTitle>Bausteine</SectionTitle>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
            <BlockCard title="Im Wissen suchen" description="Immer dabei." />
            {active.map((block) => (
              <BlockCard key={block.id} title={block.label} onRemove={() => removeBlock(block.id)}>
                {block.id === 'libraries' && <LibrariesField setup={setup} />}
                {block.id === 'plan' && <PlanField setup={setup} idPrefix={idPrefix} withoutOff />}
                {block.id === 'file' && <OutputField setup={setup} idPrefix={idPrefix} kindFixed />}
              </BlockCard>
            ))}
          </Box>
          {setup.errors.tools && (
            <Typography sx={{ color: 'error.main', fontSize: 12.5, mt: 1 }}>
              {setup.errors.tools}
            </Typography>
          )}
          {available.length > 0 && (
            <Box sx={{ mt: 2 }}>
              <Typography
                id={`${idPrefix}-add`}
                sx={{ fontSize: 12, color: 'text.secondary', mb: 1 }}
              >
                Hinzufügen
              </Typography>
              <Box
                role="group"
                aria-labelledby={`${idPrefix}-add`}
                sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}
              >
                {available.map((block) => (
                  <Tooltip key={block.id} title={block.description} describeChild>
                    <Button
                      variant="outlined"
                      size="small"
                      startIcon={<AddIcon />}
                      onClick={() => addBlock(block.id)}
                      disabled={block.id === 'file' && fileDisabled}
                    >
                      {block.label}
                    </Button>
                  </Tooltip>
                ))}
              </Box>
            </Box>
          )}
        </Box>
        <AdvancedSettings setup={setup} idPrefix={idPrefix} />
      </Box>
    </AgentPageFrame>
  )
}
