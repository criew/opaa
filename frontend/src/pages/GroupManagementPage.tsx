import { useCallback, useEffect, useMemo, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Switch from '@mui/material/Switch'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import type { GroupEffectsResponse, GroupListResponse, UserInfo } from '../types/api'
import { getUsers } from '../services/api'
import { getGroupEffects } from '../services/permissionTransferApi'
import { confirmAction } from '../stores/confirmStore'
import { useAuthStore } from '../stores/authStore'
import { useGroupStore } from '../stores/groupStore'
import { groupKindLabel } from '../utils/labels'
import CreateGroupDialog from '../components/CreateGroupDialog'
import GroupStewardsSection from '../components/groups/GroupStewardsSection'
import PermissionTransferDialog from '../components/permissions/PermissionTransferDialog'
import FieldLabel from '../components/wizard/FieldLabel'
import MetaBadge from '../components/MetaBadge'
import AreaPageHeader from '../components/AreaPageHeader'
import PendingPlanNotice from '../components/admin/directorysync/PendingPlanNotice'
import {
  groupIneffectiveReason,
  groupMechanismLabel,
  groupOriginLabel,
} from '../components/groups/groupOriginLabels'
import { contentWidth } from '../theme/tokens'

/** „alle" oder „intern" oder die ID eines Anbieters — der Filter nach Herkunft (ADR-0036/2). */
type OriginFilter = string

function GroupCard({
  group,
  effects,
  onTransferred,
}: {
  group: GroupListResponse
  effects: GroupEffectsResponse | undefined
  onTransferred: () => void
}) {
  const currentUserId = useAuthStore((s) => s.user?.id)
  const details = useGroupStore((s) => s.groupDetails[group.id])
  const loadGroupDetails = useGroupStore((s) => s.loadGroupDetails)
  const renameGroup = useGroupStore((s) => s.renameGroup)
  const deleteExistingGroup = useGroupStore((s) => s.deleteExistingGroup)
  const addMember = useGroupStore((s) => s.addMember)
  const removeMember = useGroupStore((s) => s.removeMember)
  const changeRelease = useGroupStore((s) => s.changeRelease)

  const [expanded, setExpanded] = useState(false)
  const [membersRequested, setMembersRequested] = useState(false)
  const [transferOpen, setTransferOpen] = useState(false)
  const [draft, setDraft] = useState<{ groupId: string | null; name: string; description: string }>(
    { groupId: null, name: '', description: '' },
  )
  const [selectedUser, setSelectedUser] = useState<UserInfo | null>(null)
  const [allUsers, setAllUsers] = useState<UserInfo[]>([])
  const [localError, setLocalError] = useState<string | null>(null)

  const isAdHoc = group.kind === 'AD_HOC'
  const name = draft.groupId === group.id ? draft.name : group.name
  const description = draft.groupId === group.id ? draft.description : (group.description ?? '')
  const ineffective = groupIneffectiveReason(group)

  // Der Abruf der Mitgliederliste durch die Systemverwaltung ist ein Audit-Ereignis (ADR-0036,
  // Entscheidung 4/9) - er geschieht deshalb erst auf ausdrücklichen Wunsch, nicht beim Aufklappen.
  useEffect(() => {
    if (membersRequested && !details) {
      void loadGroupDetails(group.id)
    }
  }, [membersRequested, details, group.id, loadGroupDetails])

  useEffect(() => {
    if (membersRequested) {
      void getUsers()
        .then(setAllUsers)
        .catch(() => setAllUsers([]))
    }
  }, [membersRequested])

  const availableUsers = useMemo(() => {
    const memberIds = new Set(details?.members.map((m) => m.userId) ?? [])
    return allUsers.filter((u) => !memberIds.has(u.id))
  }, [allUsers, details?.members])

  return (
    <Accordion expanded={expanded} onChange={(_event, isExpanded) => setExpanded(isExpanded)}>
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Box sx={{ flexGrow: 1 }}>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <Typography sx={{ fontSize: 14.5, fontWeight: 600 }}>{group.name}</Typography>
            <MetaBadge>{groupKindLabel(group.kind)}</MetaBadge>
            {group.provider?.external && <MetaBadge accent>extern</MetaBadge>}
            {group.dissolved && <MetaBadge>aufgelöst</MetaBadge>}
            {group.provider && !group.provider.enabled && <MetaBadge>Anbieter aus</MetaBadge>}
            {group.protectedGroup && <MetaBadge>geschützt</MetaBadge>}
            {group.kind === 'AD_HOC' && !group.releasedForUse && (
              <MetaBadge>nicht freigegeben</MetaBadge>
            )}
            <Typography sx={{ fontSize: 13, color: 'text.secondary', ml: 'auto', mr: 1 }}>
              {group.memberCount} {group.memberCount === 1 ? 'Mitglied' : 'Mitglieder'}
            </Typography>
          </Stack>
          {/* Die Herkunft steht ohne Aufklappen da (ADR-0036, Entscheidung 2): Anbieter,
              Kennzeichen, Quellpfad und der Mechanismus, der die Gruppe pflegt. */}
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.25 }}>
            Herkunft: {groupOriginLabel(group)}
            {group.sourcePath ? ` · ${group.sourcePath}` : ''}
            {group.provider ? ` · ${groupMechanismLabel(group.provider.groupMechanism)}` : ''}
            {effects && effects.summary !== '' ? ` · wirkt: ${effects.summary}` : ''}
            {effects && effects.summary === '' ? ' · ohne Wirkung' : ''}
          </Typography>
        </Box>
      </AccordionSummary>
      <AccordionDetails>
        {localError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setLocalError(null)}>
            {localError}
          </Alert>
        )}
        {ineffective && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            {ineffective}
          </Alert>
        )}
        {group.kind === 'ORG_UNIT' && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Organisationseinheiten werden aus dem Verzeichnis synchronisiert und können hier nicht
            bearbeitet werden.
          </Alert>
        )}
        {group.kind === 'IDENTITY_PROVIDER' && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Diese Gruppe stammt aus dem Identitätsanbieter und wird bei jeder Anmeldung abgeglichen;
            sie kann hier nicht bearbeitet werden.
          </Alert>
        )}

        <Stack spacing={2} sx={{ mb: 2 }}>
          <Box>
            <FieldLabel htmlFor={`group-${group.id}-name`}>Name der Gruppe</FieldLabel>
            <TextField
              id={`group-${group.id}-name`}
              fullWidth
              value={name}
              onChange={(e) => setDraft({ groupId: group.id, name: e.target.value, description })}
              disabled={!isAdHoc}
              size="small"
            />
          </Box>
          <Box>
            <FieldLabel htmlFor={`group-${group.id}-description`}>Beschreibung</FieldLabel>
            <TextField
              id={`group-${group.id}-description`}
              fullWidth
              value={description}
              onChange={(e) => setDraft({ groupId: group.id, name, description: e.target.value })}
              multiline
              minRows={2}
              disabled={!isAdHoc}
              size="small"
            />
          </Box>
          <Stack direction="row" spacing={1}>
            {isAdHoc && (
              <>
                <Button
                  variant="contained"
                  size="small"
                  onClick={async () => {
                    setLocalError(null)
                    try {
                      await renameGroup(group.id, name.trim(), description.trim())
                    } catch (err) {
                      setLocalError(
                        err instanceof Error ? err.message : 'Aktualisierung fehlgeschlagen',
                      )
                    }
                  }}
                >
                  Speichern
                </Button>
                <Button
                  color="error"
                  variant="outlined"
                  size="small"
                  onClick={async () => {
                    const confirmed = await confirmAction({
                      question: `Gruppe "${group.name}" löschen?`,
                      consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
                      confirmLabel: 'Löschen',
                      tone: 'danger',
                    })
                    if (!confirmed) {
                      return
                    }
                    setLocalError(null)
                    try {
                      await deleteExistingGroup(group.id)
                    } catch (err) {
                      setLocalError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
                    }
                  }}
                >
                  Gruppe löschen
                </Button>
              </>
            )}
            <Button variant="outlined" size="small" onClick={() => setTransferOpen(true)}>
              Wirkungen übertragen
            </Button>
          </Stack>
        </Stack>

        {isAdHoc && (
          <>
            <Divider sx={{ mb: 2 }} />
            <FormControlLabel
              control={
                <Switch
                  checked={group.releasedForUse}
                  onChange={async (e) => {
                    setLocalError(null)
                    try {
                      await changeRelease(group.id, e.target.checked)
                    } catch (err) {
                      setLocalError(
                        err instanceof Error
                          ? err.message
                          : 'Die Freigabe konnte nicht geändert werden',
                      )
                    }
                  }}
                />
              }
              label="Zur Verwendung freigeben"
            />
            <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 2 }}>
              Erst freigegeben ist die Gruppe für andere Rechtevergebende wählbar. Das
              Schutzkennzeichen setzen und lösen die Verantwortlichen der Gruppe selbst — die
              Systemverwaltung kann es nicht; bei einer geschützten Gruppe gilt das auch für die
              Freigabe.
            </Typography>

            <Divider sx={{ mb: 2 }} />
            <GroupStewardsSection
              groupId={group.id}
              groupName={group.name}
              stewards={details?.stewards ?? group.stewards}
              currentUserId={currentUserId}
            />
          </>
        )}

        <Divider sx={{ my: 2 }} />

        <Typography
          component="h3"
          sx={{
            fontFamily: 'monospace',
            fontSize: 10,
            fontWeight: 500,
            letterSpacing: '0.08em',
            textTransform: 'uppercase',
            color: 'text.secondary',
            mb: 1,
          }}
        >
          Mitglieder
        </Typography>

        {!membersRequested ? (
          <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              Der Abruf der Mitgliederliste durch die Systemverwaltung ist ein Audit-Ereignis. Diese
              Seite lädt sie deshalb erst auf ausdrücklichen Wunsch. Die Zahl der Mitglieder steht
              oben — sie ist keine Liste.
            </Typography>
            <Button size="small" variant="outlined" onClick={() => setMembersRequested(true)}>
              Mitglieder anzeigen
            </Button>
          </Stack>
        ) : (
          <Stack spacing={1}>
            {(details?.members ?? []).map((member) => (
              <Box
                key={member.userId}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 1,
                }}
              >
                <Typography
                  variant="body2"
                  sx={member.displayName ? undefined : { fontFamily: 'monospace' }}
                >
                  {member.displayName ?? member.userId}
                </Typography>
                {isAdHoc && (
                  <Button
                    color="error"
                    size="small"
                    onClick={async () => {
                      setLocalError(null)
                      try {
                        await removeMember(group.id, member.userId)
                      } catch (err) {
                        setLocalError(
                          err instanceof Error
                            ? err.message
                            : 'Entfernen des Mitglieds fehlgeschlagen',
                        )
                      }
                    }}
                  >
                    Entfernen
                  </Button>
                )}
              </Box>
            ))}

            {isAdHoc && (
              <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ pt: 1 }}>
                <Autocomplete
                  options={availableUsers}
                  getOptionLabel={(option) =>
                    option.displayName
                      ? `${option.displayName} (${option.email ?? option.id})`
                      : (option.email ?? option.id)
                  }
                  noOptionsText="Keine Treffer"
                  value={selectedUser}
                  onChange={(_event, value) => setSelectedUser(value)}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      placeholder="Benutzer suchen …"
                      slotProps={{
                        ...params.slotProps,
                        htmlInput: { ...params.slotProps.htmlInput, 'aria-label': 'Benutzer' },
                      }}
                    />
                  )}
                  isOptionEqualToValue={(option, value) => option.id === value.id}
                  size="small"
                  sx={{ minWidth: 280 }}
                />
                <Button
                  variant="contained"
                  size="small"
                  disabled={!selectedUser}
                  onClick={async () => {
                    if (!selectedUser) return
                    setLocalError(null)
                    try {
                      await addMember(group.id, selectedUser.id)
                      setSelectedUser(null)
                    } catch (err) {
                      setLocalError(
                        err instanceof Error
                          ? err.message
                          : 'Mitglied konnte nicht hinzugefügt werden',
                      )
                    }
                  }}
                >
                  Mitglied hinzufügen
                </Button>
              </Stack>
            )}
          </Stack>
        )}

        {transferOpen && (
          <PermissionTransferDialog
            open
            onClose={() => setTransferOpen(false)}
            source={{ type: 'GROUP', id: group.id, name: group.name }}
            targetKinds={['GROUP']}
            scopes={['ASSET_GRANTS', 'SPACE_MEMBERSHIPS', 'CAPABILITIES', 'OWNERSHIP']}
            intro={`Die gewählten Wirkungen von „${group.name}" gehen in einem Vorgang an die Zielgruppe. Mitgliedschaften der Gruppe werden dabei nicht verschoben.`}
            onTransferred={onTransferred}
          />
        )}
      </AccordionDetails>
    </Accordion>
  )
}

