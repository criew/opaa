import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import KeyOutlinedIcon from '@mui/icons-material/KeyOutlined'
import type { Capability, CapabilityOverviewResponse } from '@/shared/api/types'
import { getCapabilityOverview } from '../api/capabilityAdminApi'
import { useAuthStore } from '@/shared/session/authStore'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import CapabilityRow from '../components/CapabilityRow'
import CapabilityAccessPanel from '../components/CapabilityAccessPanel'
import { contentWidth, fontSize } from '@/shared/theme/tokens'
import LoadingState from '@/shared/ui/states/LoadingState'
import LoadError from '@/shared/ui/states/LoadError'

/** The rights that read rather than create; they stand in a section of their own. */
const READING_RIGHTS: Capability[] = ['VIEW_AGENT_DIRECTORY']

/** The entries of one right side by side, in the order of the overview. */
function groupByCapability(overviews: CapabilityOverviewResponse[]) {
  const groups = new Map<Capability, CapabilityOverviewResponse[]>()
  for (const overview of overviews) {
    groups.set(overview.capability, [...(groups.get(overview.capability) ?? []), overview])
  }
  return [...groups.values()]
}

/**
 * Who may create what (ADR-0036, Entscheidung 5): one row per right with its state, changed in a
 * side panel that asks one question with three answers.
 */
export default function CapabilityManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const [overviews, setOverviews] = useState<CapabilityOverviewResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(isSystemAdmin)
  const [editing, setEditing] = useState<Capability | null>(null)

  // No synchronous setState in the body: the effect below calls the same function, and a
  // synchronous setState from there causes a render cascade (react-hooks/set-state-in-effect).
  const load = useCallback(
    () =>
      getCapabilityOverview()
        .then((loaded) => {
          setOverviews(loaded)
          setError(null)
        })
        .catch((err: unknown) =>
          setError(
            err instanceof Error ? err.message : 'Die Anlegerechte konnten nicht geladen werden.',
          ),
        )
        .finally(() => setIsLoading(false)),
    [],
  )

  useEffect(() => {
    if (!isSystemAdmin) return
    void load()
  }, [isSystemAdmin, load])

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.form }}>
        <PageHeading title="Anlegerechte" gutterBottom />
        <Alert severity="info">
          Anlegerechte vergibt die Systemverwaltung. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  const rights = groupByCapability(overviews)
  const creationRights = rights.filter((entries) => !READING_RIGHTS.includes(entries[0].capability))
  const readingRights = rights.filter((entries) => READING_RIGHTS.includes(entries[0].capability))
  const editingEntries = rights.find((entries) => entries[0].capability === editing) ?? null

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.list }}>
        <AreaPageHeader
          icon={KeyOutlinedIcon}
          title="Anlegerechte"
          description="Hier legen Sie fest, wer in OPAA neue Spaces, Bibliotheken und Gruppen anlegen darf. Auf Inhalte, die es schon gibt, hat das keinen Einfluss. Jede Änderung wirkt sofort und wird protokolliert."
        />

        <Box component="section" aria-labelledby="capability-overview-heading">
          <Typography
            id="capability-overview-heading"
            component="h2"
            sx={{ fontSize: fontSize.title, fontWeight: 600, mb: 1.5 }}
          >
            Wer darf was anlegen?
          </Typography>
          {error ? (
            <LoadError
              message="Die Anlegerechte konnten nicht geladen werden."
              detail={error}
              onRetry={() => void load()}
            />
          ) : isLoading ? (
            <LoadingState shape="rows" count={4} />
          ) : (
            <Box
              component="ul"
              sx={{
                listStyle: 'none',
                p: 0,
                m: 0,
                borderTop: 1,
                borderBottom: 1,
                borderColor: 'divider',
              }}
            >
              {creationRights.map((entries) => (
                <CapabilityRow
                  key={entries[0].capability}
                  entries={entries}
                  onEdit={() => setEditing(entries[0].capability)}
                />
              ))}
            </Box>
          )}
        </Box>

        {!error && !isLoading && readingRights.length > 0 && (
          <Box component="section" aria-labelledby="capability-reading-heading" sx={{ mt: 4 }}>
            <Typography
              id="capability-reading-heading"
              component="h2"
              sx={{ fontSize: fontSize.title, fontWeight: 600, mb: 1.5 }}
            >
              Wer darf das Agentenverzeichnis einsehen?
            </Typography>
            <Box
              component="ul"
              sx={{
                listStyle: 'none',
                p: 0,
                m: 0,
                borderTop: 1,
                borderBottom: 1,
                borderColor: 'divider',
              }}
            >
              {readingRights.map((entries) => (
                <CapabilityRow
                  key={entries[0].capability}
                  entries={entries}
                  onEdit={() => setEditing(entries[0].capability)}
                />
              ))}
            </Box>
          </Box>
        )}
      </Box>

      <CapabilityAccessPanel
        key={editing ?? 'closed'}
        entries={editingEntries}
        onClose={() => setEditing(null)}
        onSaved={load}
      />
    </Box>
  )
}
