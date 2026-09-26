import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { GroupListResponse, UserInfo } from '../../../types/api'
import { getUsers } from '../../../services/api'
import { useGroupStore } from '../../../stores/groupStore'

const AUDIT_NOTICE =
  'Der Abruf der Mitgliederliste durch die Systemverwaltung wird im Nachweisprotokoll ' +
  'festgehalten. Deshalb lädt dieser Dialog sie erst auf ausdrücklichen Wunsch.'

interface GroupMembersDialogProps {
  /** The group whose members to show; the dialog is closed while this is null. */
  group: GroupListResponse | null
  onClose: () => void
}

/**
 * Die Mitglieder einer Gruppe (#1978). Die Liste lädt erst auf ausdrücklichen Wunsch, denn ihr
 * Abruf durch die Systemverwaltung ist ein Audit-Ereignis (ADR-0036, Entscheidungen 4 und 9);
 * bis dahin steht nur die Zahl da. Mitglieder aufnehmen und entfernen lässt sich nur bei einer
 * internen Gruppe - die übrigen pflegt ihre Quelle.
 */
export default function GroupMembersDialog({ group, onClose }: GroupMembersDialogProps) {
  if (!group) return null
  return <GroupMembersDialogContent key={group.id} group={group} onClose={onClose} />
}

function memberText(count: number): string {
  return count === 1 ? '1 Mitglied' : `${count} Mitglieder`
}

function GroupMembersDialogContent({
  group,
  onClose,
}: {
  group: GroupListResponse
  onClose: () => void
}) {
  const details = useGroupStore((s) => s.groupDetails[group.id])
  const loadGroupDetails = useGroupStore((s) => s.loadGroupDetails)
  const addMember = useGroupStore((s) => s.addMember)
  const removeMember = useGroupStore((s) => s.removeMember)
  const isInternal = group.kind === 'AD_HOC'

  const [requested, setRequested] = useState(false)
  const [allUsers, setAllUsers] = useState<UserInfo[]>([])
  const [selectedUser, setSelectedUser] = useState<UserInfo | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!requested) return
    void loadGroupDetails(group.id)
    if (isInternal) {
      void getUsers()
        .then(setAllUsers)
        .catch(() => setAllUsers([]))
    }
  }, [requested, group.id, isInternal, loadGroupDetails])

  const members = requested ? details?.members : undefined
  const availableUsers = useMemo(() => {
    const memberIds = new Set(members?.map((member) => member.userId) ?? [])
    return allUsers.filter((user) => !memberIds.has(user.id))
  }, [allUsers, members])

  async function run(action: () => Promise<void>, fallback: string) {
    setError(null)
    try {
      await action()
    } catch (err) {
      setError(err instanceof Error ? err.message : fallback)
    }
  }

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onClose} aria-labelledby="group-members-title">
      <DialogTitle id="group-members-title">Mitglieder von „{group.name}“</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}
        {!requested ? (
          <Stack spacing={1.5} sx={{ alignItems: 'flex-start' }}>
            <Typography sx={{ fontSize: 13.5 }}>
              Die Gruppe hat {memberText(group.memberCount)}.
            </Typography>
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>{AUDIT_NOTICE}</Typography>
            <Button variant="outlined" size="small" onClick={() => setRequested(true)}>
              Mitglieder anzeigen
            </Button>
          </Stack>
        ) : !members ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
            <CircularProgress size={22} aria-label="Mitglieder werden geladen" />
          </Box>
        ) : (
          <Stack spacing={1}>
            {members.length === 0 && (
              <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                Diese Gruppe hat keine Mitglieder.
              </Typography>
            )}
            <Box
              component="ul"
              aria-label="Mitglieder"
              sx={{ listStyle: 'none', m: 0, p: 0, maxHeight: 320, overflowY: 'auto' }}
            >
              {members.map((member) => (
                <Box
                  component="li"
                  key={member.userId}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 1,
                    py: 0.5,
                    borderBottom: 1,
                    borderColor: 'divider',
                  }}
                >
                  <Typography
                    sx={{
                      fontSize: 13.5,
                      ...(member.displayName ? {} : { fontFamily: 'monospace' }),
                    }}
                  >
                    {member.displayName ?? member.userId}
                  </Typography>
                  {isInternal && (
                    <Button
                      color="error"
                      size="small"
                      onClick={() =>
                        void run(
                          () => removeMember(group.id, member.userId),
                          'Entfernen des Mitglieds fehlgeschlagen',
                        )
                      }
                    >
                      Entfernen
                    </Button>
                  )}
                </Box>
              ))}
            </Box>
            {isInternal ? (
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ pt: 1 }}>
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
                      placeholder="Person suchen …"
                      slotProps={{
                        ...params.slotProps,
                        htmlInput: { ...params.slotProps.htmlInput, 'aria-label': 'Person' },
                      }}
                    />
                  )}
                  isOptionEqualToValue={(option, value) => option.id === value.id}
                  size="small"
                  sx={{ flexGrow: 1 }}
                />
                <Button
                  variant="contained"
                  size="small"
                  disabled={!selectedUser}
                  onClick={() =>
                    void run(async () => {
                      if (!selectedUser) return
                      await addMember(group.id, selectedUser.id)
                      setSelectedUser(null)
                    }, 'Mitglied konnte nicht hinzugefügt werden')
                  }
                >
                  Aufnehmen
                </Button>
              </Stack>
            ) : (
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary', pt: 1 }}>
                Die Mitglieder dieser Gruppe pflegt ihre Quelle.
              </Typography>
            )}
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Schließen</Button>
      </DialogActions>
    </Dialog>
  )
}
