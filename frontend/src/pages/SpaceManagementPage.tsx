import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Paper from '@mui/material/Paper'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useNavigate, useParams } from 'react-router'
import type { SpaceRole, SpaceVisibility, UserInfo } from '../types/api'
import { getUsers } from '../services/api'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import { spaceRoleLabel, spaceVisibilityDescription, spaceVisibilityLabel } from '../utils/labels'
import PageHeading from '../components/a11y/PageHeading'

const editableRoles: SpaceRole[] = ['MEMBER', 'CURATOR', 'ADMIN']
// #272: mirrors docs/features/spaces-and-assets.md#space-sichtbarkeit.
const editableVisibilities: SpaceVisibility[] = ['PRIVATE', 'DISCOVERABLE', 'OPEN']

function canManageMembers(role: SpaceRole | undefined): boolean {
  return role === 'ADMIN'
}

export default function SpaceManagementPage() {
  const { spaceId } = useParams()
  const navigate = useNavigate()
  const currentUserId = useAuthStore((s) => s.user?.id)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const selectSpace = useSpaceStore((s) => s.selectSpace)
  const space = useSpaceStore((s) => s.selectedSpace)
  const error = useSpaceStore((s) => s.error)
  const addMember = useSpaceStore((s) => s.addMember)
  const updateMemberRole = useSpaceStore((s) => s.updateMemberRole)
  const removeMember = useSpaceStore((s) => s.removeMember)
  const transferOwnership = useSpaceStore((s) => s.transferOwnership)
  const updateDetails = useSpaceStore((s) => s.updateDetails)
  const deleteSelectedSpace = useSpaceStore((s) => s.deleteSelectedSpace)
  const archiveSelectedSpace = useSpaceStore((s) => s.archiveSelectedSpace)
  const [draft, setDraft] = useState<{
    spaceId: string | null
    name: string
    description: string
    visibility: SpaceVisibility
  }>({
    spaceId: null,
    name: '',
    description: '',
    visibility: 'PRIVATE',
  })
  const [selectedUser, setSelectedUser] = useState<UserInfo | null>(null)
  const [newMemberRole, setNewMemberRole] = useState<SpaceRole>('MEMBER')
  const [localError, setLocalError] = useState<string | null>(null)
  // #543: deleteSpace's 409 - "Der Space enthält noch Chats ... Archivieren Sie den Space
  // stattdessen." - is the one failure this page offers a direct way out of, instead of just
  // showing the message.
  const [deleteBlockedByChats, setDeleteBlockedByChats] = useState(false)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const [allUsers, setAllUsers] = useState<UserInfo[]>([])

  useEffect(() => {
    if (spaceId) {
      void loadSpaces()
      void selectSpace(spaceId)
    }
  }, [loadSpaces, selectSpace, spaceId])

  useEffect(() => {
    void getUsers()
      .then(setAllUsers)
      .catch(() => setAllUsers([]))
  }, [])

  const canManage = useMemo(() => canManageMembers(space?.userRole), [space?.userRole])
  const availableUsers = useMemo(() => {
    const memberIds = new Set(space?.members.map((m) => m.userId) ?? [])
    return allUsers.filter((u) => !memberIds.has(u.id))
  }, [allUsers, space?.members])
  const isOwner = Boolean(currentUserId) && space?.ownerId === currentUserId
  const activeSpaceId = space?.id ?? null
  const name = draft.spaceId === activeSpaceId ? draft.name : (space?.name ?? '')
  const description =
    draft.spaceId === activeSpaceId ? draft.description : (space?.description ?? '')
  const visibility =
    draft.spaceId === activeSpaceId ? draft.visibility : (space?.visibility ?? 'PRIVATE')

  if (!spaceId || !space) {
    return (
      <Box sx={{ flexGrow: 1, p: 3 }}>
        <PageHeading title="Space nicht geladen" variant="h6" />
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, overflowY: 'auto' }}>
      {(error || localError) && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            deleteBlockedByChats ? (
              <Button
                color="inherit"
                size="small"
                onClick={async () => {
                  try {
                    await archiveSelectedSpace(spaceId)
                    setDeleteBlockedByChats(false)
                    setLocalError(null)
                    setSuccessMessage('Space archiviert')
                  } catch (err) {
                    setLocalError(err instanceof Error ? err.message : 'Archivieren fehlgeschlagen')
                  }
                }}
              >
                Space archivieren
              </Button>
            ) : undefined
          }
        >
          {localError ?? error}
        </Alert>
      )}
      {successMessage && (
        <Alert severity="success" sx={{ mb: 2 }}>
          {successMessage}
        </Alert>
      )}

      <Stack spacing={2.5}>
        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
            <Typography variant="h6">Space-Einstellungen</Typography>
            {space.archived && <Chip label="Archiviert" size="small" color="default" />}
          </Stack>
          {space.archived && (
            <Alert severity="info" sx={{ mb: 2 }}>
              Dieser Space ist archiviert und nimmt keinen neuen Inhalt mehr an. Private Chats
              bleiben für ihre Autoren weiterhin lesbar.
            </Alert>
          )}
          <Divider sx={{ mb: 2 }} />
          <Stack spacing={1.5}>
            <TextField
              label="Name des Space"
              value={name}
              onChange={(event) =>
                setDraft({
                  spaceId: activeSpaceId,
                  name: event.target.value,
                  description,
                  visibility,
                })
              }
              disabled={!canManage}
            />
            <TextField
              label="Beschreibung"
              value={description}
              onChange={(event) =>
                setDraft({
                  spaceId: activeSpaceId,
                  name,
                  description: event.target.value,
                  visibility,
                })
              }
              multiline
              minRows={2}
              disabled={!canManage}
            />
            <FormControl disabled={!canManage}>
              <InputLabel id="space-visibility-label">Sichtbarkeit</InputLabel>
              <Select
                labelId="space-visibility-label"
                label="Sichtbarkeit"
                value={visibility}
                onChange={(event) =>
                  setDraft({
                    spaceId: activeSpaceId,
                    name,
                    description,
                    visibility: event.target.value as SpaceVisibility,
                  })
                }
              >
                {editableVisibilities.map((option) => (
                  <MenuItem key={option} value={option}>
                    {spaceVisibilityLabel(option)}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <Typography variant="caption" color="text.secondary">
              {spaceVisibilityDescription(visibility)}
            </Typography>
            {canManage && (
              <Button
                variant="contained"
                onClick={async () => {
                  setLocalError(null)
                  try {
                    await updateDetails(spaceId, name, description, visibility)
                    setSuccessMessage('Space aktualisiert')
                  } catch (err) {
                    setLocalError(
                      err instanceof Error ? err.message : 'Aktualisierung fehlgeschlagen',
                    )
                  }
                }}
              >
                Einstellungen speichern
              </Button>
            )}
            {isOwner && !space.isDefault && !space.archived && (
              <Button
                variant="outlined"
                onClick={async () => {
                  if (
                    !window.confirm(
                      'Diesen Space archivieren? Er nimmt danach keinen neuen Inhalt mehr an ' +
                        'und wird aus den regulären Listen ausgeblendet.',
                    )
                  ) {
                    return
                  }
                  setLocalError(null)
                  setDeleteBlockedByChats(false)
                  try {
                    await archiveSelectedSpace(spaceId)
                    setSuccessMessage('Space archiviert')
                  } catch (err) {
                    setLocalError(err instanceof Error ? err.message : 'Archivieren fehlgeschlagen')
                  }
                }}
              >
                Space archivieren
              </Button>
            )}
            {isOwner && !space.isDefault && (
              <Button
                color="error"
                variant="outlined"
                onClick={async () => {
                  if (
                    !window.confirm(
                      'Diesen Space löschen? Diese Aktion kann nicht rückgängig gemacht werden.',
                    )
                  ) {
                    return
                  }
                  setLocalError(null)
                  setDeleteBlockedByChats(false)
                  try {
                    await deleteSelectedSpace(spaceId)
                    navigate('/spaces')
                  } catch (err) {
                    const message = err instanceof Error ? err.message : 'Löschen fehlgeschlagen'
                    setLocalError(message)
                    // #543: deleteSpace's own 409 message names archiving as the way out - offer
                    // it directly instead of leaving the user to figure out the next step.
                    setDeleteBlockedByChats(message.includes('Archivieren'))
                  }
                }}
              >
                Space löschen
              </Button>
            )}
          </Stack>
        </Paper>

        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Typography variant="h6" gutterBottom>
            Mitglieder
          </Typography>
          <Divider sx={{ mb: 2 }} />
          {space.isDefault && space.memberCount === 1 ? (
            <Alert severity="info">
              Dies ist Ihr Standard-Space. Sie arbeiten hier allein — Sie können jederzeit
              Mitglieder hinzufügen.
            </Alert>
          ) : (
            <Stack spacing={1.5}>
              {space.members.map((member) => {
                const memberIsOwner = member.userId === space.ownerId
                return (
                  <Box
                    key={member.userId}
                    sx={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      gap: 1,
                      flexWrap: 'wrap',
                    }}
                  >
                    <Typography sx={member.displayName ? undefined : { fontFamily: 'monospace' }}>
                      {member.displayName ?? member.userId}
                      {memberIsOwner ? ' · Eigentümer' : ''}
                    </Typography>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      {canManage ? (
                        <Select
                          size="small"
                          value={member.role}
                          onChange={async (event) => {
                            const nextRole = event.target.value as SpaceRole
                            setLocalError(null)
                            try {
                              await updateMemberRole(spaceId, member.userId, nextRole)
                            } catch (err) {
                              setLocalError(
                                err instanceof Error
                                  ? err.message
                                  : 'Rollenänderung fehlgeschlagen',
                              )
                            }
                          }}
                        >
                          {editableRoles.map((role) => (
                            <MenuItem key={role} value={role}>
                              {spaceRoleLabel(role)}
                            </MenuItem>
                          ))}
                        </Select>
                      ) : (
                        <Chip label={spaceRoleLabel(member.role)} size="small" />
                      )}
                      {canManage && !memberIsOwner && (
                        <Button
                          color="error"
                          size="small"
                          onClick={async () => {
                            if (
                              !window.confirm(
                                `${member.displayName ?? member.userId} aus diesem Space entfernen?`,
                              )
                            ) {
                              return
                            }
                            setLocalError(null)
                            try {
                              await removeMember(spaceId, member.userId)
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
                      {isOwner && !memberIsOwner && (
                        <Button
                          size="small"
                          onClick={async () => {
                            if (
                              !window.confirm(
                                `Verantwortung an ${member.displayName ?? member.userId} übertragen?`,
                              )
                            ) {
                              return
                            }
                            setLocalError(null)
                            try {
                              await transferOwnership(spaceId, member.userId)
                              setSuccessMessage('Verantwortung übertragen')
                            } catch (err) {
                              setLocalError(
                                err instanceof Error
                                  ? err.message
                                  : 'Übertragung der Verantwortung fehlgeschlagen',
                              )
                            }
                          }}
                        >
                          Zum Eigentümer machen
                        </Button>
                      )}
                    </Stack>
                  </Box>
                )
              })}

              {canManage && (
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
                    sx={{ minWidth: 280 }}
                  />
                  <Select
                    size="small"
                    value={newMemberRole}
                    onChange={(event) => setNewMemberRole(event.target.value as SpaceRole)}
                    sx={{ width: 180 }}
                  >
                    {editableRoles.map((role) => (
                      <MenuItem key={role} value={role}>
                        {spaceRoleLabel(role)}
                      </MenuItem>
                    ))}
                  </Select>
                  <Button
                    variant="contained"
                    disabled={!selectedUser}
                    onClick={async () => {
                      if (!selectedUser) return
                      setLocalError(null)
                      try {
                        await addMember(spaceId, selectedUser.id, newMemberRole)
                        setSelectedUser(null)
                        setSuccessMessage('Mitglied hinzugefügt')
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
        </Paper>
      </Stack>
    </Box>
  )
}
