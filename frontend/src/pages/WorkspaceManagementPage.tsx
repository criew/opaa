import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import MenuItem from '@mui/material/MenuItem'
import Paper from '@mui/material/Paper'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useNavigate, useParams } from 'react-router'
import type { UserInfo, WorkspaceRole } from '../types/api'
import { getUsers } from '../services/api'
import { useWorkspaceStore } from '../stores/workspaceStore'
import { workspaceRoleLabel } from '../utils/labels'

const editableRoles: WorkspaceRole[] = ['VIEWER', 'EDITOR', 'ADMIN']

function canManageMembers(role: WorkspaceRole | undefined): boolean {
  return role === 'ADMIN' || role === 'OWNER'
}

export default function WorkspaceManagementPage() {
  const { workspaceId } = useParams()
  const navigate = useNavigate()
  const loadWorkspaces = useWorkspaceStore((s) => s.loadWorkspaces)
  const selectWorkspace = useWorkspaceStore((s) => s.selectWorkspace)
  const workspace = useWorkspaceStore((s) => s.selectedWorkspace)
  const error = useWorkspaceStore((s) => s.error)
  const addMember = useWorkspaceStore((s) => s.addMember)
  const updateMemberRole = useWorkspaceStore((s) => s.updateMemberRole)
  const removeMember = useWorkspaceStore((s) => s.removeMember)
  const transferOwnership = useWorkspaceStore((s) => s.transferOwnership)
  const updateDetails = useWorkspaceStore((s) => s.updateDetails)
  const deleteSelectedWorkspace = useWorkspaceStore((s) => s.deleteSelectedWorkspace)
  const [draft, setDraft] = useState<{
    workspaceId: string | null
    name: string
    description: string
  }>({
    workspaceId: null,
    name: '',
    description: '',
  })
  const [selectedUser, setSelectedUser] = useState<UserInfo | null>(null)
  const [newMemberRole, setNewMemberRole] = useState<WorkspaceRole>('VIEWER')
  const [localError, setLocalError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const [allUsers, setAllUsers] = useState<UserInfo[]>([])

  useEffect(() => {
    if (workspaceId) {
      void loadWorkspaces()
      void selectWorkspace(workspaceId)
    }
  }, [loadWorkspaces, selectWorkspace, workspaceId])

  useEffect(() => {
    void getUsers()
      .then(setAllUsers)
      .catch(() => setAllUsers([]))
  }, [])

  const canManage = useMemo(() => canManageMembers(workspace?.userRole), [workspace?.userRole])
  const availableUsers = useMemo(() => {
    const memberIds = new Set(workspace?.members.map((m) => m.userId) ?? [])
    return allUsers.filter((u) => !memberIds.has(u.id))
  }, [allUsers, workspace?.members])
  const isOwner = workspace?.userRole === 'OWNER'
  const activeWorkspaceId = workspace?.id ?? null
  const name = draft.workspaceId === activeWorkspaceId ? draft.name : (workspace?.name ?? '')
  const description =
    draft.workspaceId === activeWorkspaceId ? draft.description : (workspace?.description ?? '')

  if (!workspaceId || !workspace) {
    return (
      <Box sx={{ flexGrow: 1, p: 3 }}>
        <Typography variant="h6">Workspace nicht geladen</Typography>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, overflowY: 'auto' }}>
      {(error || localError) && (
        <Alert severity="error" sx={{ mb: 2 }}>
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
          <Typography variant="h6" gutterBottom>
            Workspace-Einstellungen
          </Typography>
          <Divider sx={{ mb: 2 }} />
          <Stack spacing={1.5}>
            <TextField
              label="Name des Workspace"
              value={name}
              onChange={(event) =>
                setDraft({
                  workspaceId: activeWorkspaceId,
                  name: event.target.value,
                  description,
                })
              }
              disabled={!canManage}
            />
            <TextField
              label="Beschreibung"
              value={description}
              onChange={(event) =>
                setDraft({
                  workspaceId: activeWorkspaceId,
                  name,
                  description: event.target.value,
                })
              }
              multiline
              minRows={2}
              disabled={!canManage}
            />
            {canManage && (
              <Button
                variant="contained"
                onClick={async () => {
                  setLocalError(null)
                  try {
                    await updateDetails(workspaceId, name, description)
                    setSuccessMessage('Workspace aktualisiert')
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
            {isOwner && workspace.type !== 'PERSONAL' && (
              <Button
                color="error"
                variant="outlined"
                onClick={async () => {
                  if (
                    !window.confirm(
                      'Diesen Workspace löschen? Diese Aktion kann nicht rückgängig gemacht werden.',
                    )
                  ) {
                    return
                  }
                  setLocalError(null)
                  try {
                    await deleteSelectedWorkspace(workspaceId)
                    navigate('/workspaces')
                  } catch (err) {
                    setLocalError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
                  }
                }}
              >
                Workspace löschen
              </Button>
            )}
          </Stack>
        </Paper>

        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Typography variant="h6" gutterBottom>
            Mitglieder
          </Typography>
          <Divider sx={{ mb: 2 }} />
          {workspace.type === 'PERSONAL' ? (
            <Alert severity="info">
              Dies ist Ihr persönlicher Workspace. Die Mitgliederverwaltung ist deaktiviert.
            </Alert>
          ) : (
            <Stack spacing={1.5}>
              {workspace.members.map((member) => (
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
                  </Typography>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    {canManage ? (
                      <Select
                        size="small"
                        value={member.role}
                        onChange={async (event) => {
                          const nextRole = event.target.value as WorkspaceRole
                          if (member.role === 'OWNER' || nextRole === 'OWNER') {
                            return
                          }
                          setLocalError(null)
                          try {
                            await updateMemberRole(workspaceId, member.userId, nextRole)
                          } catch (err) {
                            setLocalError(
                              err instanceof Error ? err.message : 'Rollenänderung fehlgeschlagen',
                            )
                          }
                        }}
                        disabled={member.role === 'OWNER'}
                      >
                        {[...editableRoles, member.role]
                          .filter((value, index, arr) => arr.indexOf(value) === index)
                          .map((role) => (
                            <MenuItem key={role} value={role}>
                              {workspaceRoleLabel(role)}
                            </MenuItem>
                          ))}
                      </Select>
                    ) : (
                      <Chip label={workspaceRoleLabel(member.role)} size="small" />
                    )}
                    {canManage && member.role !== 'OWNER' && (
                      <Button
                        color="error"
                        size="small"
                        onClick={async () => {
                          if (
                            !window.confirm(
                              `${member.displayName ?? member.userId} aus diesem Workspace entfernen?`,
                            )
                          ) {
                            return
                          }
                          setLocalError(null)
                          try {
                            await removeMember(workspaceId, member.userId)
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
                    {isOwner && member.role !== 'OWNER' && (
                      <Button
                        size="small"
                        onClick={async () => {
                          if (
                            !window.confirm(
                              `Eigentum an ${member.displayName ?? member.userId} übertragen?`,
                            )
                          ) {
                            return
                          }
                          setLocalError(null)
                          try {
                            await transferOwnership(workspaceId, member.userId)
                            setSuccessMessage('Eigentum übertragen')
                          } catch (err) {
                            setLocalError(
                              err instanceof Error
                                ? err.message
                                : 'Übertragung des Eigentums fehlgeschlagen',
                            )
                          }
                        }}
                      >
                        Zum Eigentümer machen
                      </Button>
                    )}
                  </Stack>
                </Box>
              ))}

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
                    onChange={(event) => setNewMemberRole(event.target.value as WorkspaceRole)}
                    sx={{ width: 180 }}
                  >
                    {editableRoles.map((role) => (
                      <MenuItem key={role} value={role}>
                        {workspaceRoleLabel(role)}
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
                        await addMember(workspaceId, selectedUser.id, newMemberRole)
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
