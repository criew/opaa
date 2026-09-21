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
  GroupListResponse,
  UserSummary,
} from '../types/api'
import { getGroups } from '../services/api'
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
import { groupOriginLabel } from '../components/groups/groupOriginLabels'
import { contentWidth } from '../theme/tokens'

function CapabilityCard({
  overview,
  groups,
  onChanged,
}: {
  overview: CapabilityOverviewResponse
  groups: GroupListResponse[]
  onChanged: () => Promise<void>
}) {
  const [subjectType, setSubjectType] = useState<CapabilitySubjectType>('GROUP')
  const [groupId, setGroupId] = useState('')
  const [user, setUser] = useState<UserSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  const capability: Capability = overview.capability
  const subjectId =
    subjectType === 'GROUP' ? groupId : subjectType === 'USER' ? user?.id : undefined
  const ready = subjectType === 'ALL_ACCOUNTS' || Boolean(subjectId)

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
      aria-label={overview.label}
      sx={{ borderBottom: 1, borderColor: 'divider', pb: 2 }}
    >
      <Typography component="h3" sx={{ fontSize: 14.5, fontWeight: 600 }}>
        {overview.label}
      </Typography>
      {/* Die Klartextzeile des ausgelieferten Stands (ADR-0036, Entscheidung 5) — eine
          Anzeigezeile, kein Einrichtungsassistent. */}
      <Typography sx={{ fontSize: 13.5, mt: 0.5 }}>{overview.statement}</Typography>

      {error && (
        <Alert severity="error" sx={{ mt: 1.5 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Divider sx={{ my: 1.5 }} />

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
              onClick={async () => {
                const confirmed = await confirmAction({
                  question: `„${overview.label}" entziehen?`,
                  consequence:
                    grant.subjectType === 'ALL_ACCOUNTS'
                      ? 'Der Entzug von „Alle Konten" wirkt sofort und für jedes Konto der Organisation. Er wird als Governance-Ereignis protokolliert.'
                      : 'Der Entzug wirkt ohne Neuanmeldung und wird protokolliert.',
                  confirmLabel: 'Entziehen',
                  tone: 'danger',
                })
                if (!confirmed) return
                await run(
                  () => revokeCapability(capability, grant.id),
                  'Das Anlegerecht konnte nicht entzogen werden.',
                )
              }}
            >
              Entziehen
            </Button>
          </Box>
        ))}
      </Stack>

      <Divider sx={{ my: 1.5 }} />

      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ alignItems: 'flex-start' }}>
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
          <TextField
            select
            size="small"
            label="Gruppe"
            value={groupId}
            onChange={(e) => setGroupId(e.target.value)}
            sx={{ minWidth: 280 }}
          >
            {groups
              .filter((group) => !group.dissolved)
              .map((group) => (
                <MenuItem key={group.id} value={group.id}>
                  {group.name} · {groupOriginLabel(group)}
                </MenuItem>
              ))}
          </TextField>
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
          onClick={() =>
            void run(async () => {
              await grantCapability(capability, { subjectType, subjectId })
              setUser(null)
              setGroupId('')
              notify(`„${overview.label}" wurde erteilt.`, 'success')
            }, 'Das Anlegerecht konnte nicht erteilt werden.')
          }
        >
          Erteilen
        </Button>
      </Stack>
    </Box>
  )
}

/**
 * Die Anlegerechte der Installation (#1821, ADR-0036 Entscheidung 5): je Recht der Stand als
 * Klartextzeile, die berechtigten Subjekte und die Vergabe an Person, Gruppe oder „Alle Konten".
 */
export default function CapabilityManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const [overviews, setOverviews] = useState<CapabilityOverviewResponse[]>([])
  const [groups, setGroups] = useState<GroupListResponse[]>([])
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
    void getGroups()
      .then(setGroups)
      .catch(() => setGroups([]))
  }, [isSystemAdmin, load])

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
          meta={overviews.length === 1 ? '1 Anlegerecht' : `${overviews.length} Anlegerechte`}
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
            {overviews.map((overview) => (
              <CapabilityCard
                key={overview.capability}
                overview={overview}
                groups={groups}
                onChanged={load}
              />
            ))}
          </Stack>
        )}
      </Box>
    </Box>
  )
}
