import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import type {
  CapabilityOverviewResponse,
  ConnectionProfileImpactResponse,
  ConnectionProfileResponse,
  ConnectorTypeStateResponse,
} from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { useSourceTypes } from './useSourceTypes'
import { getCapabilityOverview } from '@/features/capability'
import {
  deleteConnectionProfile,
  disconnectAllConnections,
  getConnectionProfileImpact,
  listConnectionProfiles,
  listConnectorTypeStates,
  lockConnectionProfile,
  testConnectionProfileSignIn,
} from '../api/connectionProfileApi'
import StatusLine from '@/shared/ui/StatusLine'
import { ListEmptyState, ListLoading } from '@/shared/ui/list/AdminList'
import ConnectionProfileFormDialog from './ConnectionProfileFormDialog'
import ConnectionProfileOverview, { type ProfileEntry } from './ConnectionProfileOverview'
import ConnectionProfilePanel from './ConnectionProfilePanel'
import DormantSourceConnectionsSection from './DormantSourceConnectionsSection'
import { confirmLock } from './connectorLock'
import { admitsProfiles } from './connectionProfileLabels'
import { admitsPersons, personCountLabel } from './profileChange'
import { accessOf, attentionOf, profileAccessEntry, signsInItself, summaryOf } from './profileState'

function connectionCount(count: number) {
  return count === 1 ? '1 Verbindung' : `${count} Verbindungen`
}

/** What the persons with a connected account learn and have to do after a shutdown. */
function personsNotice(profile: ConnectionProfileResponse): string {
  return admitsPersons(profile.ownership)
    ? ' Personen mit verbundenem Konto werden benachrichtigt und müssen ihr Konto selbst neu verbinden.'
    : ''
}

/** The persons' accounts a shutdown or deletion cuts off, as rounded as the API gives them. */
function accountsClause(
  profile: ConnectionProfileResponse,
  impact: ConnectionProfileImpactResponse,
): string {
  return admitsPersons(profile.ownership)
    ? `, verbundene Konten von Personen: ${personCountLabel(impact.connectedAccounts)}`
    : ''
}

/** The query parameter that opens the panel of one profile, e.g. from a notification. */
export const PROFILE_PARAM = 'profile'

/**
 * The „Zugänge“ tab of the connections area: at the top what needs doing, below it per profile
 * source type and state, server, who may use it, the connections and the need for action - one
 * row per profile. Everything else is in the side panel; `?profile=<id>` opens it. Below that,
 * the libraries with a dormant source. The role barrier is on the page.
 */
