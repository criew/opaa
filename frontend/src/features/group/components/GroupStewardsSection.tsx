import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import SectionHead from '@/shared/ui/SectionHead'
import {
  SubjectFormRow,
  USER_CHOICE_MISSING,
  UserPicker,
  useRequiredChoice,
} from '@/features/principal'
import type { GroupStewardResponse, UserSummary } from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { useAuthStore } from '@/shared/session/authStore'
import { useGroupStore } from '../stores/groupStore'

interface GroupStewardsSectionProps {
  groupId: string
  groupName: string
  stewards: GroupStewardResponse[]
  /** The own account identifier — stepping down is the end point of a handover, not a removal. */
  currentUserId: string | undefined
}

/**
 * The stewards of an internal group (ADR-0036 Entscheidung 4): appoint,
 * dismiss and hand over the responsibility. The handover is deliberately two-step — first
 * appoint the successor, then step down oneself; the last steward cannot remove themselves,
 * and the service rejects exactly that. The system administration may dismiss the last
 * steward — a departing account must be removable —, which is why the
 * button's lock depends on both.
 */
export default function GroupStewardsSection({
  groupId,
  groupName,
  stewards,
  currentUserId,
}: GroupStewardsSectionProps) {
  const appointSteward = useGroupStore((s) => s.appointSteward)
  const dismissSteward = useGroupStore((s) => s.dismissSteward)

  const [selected, setSelected] = useState<UserSummary | null>(null)
  const choice = useRequiredChoice(USER_CHOICE_MISSING)
  const [error, setError] = useState<string | null>(null)

  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')

  const isOnlySteward = stewards.length === 1
  const amSteward = stewards.some((steward) => steward.userId === currentUserId)
  const dismissalBlocked = isOnlySteward && !isSystemAdmin

  async function handleAppoint() {
    if (!choice.check(Boolean(selected)) || !selected) return
    setError(null)
    try {
      await appointSteward(groupId, selected.id)
      setSelected(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Person konnte nicht benannt werden')
    }
  }

  async function handleDismiss(steward: GroupStewardResponse) {
    const own = steward.userId === currentUserId
    const confirmed = await confirmAction({
      question: own
        ? `Verantwortung für „${groupName}“ abgeben?`
        : `${steward.displayName ?? 'Diese Person'} als verantwortlich entlassen?`,
      consequence: own
        ? 'Sie können die Gruppe danach nicht mehr pflegen.'
        : 'Die Person kann die Gruppe danach nicht mehr pflegen.',
      confirmLabel: own ? 'Verantwortung abgeben' : 'Entlassen',
      tone: 'danger',
    })
    if (!confirmed) return
    setError(null)
    try {
      await dismissSteward(groupId, steward.userId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Person konnte nicht entlassen werden')
    }
  }

  return (
    <Box>
      <SectionHead>Verantwortlich</SectionHead>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1.5 }}>
        Verantwortliche pflegen die Gruppe; Mitglied werden sie dadurch nicht. Wechseln Sie die
        Aufgabe, benennen Sie bitte zuerst eine Nachfolge und geben Sie die Verantwortung dann ab.
      </Typography>
      <Stack spacing={1}>
        {stewards.length === 0 && (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Für diese Gruppe ist niemand verantwortlich.
          </Typography>
        )}
        {stewards.map((steward) => (
          <Box
            key={steward.userId}
            sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}
          >
            <Typography
              variant="body2"
              sx={steward.displayName ? undefined : { fontFamily: 'monospace' }}
            >
              {steward.displayName ?? steward.userId}
              {steward.userId === currentUserId ? ' (Sie)' : ''}
            </Typography>
            <Button
              color="error"
              size="small"
              disabled={dismissalBlocked}
              onClick={() => void handleDismiss(steward)}
            >
              {steward.userId === currentUserId ? 'Verantwortung abgeben' : 'Entlassen'}
            </Button>
          </Box>
        ))}
        {dismissalBlocked && (
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            {amSteward
              ? 'Sie sind die letzte verantwortliche Person. Benennen Sie eine Nachfolge, um die Verantwortung abgeben zu können.'
              : 'Dies ist die letzte verantwortliche Person. Sie kann erst entlassen werden, wenn eine Nachfolge benannt ist.'}
          </Typography>
        )}
        {isOnlySteward && isSystemAdmin && (
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            Dies ist die letzte verantwortliche Person. Als Systemverwaltung können Sie sie
            entlassen — etwa wenn ihr Konto das Haus verlässt. Die Gruppe steht danach ohne
            Verantwortliche da, bis eine neue benannt wird.
          </Typography>
        )}
        <Box sx={{ pt: 1 }}>
          <SubjectFormRow
            picker={
              <UserPicker
                ariaLabel="Verantwortliche Person"
                placeholder="Person suchen …"
                value={selected}
                onChange={(next) => {
                  choice.clear()
                  setSelected(next)
                }}
                errorText={choice.errorText}
                inputRef={choice.inputRef}
                grow
                excludedUserIds={stewards.map((steward) => steward.userId)}
              />
            }
            action={
              <Button variant="outlined" onClick={() => void handleAppoint()}>
                Als verantwortlich benennen
              </Button>
            }
          />
        </Box>
      </Stack>
    </Box>
  )
}
