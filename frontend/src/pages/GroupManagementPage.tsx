import { useEffect, useMemo, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { GroupListResponse, UserInfo } from '../types/api'
import { getUsers } from '../services/api'
import { useGroupStore } from '../stores/groupStore'
import { groupKindLabel } from '../utils/labels'
import CreateGroupDialog from '../components/CreateGroupDialog'
import PageHeading from '../components/a11y/PageHeading'

function GroupCard({ group }: { group: GroupListResponse }) {
  const details = useGroupStore((s) => s.groupDetails[group.id])
  const loadGroupDetails = useGroupStore((s) => s.loadGroupDetails)
  const renameGroup = useGroupStore((s) => s.renameGroup)
  const deleteExistingGroup = useGroupStore((s) => s.deleteExistingGroup)
  const addMember = useGroupStore((s) => s.addMember)
  const removeMember = useGroupStore((s) => s.removeMember)

  const [expanded, setExpanded] = useState(false)
  const [draft, setDraft] = useState<{ groupId: string | null; name: string; description: string }>(
    { groupId: null, name: '', description: '' },
  )
  const [selectedUser, setSelectedUser] = useState<UserInfo | null>(null)
  const [allUsers, setAllUsers] = useState<UserInfo[]>([])
  const [localError, setLocalError] = useState<string | null>(null)

  const isAdHoc = group.kind === 'AD_HOC'
  const name = draft.groupId === group.id ? draft.name : group.name
  const description = draft.groupId === group.id ? draft.description : (group.description ?? '')

  useEffect(() => {
    if (expanded && !details) {
      void loadGroupDetails(group.id)
    }
  }, [expanded, details, group.id, loadGroupDetails])

  useEffect(() => {
    if (expanded) {
      void getUsers()
        .then(setAllUsers)
        .catch(() => setAllUsers([]))
    }
  }, [expanded])

  const availableUsers = useMemo(() => {
    const memberIds = new Set(details?.members.map((m) => m.userId) ?? [])
    return allUsers.filter((u) => !memberIds.has(u.id))
  }, [allUsers, details?.members])

  return (
    <Accordion
      expanded={expanded}
      onChange={(_event, isExpanded) => setExpanded(isExpanded)}
      variant="outlined"
      disableGutters
    >
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexGrow: 1 }}>
          <Typography sx={{ fontWeight: 600 }}>{group.name}</Typography>
          <Chip label={groupKindLabel(group.kind)} size="small" variant="outlined" />
          <Typography variant="body2" color="text.secondary" sx={{ ml: 'auto', mr: 1 }}>
            {group.memberCount} {group.memberCount === 1 ? 'Mitglied' : 'Mitglieder'}
          </Typography>
        </Stack>
      </AccordionSummary>
      <AccordionDetails>
        {localError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setLocalError(null)}>
            {localError}
          </Alert>
        )}
        {!isAdHoc && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Organisationseinheiten werden aus dem Verzeichnis synchronisiert und können hier nicht
            bearbeitet werden.
          </Alert>
        )}

        <Stack spacing={1.5} sx={{ mb: 2 }}>
          <TextField
            label="Name der Gruppe"
            value={name}
            onChange={(e) => setDraft({ groupId: group.id, name: e.target.value, description })}
            disabled={!isAdHoc}
            size="small"
          />
          <TextField
            label="Beschreibung"
            value={description}
            onChange={(e) => setDraft({ groupId: group.id, name, description: e.target.value })}
            multiline
            minRows={2}
            disabled={!isAdHoc}
            size="small"
          />
          {isAdHoc && (
            <Stack direction="row" spacing={1}>
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
                  if (
                    !window.confirm(
                      `Gruppe "${group.name}" löschen? Diese Aktion kann nicht rückgängig gemacht werden.`,
                    )
                  ) {
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
            </Stack>
          )}
        </Stack>

        <Divider sx={{ mb: 2 }} />

        <Typography variant="subtitle2" gutterBottom>
          Mitglieder
        </Typography>
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
                value={selectedUser}
                onChange={(_event, value) => setSelectedUser(value)}
                renderInput={(params) => (
                  <TextField {...params} label="Benutzer" placeholder="Benutzer suchen …" />
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
      </AccordionDetails>
    </Accordion>
  )
}

export default function GroupManagementPage() {
  const groups = useGroupStore((s) => s.groups)
  const isLoading = useGroupStore((s) => s.isLoading)
  const error = useGroupStore((s) => s.error)
  const loadGroups = useGroupStore((s) => s.loadGroups)
  const [createDialogOpen, setCreateDialogOpen] = useState(false)

  useEffect(() => {
    void loadGroups()
  }, [loadGroups])

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, overflowY: 'auto' }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <PageHeading title="Gruppen" variant="h6" />
        <Button variant="contained" onClick={() => setCreateDialogOpen(true)}>
          Neue Gruppe
        </Button>
      </Stack>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {isLoading ? (
        <Typography color="text.secondary">Gruppen werden geladen …</Typography>
      ) : groups.length === 0 ? (
        <Typography color="text.secondary">Es sind noch keine Gruppen vorhanden.</Typography>
      ) : (
        <Stack spacing={1}>
          {groups.map((group) => (
            <GroupCard key={group.id} group={group} />
          ))}
        </Stack>
      )}

      <CreateGroupDialog
        open={createDialogOpen}
        onClose={() => setCreateDialogOpen(false)}
        onCreated={() => setCreateDialogOpen(false)}
      />
    </Box>
  )
}
