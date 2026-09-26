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
import { selectGroupMembers, useGroupStore } from '../../../stores/groupStore'
import GroupMembersTable from './GroupMembersTable'

const AUDIT_NOTICE = 'Dieser Abruf wird im Nachweisprotokoll festgehalten.'

const PROVIDER_AUDIT_NOTICE =
  'Die Mitglieder pflegt die Quelle der Gruppe. Dieser Abruf wird im Nachweisprotokoll festgehalten.'

interface GroupMembersDialogProps {
  /** The group whose members to show; the dialog is closed while this is null. */
  group: GroupListResponse | null
  onClose: () => void
}

/**
 * Die Mitglieder einer Gruppe (#1978) als Tabelle. Ihr Abruf durch die Systemverwaltung ist ein
 * Audit-Ereignis (ADR-0036, Entscheidungen 4 und 9); das Öffnen des Dialogs ist der ausdrückliche
 * Wunsch, die Liste lädt deshalb sofort, und der Dialog nennt die Protokollierung. Aufnehmen und
 * entfernen lässt sich nur bei einer internen Gruppe - die übrigen pflegt ihre Quelle.
 */
export default function GroupMembersDialog({ group, onClose }: GroupMembersDialogProps) {
  if (!group) return null
  return <GroupMembersDialogContent key={group.id} group={group} onClose={onClose} />
}

function GroupMembersDialogContent({
  group,
  onClose,
}: {
  group: GroupListResponse
  onClose: () => void
}) {
  const knownMembers = useGroupStore(selectGroupMembers(group.id))
  const loadGroupMembers = useGroupStore((s) => s.loadGroupMembers)
  const addMember = useGroupStore((s) => s.addMember)
  const removeMember = useGroupStore((s) => s.removeMember)
  const isInternal = group.kind === 'AD_HOC'

  const [allUsers, setAllUsers] = useState<UserInfo[]>([])
  const [selectedUser, setSelectedUser] = useState<UserInfo | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void loadGroupMembers(group.id)
    if (isInternal) {
      void getUsers()
        .then(setAllUsers)
        .catch(() => setAllUsers([]))
    }
  }, [group.id, isInternal, loadGroupMembers])

  const members = knownMembers
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
    <Dialog open fullWidth maxWidth="md" onClose={onClose} aria-labelledby="group-members-title">
      <DialogTitle id="group-members-title">Mitglieder von „{group.name}“</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}
        {!members ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
            <CircularProgress size={22} aria-label="Mitglieder werden geladen" />
          </Box>
        ) : (
          <Stack spacing={1}>
            <GroupMembersTable
              members={members}
              onRemove={
                isInternal
                  ? (member) =>
                      void run(
                        () => removeMember(group.id, member.userId),
                        'Entfernen des Mitglieds fehlgeschlagen',
                      )
                  : undefined
              }
            />
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
            ) : null}
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', pt: 1 }}>
              {isInternal ? AUDIT_NOTICE : PROVIDER_AUDIT_NOTICE}
            </Typography>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Schließen</Button>
      </DialogActions>
    </Dialog>
  )
}