export default function ConnectionProfilesTab() {
  const [searchParams, setSearchParams] = useSearchParams()
  const openId = searchParams.get(PROFILE_PARAM)
  const { sourceTypes, loaded: sourceTypesLoaded } = useSourceTypes()
  const anyTypeAdmitsProfiles = sourceTypes.some(admitsProfiles)
  const [profiles, setProfiles] = useState<ConnectionProfileResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState<{ open: boolean; profile: ConnectionProfileResponse | null }>({
    open: false,
    profile: null,
  })
  const [typeStates, setTypeStates] = useState<ConnectorTypeStateResponse[] | null>(null)
  const [typeError, setTypeError] = useState<string | null>(null)
  const [overview, setOverview] = useState<CapabilityOverviewResponse[] | null>(null)
  const [overviewError, setOverviewError] = useState<string | null>(null)
  const [dormant, setDormant] = useState<number | null>(null)
  const [dormantFailed, setDormantFailed] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)
  const reload = () => setReloadKey((key) => key + 1)

  // No synchronous setState in the body: the effect below calls this function, and a synchronous
  // setState from there causes a render cascade (react-hooks/set-state-in-effect).
  const loadOverview = useCallback(
    () =>
      getCapabilityOverview()
        .then((loaded) => {
          setOverview(loaded)
          setOverviewError(null)
        })
        .catch((err: unknown) =>
          setOverviewError(
            err instanceof Error ? err.message : 'Die Freigaben konnten nicht geladen werden.',
          ),
        ),
    [],
  )

  useEffect(() => {
    let cancelled = false
    void listConnectionProfiles()
      .then((loaded) => {
        if (cancelled) return
        setProfiles(loaded)
        setError(null)
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Zugänge konnten nicht geladen werden.')
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    void listConnectorTypeStates()
      .then((loaded) => {
        if (cancelled) return
        setTypeStates(loaded)
        setTypeError(null)
      })
      .catch((err) => {
        if (!cancelled) {
          setTypeError(
            err instanceof Error ? err.message : 'Quellarten konnten nicht geladen werden.',
          )
        }
      })
    void loadOverview()
    return () => {
      cancelled = true
    }
  }, [reloadKey, loadOverview])

  const displayNameOf = (type: string) =>
    sourceTypes.find((descriptor) => descriptor.type === type)?.displayName ?? type

  const typeLocked = (sourceType: string) =>
    (typeStates ?? []).some((state) => state.sourceType === sourceType && state.locked)

  function openPanel(profileId: string | null) {
    const next = new URLSearchParams(searchParams)
    if (profileId) next.set(PROFILE_PARAM, profileId)
    else next.delete(PROFILE_PARAM)
    setSearchParams(next, { replace: true, preventScrollReset: true })
  }

  async function handleDisconnect(profile: ConnectionProfileResponse) {
    try {
      const impact = await getConnectionProfileImpact(profile.id)
      const confirmed = await confirmAction({
        question: `Alle Verbindungen von „${profile.name}“ trennen?`,
        consequence: signsInItself(profile)
          ? `Betroffen: ${connectionCount(impact.connections)}${accountsClause(profile, impact)}. ${profile.authMethod === 'SERVICE_ACCOUNT_KEY' ? 'Der Dienstkonto-Schlüssel' : 'Das Client-Secret'} des Zugangs wird unwiderruflich gelöscht. Die Bibliotheken laufen erst wieder, wenn die Systemverwaltung ${profile.authMethod === 'SERVICE_ACCOUNT_KEY' ? 'einen Schlüssel' : 'ein Secret'} am Zugang neu hinterlegt. Beim Anbieter widerruft OPAA nichts; das geschieht dort.${personsNotice(profile)}`
          : `Betroffen: ${connectionCount(impact.connections)}${accountsClause(profile, impact)}. Alle Zugangsdaten werden sofort verworfen. Der Zugang bleibt bestehen; die Bibliotheken laufen erst wieder, wenn ihre Zugangsdaten neu eingetragen sind.${personsNotice(profile)}`,
        confirmLabel: 'Alle trennen',
        tone: 'danger',
      })
      if (!confirmed) return
      const result = await disconnectAllConnections(profile.id)
      notify(`${connectionCount(result.connections)} getrennt.`, 'success')
      reload()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Trennen fehlgeschlagen.', 'error')
    }
  }

  async function handleLock(profile: ConnectionProfileResponse) {
    const lock = !profile.locked
    const remaining =
      !lock && typeLocked(profile.sourceType)
        ? `Die Quellart „${displayNameOf(profile.sourceType)}“ bleibt gesperrt: Die Bibliotheken des Zugangs laufen erst weiter, wenn auch ihre Sperre aufgehoben ist.`
        : undefined
    if (!(await confirmLock(`den Zugang „${profile.name}“`, lock, remaining))) return
    try {
      await lockConnectionProfile(profile.id, lock)
      notify(
        lock
          ? `„${profile.name}“ ist gesperrt.`
          : `Die Sperre von „${profile.name}“ ist aufgehoben.`,
        'success',
      )
      reload()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Die Sperre ließ sich nicht ändern.', 'error')
    }
  }

  async function handleTestSignIn(profile: ConnectionProfileResponse) {
    try {
      const result = await testConnectionProfileSignIn(profile.id)
      notify(result.message, result.success ? 'success' : 'error')
      reload()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Der Anmeldetest ist fehlgeschlagen.', 'error')
    }
  }

  async function handleDelete(profile: ConnectionProfileResponse): Promise<boolean> {
    try {
      const impact = await getConnectionProfileImpact(profile.id)
      const confirmed = await confirmAction({
        question: `Zugang „${profile.name}“ löschen?`,
        consequence: `Betroffen: ${connectionCount(impact.connections)}${accountsClause(profile, impact)}; sie werden getrennt. Die Bibliotheken bleiben mit ihrem Inhalt und dem Hinweis „Zugang entfernt“ stehen und laufen nicht mehr, bis sie einem anderen Zugang zugeordnet sind.`,
        confirmLabel: 'Löschen',
        tone: 'danger',
      })
      if (!confirmed) return false
      await deleteConnectionProfile(profile.id)
      notify(`„${profile.name}“ wurde gelöscht.`, 'success')
      reload()
      return true
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Löschen fehlgeschlagen.', 'error')
      return false
    }
  }

  const entries: ProfileEntry[] = profiles.map((profile) => {
    const access = accessOf(overview, profile)
    return {
      profile,
      sourceTypeName: displayNameOf(profile.sourceType),
      typeLocked: typeLocked(profile.sourceType),
      access,
      attention: attentionOf(profile, access),
    }
  })
  const unknown = [
    overviewError !== null && 'wer die Zugänge nutzen darf',
    typeError !== null && 'Sperren der Quellarten',
    dormantFailed && 'Bibliotheken mit ruhender Quelle',
  ].filter((part): part is string => Boolean(part))
  const summary = summaryOf(
    entries.map((entry) => entry.attention),
    dormant ?? 0,
    unknown,
  )
  // The line speaks only once every part has answered: a part still loading is neither good nor
  // failed, so summaryOf could only call it good too early.
  const settled =
    (overview !== null || overviewError !== null) &&
    (typeStates !== null || typeError !== null) &&
    (dormant !== null || dormantFailed)
  const opened = entries.find((entry) => entry.profile.id === openId) ?? null

  return (
    <>
      <Box
        sx={{
          display: 'flex',
          gap: 2,
          rowGap: 1.5,
          alignItems: 'flex-start',
          flexWrap: { xs: 'wrap', md: 'nowrap' },
          mb: 3,
        }}
      >
        <Box sx={{ flex: '1 1 320px', minWidth: 0 }}>
          {loading || error || !settled ? null : (
            <StatusLine
              component="h2"
              label="Handlungsbedarf der Zugänge"
              tone={summary.tone}
              headline={summary.headline}
              detail={
                profiles.length === 0
                  ? 'Mit einem Zugang meldet sich OPAA bei einem anderen System an und liest dort Inhalte.'
                  : summary.detail
              }
            />
          )}
        </Box>
        <Button
          variant="contained"
          disabled={!anyTypeAdmitsProfiles}
          onClick={() => setForm({ open: true, profile: null })}
          sx={{ flex: 'none' }}
        >
          Neuer Zugang
        </Button>
      </Box>

      {sourceTypesLoaded && !anyTypeAdmitsProfiles && (
        <Alert severity="info" sx={{ mb: 2 }} data-testid="no-profile-source-type">
          Zugänge stehen zur Verfügung, sobald eine Quellart sie unterstützt. In dieser Version
          bietet noch keine Quellart Zugänge an.
        </Alert>
      )}
      {error && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={reload}>
              Erneut laden
            </Button>
          }
        >
          {error}
        </Alert>
      )}
      {typeError && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {`Der Zustand der Quellarten konnte nicht geladen werden (${typeError}); eine Sperre der Quellart fehlt deshalb in der Liste.`}
        </Alert>
      )}
      {overviewError && (
        <Alert
          severity="warning"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={() => void loadOverview()}>
              Erneut laden
            </Button>
          }
        >
          {`Wer die Zugänge nutzen darf, konnte nicht geladen werden (${overviewError}).`}
        </Alert>
      )}

      {loading ? (
        <ListLoading label="Zugänge werden geladen" />
      ) : profiles.length === 0 && !error ? (
        <ListEmptyState
          title="Es sind noch keine Zugänge angelegt."
          hint="Ein Zugang legt fest, wie OPAA sich bei einem anderen System anmeldet, etwa bei der Nextcloud des Hauses."
        />
      ) : (
        <ConnectionProfileOverview entries={entries} onOpen={openPanel} />
      )}

      <Box sx={{ mt: 4 }}>
        <DormantSourceConnectionsSection
          onLoaded={(count) => {
            setDormant(count)
            setDormantFailed(false)
          }}
          onFailed={() => setDormantFailed(true)}
        />
      </Box>

      <ConnectionProfilePanel
        profile={opened?.profile ?? null}
        sourceTypeName={opened?.sourceTypeName ?? ''}
        typeLocked={opened?.typeLocked ?? false}
        attention={opened?.attention ?? null}
        accessEntry={opened && overview ? profileAccessEntry(overview, opened.profile) : null}
        accessError={overviewError}
        onRetryAccess={() => void loadOverview()}
        onClose={() => openPanel(null)}
        onEdit={(profile) => setForm({ open: true, profile })}
        onTestSignIn={handleTestSignIn}
        onLock={handleLock}
        onDisconnect={handleDisconnect}
        onDelete={handleDelete}
        onAccessChanged={loadOverview}
      />

      <ConnectionProfileFormDialog
        key={form.profile?.id ?? (form.open ? 'new-open' : 'new')}
        open={form.open}
        profile={form.profile}
        sourceTypes={sourceTypes}
        sourceTypesLoaded={sourceTypesLoaded}
        onClose={() => setForm({ open: false, profile: null })}
        onSaved={(saved) => {
          notify(`„${saved.name}“ wurde gespeichert.`, 'success')
          reload()
          // A new profile opens in its panel, where its release is the next step.
          if (!form.profile) openPanel(saved.id)
        }}
      />
    </>
  )
}
