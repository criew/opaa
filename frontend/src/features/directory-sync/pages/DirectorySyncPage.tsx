import { useCallback, useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import SyncOutlinedIcon from '@mui/icons-material/SyncOutlined'
import type { DirectorySyncStatusResponse } from '@/shared/api/types'
import { getDirectorySyncStatus } from '../api/directorySyncApi'
import { useAuthStore } from '@/shared/session/authStore'
import { useOidcProviderStore } from '@/features/identity-provider'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import SectionHead from '@/shared/ui/SectionHead'
import StatusLine from '@/shared/ui/StatusLine'
import { ListEmptyState, ListLoading } from '@/shared/ui/list/AdminList'
import DirectorySyncOverview from '../components/DirectorySyncOverview'
import DirectorySyncPanel from '../components/DirectorySyncPanel'
import DirectorySyncThresholdSection from '../components/DirectorySyncThresholdSection'
import { usePendingPlans } from '../components/usePendingPlans'
import {
  attentionOf,
  matchesFilter,
  type OverviewEntry,
  type OverviewFilter,
} from '../components/directorySyncState'
import { contentWidth } from '@/shared/theme/tokens'

/** From this many providers on, the overview offers a filter and a search. */
const FILTER_FROM = 6

/** „1 Anbieter braucht Ihre Entscheidung" - the summary above the overview. */
function attentionSummary(entries: OverviewEntry[]) {
  const decisions = entries.filter((entry) => entry.status?.pendingPlan).length
  const problems = entries.filter((entry) => entry.attention && !entry.status?.pendingPlan)
  const failed = problems.some((entry) => entry.attention?.tone === 'error')
  const parts = [
    decisions > 0 &&
      `${decisions} Anbieter ${decisions === 1 ? 'braucht' : 'brauchen'} Ihre Entscheidung über einen Plan`,
    problems.length > 0 &&
      `bei ${problems.length} ${problems.length === 1 ? 'Anbieter läuft' : 'Anbietern läuft'} der Abgleich nicht wie vorgesehen`,
  ].filter((part): part is string => Boolean(part))
  if (parts.length === 0) {
    return {
      tone: 'success' as const,
      headline: 'Kein Handlungsbedarf',
      detail: 'Kein Plan wartet, und kein eingeschalteter Abgleich meldet eine Störung.',
    }
  }
  const sentence = parts.join('; ')
  return {
    tone: failed ? ('error' as const) : ('warning' as const),
    headline: sentence.charAt(0).toUpperCase() + sentence.slice(1),
    detail: 'Die betroffenen Zeilen sind markiert; öffnen Sie den Anbieter, um zu entscheiden.',
  }
}

/**
 * The directory sync per provider (ADR-0036, Entscheidung 3): one row per provider with its
 * mechanism, last and next run and what needs doing; report, plan, schedule and access open in
 * the side panel.
 */
export default function DirectorySyncPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const providers = useOidcProviderStore((s) => s.providers)
  const loadProviders = useOidcProviderStore((s) => s.loadProviders)
  const [statuses, setStatuses] = useState<DirectorySyncStatusResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(isSystemAdmin)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [filter, setFilter] = useState<OverviewFilter>('ALL')
  const [query, setQuery] = useState('')
  const [now, setNow] = useState(() => new Date())

  // No synchronous setState in the body: the effect below calls the same function, and a
  // synchronous setState from there causes a render cascade (react-hooks/set-state-in-effect).
  const reload = useCallback(
    () =>
      Promise.all([loadProviders(), getDirectorySyncStatus()])
        .then(([, loadedStatuses]) => {
          setStatuses(loadedStatuses)
          setNow(new Date())
          setError(null)
        })
        .catch((err: unknown) =>
          setError(
            err instanceof Error ? err.message : 'Der Abgleichstand konnte nicht geladen werden.',
          ),
        )
        .finally(() => setIsLoading(false)),
    [loadProviders],
  )
  const { plans, ensure, retry, replace } = usePendingPlans(reload)

  useEffect(() => {
    if (!isSystemAdmin) return
    void reload()
  }, [isSystemAdmin, reload])

  const entries = useMemo<OverviewEntry[]>(
    () =>
      providers
        .filter((provider) => provider.providerType === 'OIDC')
        .map((provider) => {
          const status = statuses.find((candidate) => candidate.providerId === provider.id)
          return { provider, status, attention: attentionOf(provider, status, now) }
        }),
    [providers, statuses, now],
  )

  const selected = entries.find((entry) => entry.provider.id === selectedId) ?? null
  const selectedPlanId = selected?.status?.pendingPlan?.id ?? null

  // The plan is fetched when its provider is opened, not with the overview: every retrieval is an
  // audit entry (ADR-0036, Entscheidung 9).
  useEffect(() => {
    if (selectedId) ensure(selectedId, selectedPlanId)
  }, [selectedId, selectedPlanId, ensure])

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Verzeichnisabgleich" gutterBottom />
        <Alert severity="info">
          Den Verzeichnisabgleich führt die Systemverwaltung. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  const visible = entries.filter((entry) => matchesFilter(entry, filter, query))
  const attentionCount = entries.filter((entry) => entry.attention).length
  const onCount = entries.filter((entry) => entry.provider.directorySyncEnabled).length
  const summary = attentionSummary(entries)

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={SyncOutlinedIcon}
          title="Verzeichnisabgleich"
          meta={entries.length === 1 ? '1 Anbieter' : `${entries.length} Anbieter`}
          description="OPAA liest Gruppen, Mitgliedschaften und Kontostatus regelmäßig aus dem Verzeichnis eines Identitätsanbieters – nur lesend, OPAA schreibt nie ins Verzeichnis. Je Anbieter kommen die Gruppen entweder aus diesem Abgleich oder aus dem Token bei der Anmeldung."
        />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <Box component="section" aria-labelledby="directory-sync-overview-heading">
          <SectionHead id="directory-sync-overview-heading">Abgleich je Anbieter</SectionHead>
          {isLoading ? (
            <ListLoading label="Abgleichstand wird geladen …" />
          ) : entries.length === 0 ? (
            <ListEmptyState
              title="Kein Identitätsanbieter hinterlegt"
              hint="Ein Verzeichnisabgleich gehört immer zu einem Anbieter. Legen Sie ihn zuerst unter Administration → Identitätsanbieter an."
            />
          ) : (
            <>
              <StatusLine headline={summary.headline} tone={summary.tone} detail={summary.detail} />

              {entries.length >= FILTER_FROM && (
                <Stack
                  direction={{ xs: 'column', sm: 'row' }}
                  spacing={1.5}
                  sx={{ mb: 2, alignItems: { sm: 'center' } }}
                >
                  <ToggleButtonGroup
                    exclusive
                    size="small"
                    value={filter}
                    onChange={(_event, next: OverviewFilter | null) => next && setFilter(next)}
                    aria-label="Anbieter anzeigen"
                  >
                    <ToggleButton value="ALL">Alle ({entries.length})</ToggleButton>
                    <ToggleButton value="ATTENTION">
                      Handlungsbedarf ({attentionCount})
                    </ToggleButton>
                    <ToggleButton value="ON">Eingeschaltet ({onCount})</ToggleButton>
                  </ToggleButtonGroup>
                  <TextField
                    size="small"
                    type="search"
                    label="Anbieter suchen"
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    sx={{ width: { sm: 260 } }}
                  />
                </Stack>
              )}

              {visible.length === 0 ? (
                <ListEmptyState
                  title="Kein Anbieter passt zu dieser Auswahl"
                  hint="Wählen Sie „Alle“ oder ändern Sie den Suchbegriff."
                />
              ) : (
                <DirectorySyncOverview entries={visible} onOpen={setSelectedId} now={now} />
              )}
            </>
          )}
          {!isLoading && entries.length > 0 && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1.5 }}>
              Der nächste Lauf ergibt sich aus dem letzten Lauf und dem Intervall des Anbieters;
              „jetzt fällig“ heißt, das Intervall ist verstrichen. Das gilt nur, solange der
              Zeittakt der Installation eingeschaltet ist – sonst laufen Abgleiche nur von Hand.
            </Typography>
          )}
        </Box>

        <DirectorySyncThresholdSection />
      </Box>

      <DirectorySyncPanel
        key={selectedId ?? 'closed'}
        provider={selected?.provider ?? null}
        status={selected?.status}
        planState={selectedId ? plans[selectedId] : undefined}
        onPlanReplaced={(plan) => selectedId && replace(selectedId, plan)}
        onPlanRetry={() => selectedId && retry(selectedId, selectedPlanId)}
        onClose={() => setSelectedId(null)}
        onChanged={reload}
      />
    </Box>
  )
}
