import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import KeyOutlinedIcon from '@mui/icons-material/KeyOutlined'
import type {
  Capability,
  CapabilityOverviewResponse,
  CapabilitySubjectType,
  SelectableGroupResponse,
  UserSummary,
} from '../types/api'
import {
  getCapabilityOverview,
  grantCapability,
  revokeCapability,
} from '../services/capabilityAdminApi'
import { useAuthStore } from '../stores/authStore'
import { confirmAction } from '../stores/confirmStore'
import { notify } from '../stores/notificationStore'
import AreaPageHeader from '../components/AreaPageHeader'
import PageHeading from '../components/a11y/PageHeading'
import MetaBadge from '../components/MetaBadge'
import UserPicker from '../components/groups/UserPicker'
import GroupPicker from '../components/permissions/GroupPicker'
import {
  confirmGroupSubject,
  PROTECTED_GROUP_SEARCH_HINT,
} from '../components/permissions/subjectSelection'
import { contentWidth } from '../theme/tokens'

/** Die Subjekte, die ein Anlegerecht (in einem Geltungsbereich) halten, je mit „Entziehen". */
function GrantList({
  overview,
  run,
}: {
  overview: CapabilityOverviewResponse
  run: (action: () => Promise<void>, fallback: string) => Promise<void>
}) {
  const subject = overview.scopeLabel
    ? `„${overview.label}" für ${overview.scopeLabel}`
    : `„${overview.label}"`
  return (
    <Stack spacing={1}>
      {overview.grants.length === 0 && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
          Niemand hält dieses Anlegerecht.
        </Typography>
      )}
      {overview.grants.map((grant) => (
        <Box
          key={grant.id}
          sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}
        >
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography sx={{ fontSize: 13.5 }}>
              {grant.subjectType === 'ALL_ACCOUNTS'
                ? 'Alle Konten'
                : (grant.subjectName ?? grant.subjectId)}
            </Typography>
            <MetaBadge>
              {grant.subjectType === 'ALL_ACCOUNTS'
                ? 'alle'
                : grant.subjectType === 'GROUP'
                  ? 'Gruppe'
                  : 'Person'}
            </MetaBadge>
          </Stack>
          <Button
            size="small"
            color="error"
            aria-label={`${subject} entziehen: ${
              grant.subjectType === 'ALL_ACCOUNTS'
                ? 'Alle Konten'
                : (grant.subjectName ?? grant.subjectId)
            }`}
            onClick={async () => {
              const confirmed = await confirmAction({
                question: `${subject} entziehen?`,
                consequence:
                  grant.subjectType === 'ALL_ACCOUNTS'
                    ? 'Der Entzug von „Alle Konten" wirkt sofort und für jedes Konto der Organisation. Er wird als Governance-Ereignis protokolliert.'
                    : 'Der Entzug wirkt ohne Neuanmeldung und wird protokolliert.',
                confirmLabel: 'Entziehen',
                tone: 'danger',
              })
              if (!confirmed) return
              await run(
                () => revokeCapability(overview.capability, grant.id),
                'Das Anlegerecht konnte nicht entzogen werden.',
              )
            }}
          >
            Entziehen
          </Button>
        </Box>
      ))}
    </Stack>
  )
}

/**
 * Ein Anlegerecht. Hat es Geltungsbereiche („Konnektorbibliotheken anlegen" je Quellart und je
 * Zugang), steht je Geltungsbereich eine Klartextzeile mit ihren Subjekten, und die Vergabe fragt
 * den Geltungsbereich ab.
 */
