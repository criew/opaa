import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { GroupListResponse, UserSummary } from '@/shared/api/types'
import {
  SubjectFormRow,
  USER_CHOICE_MISSING,
  UserPicker,
  useRequiredChoice,
} from '@/features/principal'
import { selectGroupMembers, useGroupStore } from '../stores/groupStore'
import GroupMembersTable from './GroupMembersTable'

const AUDIT_NOTICE =
  'Ruft die Systemverwaltung die Mitgliederliste ab, wird das im Nachweisprotokoll festgehalten.'

const PROVIDER_AUDIT_NOTICE = 'Die Mitglieder pflegt die Quelle der Gruppe. ' + AUDIT_NOTICE

interface GroupMembersDialogProps {
  /** The group whose members to show; the dialog is closed while this is null. */
  group: GroupListResponse | null
  onClose: () => void
}

/**
 * The members of a group as a table. Their retrieval by the system administration is an
 * audit event (ADR-0036, Entscheidungen 4 and 9); opening the dialog is the explicit
 * request, so the list loads at once, and the dialog names the logging. Adding and
 * removing is possible only for an internal group - its source maintains the others.
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
  const knownMembers = useGroupStore(selectGroupMembers(group.id))
  const loadGroupMembers = useGroupStore((s) => s.loadGroupMembers)
  const addMember = useGroupStore((s) => s.addMember)
  const removeMember = useGroupStore((s) => s.removeMember)
  const isInternal = group.kind === 'AD_HOC'

  const [selectedUser, setSelectedUser] = useState<UserSummary | null>(null)
  const choice = useRequiredChoice(USER_CHOICE_MISSING)
  const [error, setError] = useState<string | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    loadGroupMembers(group.id).catch((err: unknown) =>
      setLoadError(
        err instanceof Error ? err.message : 'Die Mitgliederliste konnte nicht geladen werden.',
      ),
    )
  }, [group.id, loadGroupMembers, attempt])

  const members = knownMembers

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
      <DialogTitle id="group-members-title">
        Mitglieder von „{group.name}“{' '}
        <Box component="span" sx={{ color: 'text.secondary', fontWeight: 400 }}>
          · {memberText(members?.length ?? group.memberCount)}
        </Box>
      </DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}
        {!members && loadError ? (
          <Alert
            severity="error"
            action={
              <Button
                color="inherit"
                size="small"
                onClick={() => {
                  setLoadError(null)
                  setAttempt((current) => current + 1)
                }}
              >
                Erneut versuchen
              </Button>
            }
          >
            {loadError}
          </Alert>
        ) : !members ? (
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
              <Box sx={{ pt: 1 }}>
                <SubjectFormRow
                  picker={
                    <UserPicker
                      ariaLabel="Person suchen"
                      placeholder="Person suchen …"
                      value={selectedUser}
                      onChange={(next) => {
                        choice.clear()
                        setSelectedUser(next)
                      }}
                      errorText={choice.errorText}
                      inputRef={choice.inputRef}
                      grow
                      excludedUserIds={members.map((member) => member.userId)}
                    />
                  }
                  action={
                    <Button
                      variant="contained"
                      onClick={() => {
                        if (!choice.check(Boolean(selectedUser)) || !selectedUser) return
                        void run(async () => {
                          await addMember(group.id, selectedUser.id)
                          setSelectedUser(null)
                        }, 'Mitglied konnte nicht hinzugefügt werden')
                      }}
                    >
                      Hinzufügen
                    </Button>
                  }
                />
              </Box>
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
