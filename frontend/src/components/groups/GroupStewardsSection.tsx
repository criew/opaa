import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import SectionHead from '../SectionHead'
import UserPicker from './UserPicker'
import type { GroupStewardResponse, UserSummary } from '../../types/api'
import { confirmAction } from '../../stores/confirmStore'
import { useAuthStore } from '../../stores/authStore'
import { useGroupStore } from '../../stores/groupStore'

interface GroupStewardsSectionProps {
  groupId: string
  groupName: string
  stewards: GroupStewardResponse[]
  /** Die eigene Konto-Kennung — der Rücktritt ist der Schlusspunkt einer Abgabe, kein Entfernen. */
  currentUserId: string | undefined
}

/**
 * Die verantwortlichen Personen einer internen Gruppe (#1814, ADR-0036 Entscheidung 4): benennen,
 * entlassen und die Verantwortung abgeben. Die Abgabe ist bewusst zweischrittig — erst die
 * Nachfolge benennen, dann selbst zurücktreten; die letzte verantwortliche Person kann sich nicht
 * selbst entfernen, und der Dienst weist genau das ab. Die Systemverwaltung darf die letzte
 * verantwortliche Person entlassen — ein ausscheidendes Konto muss lösbar sein —, deshalb hängt
 * die Sperre der Schaltfläche an beidem.
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
  const [error, setError] = useState<string | null>(null)

  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')

  const isOnlySteward = stewards.length === 1
  const amSteward = stewards.some((steward) => steward.userId === currentUserId)
  const dismissalBlocked = isOnlySteward && !isSystemAdmin

  async function handleAppoint() {
    if (!selected) return
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
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ pt: 1 }}>
          <UserPicker
            ariaLabel="Verantwortliche Person"
            placeholder="Person suchen …"
            value={selected}
            onChange={setSelected}
            excludedUserIds={stewards.map((steward) => steward.userId)}
          />
          <Button
            variant="outlined"
            size="small"
            disabled={!selected}
            onClick={() => void handleAppoint()}
          >
            Als verantwortlich benennen
          </Button>
        </Stack>
      </Stack>
    </Box>
  )
}
