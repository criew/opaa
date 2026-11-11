import { useEffect, type ReactNode } from 'react'
import { useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import DetailSection, { TextLink } from '@/shared/ui/DetailSection'
import FormFieldLabel from '@/shared/ui/form/FormFieldLabel'
import LoadingState from '@/shared/ui/states/LoadingState'
import LoadError from '@/shared/ui/states/LoadError'
import { contentWidth, fontSize } from '@/shared/theme/tokens'
import { formatDate, formatNumber, MISSING_VALUE } from '@/shared/utils/formatDate'
import type { AgentDirectoryEntry, DocumentTemplateDirectoryEntry } from '@/shared/api/types'
import { useAgentDirectoryStore } from '../stores/agentDirectoryStore'
import { limitsSummary, planStepLabel, toolLabel } from '../components/agentTexts'
import {
  AGENT_DIRECTORY_ROUTE,
  FORBIDDEN_NOTICE,
  responsibilityLabel,
  UNAVAILABLE_NOTICE,
} from '../components/agentDirectoryTexts'

/** One read-only value with its label above it. */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box sx={{ mb: 2 }}>
      <FormFieldLabel>{label}</FormFieldLabel>
      <Typography component="div" sx={{ fontSize: fontSize.text, whiteSpace: 'pre-wrap' }}>
        {children}
      </Typography>
    </Box>
  )
}

function listOrMissing(values: string[], whenEmpty: string = MISSING_VALUE): string {
  return values.length > 0 ? values.join(', ') : whenEmpty
}

/** No assignment searches what the asker may read; an assignment whose libraries are gone, nothing. */
function libraryText(agent: AgentDirectoryEntry): string {
  if (agent.allReadableLibraries) return 'Alle Bibliotheken, die die fragende Person lesen darf'
  return listOrMissing(agent.libraryNames, 'keine (zugeordnete Bibliothek gelöscht)')
}

function lockText(locked: boolean, since?: string | null): string {
  return locked ? `Gesperrt seit ${formatDate(since)}` : 'Nicht gesperrt'
}

function AgentDetail({ agent }: { agent: AgentDirectoryEntry }) {
  return (
    <>
      <DetailSection title="Auftrag">
        <Field label="Zweck">{agent.description ?? MISSING_VALUE}</Field>
        <Field label="Anweisung">{agent.instruction || MISSING_VALUE}</Field>
      </DetailSection>
      <DetailSection title="Wissen und Werkzeuge">
        <Field label="Bibliotheken">{libraryText(agent)}</Field>
        <Field label="Werkzeuge">{listOrMissing(agent.toolNames.map(toolLabel))}</Field>
        <Field label="Vorlagen">{listOrMissing(agent.templateNames)}</Field>
      </DetailSection>
      <DetailSection title="Ablauf">
        <Field label="Beginn">{planStepLabel(agent.planStep)}</Field>
        <Field label="Grenzen">{limitsSummary(agent.limits)}</Field>
      </DetailSection>
      <DetailSection title="Verantwortung und Stand">
        <Field label="Verantwortliche Stelle">{responsibilityLabel(agent.responsibility)}</Field>
        <Field label="Fassung">{formatNumber(agent.revision)}</Field>
        <Field label="Sperre">{lockText(agent.locked, agent.lockedSince)}</Field>
        {agent.delivered && <Field label="Herkunft">Mit OPAA ausgeliefert</Field>}
      </DetailSection>
    </>
  )
}

function TemplateDetail({ template }: { template: DocumentTemplateDirectoryEntry }) {
  return (
    <>
      <DetailSection title="Vorlage">
        <Field label="Zweck">{template.description ?? MISSING_VALUE}</Field>
        <Field label="Datei">{template.fileName}</Field>
        <Field label="Platzhalter">{listOrMissing(template.placeholderNames)}</Field>
      </DetailSection>
      <DetailSection title="Verantwortung und Stand">
        <Field label="Verantwortliche Stelle">{responsibilityLabel(template.responsibility)}</Field>
        <Field label="Fassung">{formatNumber(template.revision)}</Field>
        <Field label="Sperre">{lockText(template.locked, template.lockedSince)}</Field>
      </DetailSection>
    </>
  )
}

/**
 * One entry of the agent directory, read-only. It shows what the list already read; only a page
 * opened directly reads - and thereby audits - the directory itself.
 */
export default function AgentDirectoryDetailPage() {
  const { agentId, templateId } = useParams<{ agentId?: string; templateId?: string }>()
  const directory = useAgentDirectoryStore((s) => s.directory)
  const problem = useAgentDirectoryStore((s) => s.problem)
  const error = useAgentDirectoryStore((s) => s.error)
  const load = useAgentDirectoryStore((s) => s.load)

  useEffect(() => {
    if (!useAgentDirectoryStore.getState().directory) void load()
  }, [load])

  const back = (
    <Box sx={{ mb: 2 }}>
      <TextLink to={AGENT_DIRECTORY_ROUTE}>Zum Agentenverzeichnis</TextLink>
    </Box>
  )

  if (problem === 'FORBIDDEN' || problem === 'UNAVAILABLE') {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.form }}>
        <PageHeading title="Agentenverzeichnis" gutterBottom />
        <Alert severity="info">
          {problem === 'FORBIDDEN' ? FORBIDDEN_NOTICE : UNAVAILABLE_NOTICE}
        </Alert>
      </Box>
    )
  }

  const agent = agentId ? directory?.agents.find((entry) => entry.id === agentId) : undefined
  const template = templateId
    ? directory?.templates.find((entry) => entry.id === templateId)
    : undefined

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.detail }}>
        {back}
        {problem === 'FAILED' ? (
          <LoadError
            message="Das Agentenverzeichnis konnte nicht geladen werden."
            detail={error}
            onRetry={() => void load()}
          />
        ) : !directory ? (
          <LoadingState shape="detail" />
        ) : agent ? (
          <>
            <PageHeading title={agent.name} gutterBottom />
            <AgentDetail agent={agent} />
          </>
        ) : template ? (
          <>
            <PageHeading title={template.name} gutterBottom />
            <TemplateDetail template={template} />
          </>
        ) : (
          <>
            <PageHeading title="Eintrag nicht gefunden" gutterBottom />
            <Alert severity="info">
              Diesen Eintrag gibt es im Agentenverzeichnis nicht, oder er wurde inzwischen gelöscht.
            </Alert>
          </>
        )}
      </Box>
    </Box>
  )
}