function CapabilityCard({
  entries,
  onChanged,
}: {
  entries: CapabilityOverviewResponse[]
  onChanged: () => Promise<void>
}) {
  const [subjectType, setSubjectType] = useState<CapabilitySubjectType>('GROUP')
  const [group, setGroup] = useState<SelectableGroupResponse | null>(null)
  const [user, setUser] = useState<UserSummary | null>(null)
  const [scope, setScope] = useState<string>(entries[0]?.scope ?? '')
  const [error, setError] = useState<string | null>(null)

  const first = entries[0]
  const capability: Capability = first.capability
  const scoped = Boolean(first.scope)
  const subjectId =
    subjectType === 'GROUP' ? group?.id : subjectType === 'USER' ? user?.id : undefined
  const ready = (subjectType === 'ALL_ACCOUNTS' || Boolean(subjectId)) && (!scoped || scope !== '')
  const scopeLabel = entries.find((entry) => entry.scope === scope)?.scopeLabel

  async function run(action: () => Promise<void>, fallback: string) {
    setError(null)
    try {
      await action()
      await onChanged()
    } catch (err) {
      setError(err instanceof Error ? err.message : fallback)
    }
  }

  return (
    <Box
      component="section"
      aria-label={first.label}
      sx={{ borderBottom: 1, borderColor: 'divider', pb: 2 }}
    >
      <Typography component="h3" sx={{ fontSize: 14.5, fontWeight: 600 }}>
        {first.label}
      </Typography>
      {scoped && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 0.5 }}>
          Erteilt je Quellart (Bibliothek mit eigener Adresse) und je Zugang. Die Freigabe eines
          Zugangs öffnet keinen anderen.
        </Typography>
      )}

      {error && (
        <Alert severity="error" sx={{ mt: 1.5 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {entries.map((entry) => (
        <Box
          key={entry.scope ?? entry.capability}
          component={scoped ? 'section' : 'div'}
          aria-label={entry.scopeLabel ?? undefined}
        >
          {scoped && (
            <Typography component="h4" sx={{ fontSize: 13.5, fontWeight: 600, mt: 1.5 }}>
              {entry.scopeLabel}
            </Typography>
          )}
          {/* Die Klartextzeile des Stands (ADR-0036, Entscheidung 5) — eine Anzeigezeile, kein
              Einrichtungsassistent. */}
          <Typography sx={{ fontSize: 13.5, mt: 0.5 }}>{entry.statement}</Typography>
          <Divider sx={{ my: 1.5 }} />
          <GrantList overview={entry} run={run} />
        </Box>
      ))}

      <Divider sx={{ my: 1.5 }} />

      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ alignItems: 'flex-start' }}>
        {scoped && (
          <TextField
            select
            size="small"
            label="Geltungsbereich"
            value={scope}
            onChange={(e) => setScope(e.target.value)}
            sx={{ minWidth: 220 }}
          >
            {entries.map((entry) => (
              <MenuItem key={entry.scope} value={entry.scope ?? ''}>
                {entry.scopeLabel}
              </MenuItem>
            ))}
          </TextField>
        )}

        <TextField
          select
          size="small"
          label="Empfänger"
          value={subjectType}
          onChange={(e) => setSubjectType(e.target.value as CapabilitySubjectType)}
          sx={{ minWidth: 160 }}
        >
          <MenuItem value="GROUP">Gruppe</MenuItem>
          <MenuItem value="USER">Person</MenuItem>
          <MenuItem value="ALL_ACCOUNTS">Alle Konten</MenuItem>
        </TextField>

        {subjectType === 'GROUP' && (
          /* Die gemeinsame Gruppensuche (#1820): Herkunft, Kennzeichen „extern" und die
             Wählbarkeitsregeln bringt sie mit - dieselben, die `CapabilityService#grant` abweist. */
          <Stack spacing={0.5} sx={{ minWidth: 280, flex: 1 }}>
            <GroupPicker
              ariaLabel="Gruppe"
              placeholder="Gruppe suchen …"
              value={group}
              onChange={setGroup}
            />
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              {PROTECTED_GROUP_SEARCH_HINT}
            </Typography>
          </Stack>
        )}

        {subjectType === 'USER' && (
          <UserPicker
            ariaLabel="Person"
            placeholder="Person suchen …"
            value={user}
            onChange={setUser}
            excludedUserIds={[]}
          />
        )}

        <Button
          variant="contained"
          size="small"
          disabled={!ready}
          onClick={async () => {
            // Zwischenfrage vor einem Recht an eine Gruppe eines externen Anbieters (ADR-0036/2).
            if (subjectType === 'GROUP' && group && !(await confirmGroupSubject(group))) return
            await run(async () => {
              await grantCapability(capability, {
                subjectType,
                subjectId,
                ...(scoped ? { scope } : {}),
              })
              setUser(null)
              setGroup(null)
              notify(
                scoped
                  ? `„${first.label}" für ${scopeLabel} wurde erteilt.`
                  : `„${first.label}" wurde erteilt.`,
                'success',
              )
            }, 'Das Anlegerecht konnte nicht erteilt werden.')
          }}
        >
          Erteilen
        </Button>
      </Stack>
    </Box>
  )
}

/** Die Zeilen eines Anlegerechts beisammen, in der Reihenfolge der Übersicht. */
function groupByCapability(overviews: CapabilityOverviewResponse[]) {
  const groups = new Map<Capability, CapabilityOverviewResponse[]>()
  for (const overview of overviews) {
    groups.set(overview.capability, [...(groups.get(overview.capability) ?? []), overview])
  }
  return [...groups.values()]
}

/**
 * Die Anlegerechte der Installation (#1821, ADR-0036 Entscheidung 5): je Recht der Stand als
 * Klartextzeile, die berechtigten Subjekte und die Vergabe an Person, Gruppe oder „Alle Konten".
 */
export default function CapabilityManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const [overviews, setOverviews] = useState<CapabilityOverviewResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(isSystemAdmin)

  // Kein synchrones setState im Rumpf: Der Effekt unten ruft dieselbe Funktion, und ein synchrones
  // setState von dort erzeugt eine Renderkaskade (react-hooks/set-state-in-effect).
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

  const capabilityCount = groupByCapability(overviews).length

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Anlegerechte" gutterBottom />
        <Alert severity="info">
          Anlegerechte vergibt die Systemverwaltung. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={KeyOutlinedIcon}
          title="Anlegerechte"
          meta={capabilityCount === 1 ? '1 Anlegerecht' : `${capabilityCount} Anlegerechte`}
          description="Gilt für die gesamte Anwendung. Ein Anlegerecht öffnet einen Anlegepfad und nie einen Inhalt. Vergabe und Entzug wirken ohne Neuanmeldung und werden als Governance-Ereignis protokolliert."
        />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {isLoading ? (
          <Typography sx={{ color: 'text.secondary' }}>Anlegerechte werden geladen …</Typography>
        ) : (
          <Stack spacing={2}>
            {groupByCapability(overviews).map((entries) => (
              <CapabilityCard key={entries[0].capability} entries={entries} onChanged={load} />
            ))}
          </Stack>
        )}
      </Box>
    </Box>
  )
}