/**
 * Die Gruppenverwaltung (#1821): Herkunft ohne Aufklappen, Filter nach Herkunft, die Wirkung je
 * Gruppe und der Ausgang „übertragen". Die Mitgliederliste lädt erst auf Wunsch — ihr Abruf durch
 * die Systemverwaltung ist ein Audit-Ereignis (ADR-0036, Entscheidungen 2, 4 und 9).
 */
export default function GroupManagementPage() {
  const groups = useGroupStore((s) => s.groups)
  const isLoading = useGroupStore((s) => s.isLoading)
  const error = useGroupStore((s) => s.error)
  const loadGroups = useGroupStore((s) => s.loadGroups)
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const [originFilter, setOriginFilter] = useState<OriginFilter>('ALL')
  const [effects, setEffects] = useState<GroupEffectsResponse[]>([])

  const loadEffects = useCallback(() => {
    void getGroupEffects()
      .then(setEffects)
      .catch(() => setEffects([]))
  }, [])

  useEffect(() => {
    void loadGroups()
    loadEffects()
  }, [loadGroups, loadEffects])

  const providers = useMemo(() => {
    const byId = new Map<string, string>()
    groups.forEach((group) => {
      if (group.provider) byId.set(group.provider.id, group.provider.displayName)
    })
    return [...byId.entries()]
  }, [groups])

  const visible = useMemo(() => {
    if (originFilter === 'ALL') return groups
    if (originFilter === 'INTERNAL') return groups.filter((group) => group.origin === 'INTERNAL')
    return groups.filter((group) => group.provider?.id === originFilter)
  }, [groups, originFilter])

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={GroupsOutlinedIcon}
          title="Gruppen"
          meta={visible.length === 1 ? '1 Gruppe' : `${visible.length} Gruppen`}
          description="Gilt für die gesamte Anwendung. Änderungen wirken sich auf alle Spaces und Benutzer aus. Gruppen tragen Eigentum und Freigaben."
          action={
            <Button variant="contained" onClick={() => setCreateDialogOpen(true)}>
              Neue Gruppe
            </Button>
          }
        />

        <PendingPlanNotice />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <TextField
          select
          size="small"
          label="Herkunft"
          value={originFilter}
          onChange={(e) => setOriginFilter(e.target.value)}
          sx={{ minWidth: 260, mb: 2 }}
        >
          <MenuItem value="ALL">Alle Gruppen</MenuItem>
          <MenuItem value="INTERNAL">Intern</MenuItem>
          {providers.map(([id, displayName]) => (
            <MenuItem key={id} value={id}>
              {displayName}
            </MenuItem>
          ))}
        </TextField>

        {isLoading ? (
          <Typography sx={{ color: 'text.secondary' }}>Gruppen werden geladen …</Typography>
        ) : visible.length === 0 ? (
          <Typography sx={{ color: 'text.secondary' }}>
            Es sind keine Gruppen dieser Herkunft vorhanden.
          </Typography>
        ) : (
          <Stack spacing={1}>
            {visible.map((group) => (
              <GroupCard
                key={group.id}
                group={group}
                effects={effects.find((entry) => entry.groupId === group.id)}
                onTransferred={() => {
                  void loadGroups()
                  loadEffects()
                }}
              />
            ))}
          </Stack>
        )}

        <CreateGroupDialog
          open={createDialogOpen}
          onClose={() => setCreateDialogOpen(false)}
          onCreated={() => setCreateDialogOpen(false)}
        />
      </Box>
    </Box>
  )
}
