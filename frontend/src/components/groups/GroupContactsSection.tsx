import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import SectionHead from '../SectionHead'
import UserPicker from './UserPicker'
import type { GroupContactResponse, GroupMemberResponse, UserSummary } from '../../types/api'
import { confirmAction } from '../../stores/confirmStore'
import { useGroupStore } from '../../stores/groupStore'

interface GroupContactsSectionProps {
  groupId: string
  contacts: GroupContactResponse[]
  /** Die Mitglieder, sofern abgerufen — nur ein Mitglied kann Ansprechstelle sein. */
  members: GroupMemberResponse[] | undefined
  currentUserId: string | undefined
}

/**
 * Die Ansprechstellen einer Anbietergruppe (#1875, ADR-0036 Entscheidung 9). Die Systemverwaltung
 * benennt sie und entlässt sie — ein Verwaltungsakt, der die Gruppe selbst nicht verändert und
 * keine Pflegerechte verleiht. Er berechtigt zu genau einer Handlung: das Schutzkennzeichen dieser
 * Gruppe zu setzen und zu lösen, was die Systemverwaltung ihrerseits nicht darf.
 */
export default function GroupContactsSection({
  groupId,
  contacts,
  members,
  currentUserId,
}: GroupContactsSectionProps) {
  const appointContact = useGroupStore((s) => s.appointContact)
  const dismissContact = useGroupStore((s) => s.dismissContact)

  const [selected, setSelected] = useState<UserSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function handleAppoint() {
    if (!selected) return
    setError(null)
    try {
      await appointContact(groupId, selected.id)
      setSelected(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Person konnte nicht benannt werden')
    }
  }

  async function handleDismiss(contact: GroupContactResponse) {
    const confirmed = await confirmAction({
      question: `${contact.displayName ?? 'Diese Person'} als Ansprechstelle entlassen?`,
      consequence:
        'Die Person kann das Schutzkennzeichen dieser Gruppe danach nicht mehr setzen oder lösen.',
      confirmLabel: 'Entlassen',
      tone: 'danger',
    })
    if (!confirmed) return
    setError(null)
    try {
      await dismissContact(groupId, contact.userId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Person konnte nicht entlassen werden')
    }
  }

  return (
    <Box>
      <SectionHead>Ansprechstelle</SectionHead>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1.5 }}>
        Ansprechstellen sprechen für diese Gruppe. Sie pflegen sie nicht — die Gruppe kommt vom
        Anbieter und wird dort gepflegt —, entscheiden aber über ihr Schutzkennzeichen; die
        Systemverwaltung kann es nicht setzen und nicht lösen. Benennbar ist nur, wer Mitglied der
        Gruppe ist.
      </Typography>
      <Stack spacing={1}>
        {contacts.length === 0 && (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Für diese Gruppe ist keine Ansprechstelle benannt. Solange das so ist, kann ihr
            Schutzkennzeichen niemand setzen oder lösen.
          </Typography>
        )}
        {contacts.map((contact) => (
          <Box
            key={contact.userId}
            sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}
          >
            <Typography
              variant="body2"
              sx={contact.displayName ? undefined : { fontFamily: 'monospace' }}
            >
              {contact.displayName ?? contact.userId}
              {contact.userId === currentUserId ? ' (Sie)' : ''}
            </Typography>
            <Button color="error" size="small" onClick={() => void handleDismiss(contact)}>
              Entlassen
            </Button>
          </Box>
        ))}
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ pt: 1 }}>
          <UserPicker
            ariaLabel="Ansprechstelle"
            placeholder="Mitglied suchen …"
            value={selected}
            onChange={setSelected}
            excludedUserIds={contacts.map((contact) => contact.userId)}
          />
          <Button
            variant="outlined"
            size="small"
            disabled={!selected}
            onClick={() => void handleAppoint()}
          >
            Als Ansprechstelle benennen
          </Button>
        </Stack>
        {members !== undefined && members.length === 0 && (
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            Diese Gruppe hat kein Mitglied, das benannt werden könnte.
          </Typography>
        )}
      </Stack>
    </Box>
  )
}
