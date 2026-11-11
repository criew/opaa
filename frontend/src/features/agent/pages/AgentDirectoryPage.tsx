import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import LoadingState from '@/shared/ui/states/LoadingState'
import LoadError from '@/shared/ui/states/LoadError'
import EmptyState from '@/shared/ui/states/EmptyState'
import { notify } from '@/shared/stores/notificationStore'
import { contentWidth, fontSize } from '@/shared/theme/tokens'
import { formatDate } from '@/shared/utils/formatDate'
import { exportAgentDirectory } from '../api/agentApi'
import { useAgentDirectoryStore } from '../stores/agentDirectoryStore'
import { countLabel } from '../components/agentTexts'
import AgentDirectoryTable from '../components/AgentDirectoryTable'
import {
  agentDirectoryAgentRoute,
  agentDirectoryTemplateRoute,
  FORBIDDEN_NOTICE,
  UNAVAILABLE_NOTICE,
} from '../components/agentDirectoryTexts'

/** Saves the CSV the server built; the object URL is given back right after the click. */
function saveFile(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function csvFileName(): string {
  const [day, month, year] = formatDate(new Date()).split('.')
  return `agentenverzeichnis-${year}-${month}-${day}.csv`
}

/**
 * The agent directory: every agent and template of the organization, readable or not, for whoever
 * holds the right „Agentenverzeichnis einsehen“ - for the staff council. It describes objects,
 * never people: no owner, no usage, no last use. Reading and exporting are both audited.
 */
export default function AgentDirectoryPage() {
  const directory = useAgentDirectoryStore((s) => s.directory)
  const problem = useAgentDirectoryStore((s) => s.problem)
  const error = useAgentDirectoryStore((s) => s.error)
  const load = useAgentDirectoryStore((s) => s.load)
  const [exporting, setExporting] = useState(false)

  useEffect(() => {
    void load()
  }, [load])

  async function exportCsv() {
    setExporting(true)
    try {
      saveFile(await exportAgentDirectory(), csvFileName())
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Der Export ist fehlgeschlagen.', 'error')
    } finally {
      setExporting(false)
    }
  }

  if (problem === 'FORBIDDEN' || problem === 'UNAVAILABLE') {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Agentenverzeichnis" gutterBottom />
        <Alert severity="info">
          {problem === 'FORBIDDEN' ? FORBIDDEN_NOTICE : UNAVAILABLE_NOTICE}
        </Alert>
      </Box>
    )
  }

  const meta = directory
    ? [
        countLabel(directory.agents.length, 'Agent', 'Agenten'),
        directory.templatesIncluded
          ? countLabel(directory.templates.length, 'Vorlage', 'Vorlagen')
          : null,
      ]
        .filter(Boolean)
        .join(' · ')
    : undefined

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={MenuBookOutlinedIcon}
          title="Agentenverzeichnis"
          meta={meta}
          description="Alle Agenten und Vorlagen Ihrer Organisation mit Zweck, Anweisung und verantwortlicher Stelle. Inhalte der Bibliotheken und Angaben zur Nutzung enthält das Verzeichnis nicht. Jedes Ansehen und jeder Export wird protokolliert."
          action={
            directory ? (
              <BusyButton
                variant="contained"
                busy={exporting}
                busyAnnouncement="Das Verzeichnis wird exportiert."
                onClick={() => void exportCsv()}
              >
                Als CSV exportieren
              </BusyButton>
            ) : undefined
          }
        />

        {problem === 'FAILED' ? (
          <LoadError
            message="Das Agentenverzeichnis konnte nicht geladen werden."
            detail={error}
            onRetry={() => void load()}
          />
        ) : !directory ? (
          <LoadingState shape="rows" count={4} />
        ) : (
          <>
            <Box component="section" aria-labelledby="agent-directory-agents" sx={{ mb: 4 }}>
              <Typography
                id="agent-directory-agents"
                component="h2"
                sx={{ fontSize: fontSize.title, fontWeight: 600, mb: 1.5 }}
              >
                Agenten
              </Typography>
              {directory.agents.length === 0 ? (
                <EmptyState framed message="Noch keine Agenten." />
              ) : (
                <AgentDirectoryTable
                  label="Agenten"
                  rows={directory.agents}
                  routeOf={agentDirectoryAgentRoute}
                />
              )}
            </Box>

            {directory.templatesIncluded && (
              <Box component="section" aria-labelledby="agent-directory-templates">
                <Typography
                  id="agent-directory-templates"
                  component="h2"
                  sx={{ fontSize: fontSize.title, fontWeight: 600, mb: 1.5 }}
                >
                  Vorlagen
                </Typography>
                {directory.templates.length === 0 ? (
                  <EmptyState framed message="Noch keine Vorlagen." />
                ) : (
                  <AgentDirectoryTable
                    label="Vorlagen"
                    rows={directory.templates}
                    routeOf={agentDirectoryTemplateRoute}
                  />
                )}
              </Box>
            )}
          </>
        )}
      </Box>
    </Box>
  )
}
