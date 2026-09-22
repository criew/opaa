import { useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import SectionHead from '../SectionHead'
import type { GroupContactResponse, GroupMemberResponse } from '../../types/api'
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

  const [selected, setSelected] = useState<GroupMemberResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Benennbar ist nur ein Mitglied dieser Gruppe - die Auswahl bietet deshalb genau die
  // Mitglieder an, nicht die Kontensuche der Organisation (#1875).
  const candidates = useMemo(
    () =>
      (members ?? []).filter(
        (member) => !contacts.some((contact) => contact.userId === member.userId),
      ),
    [members, contacts],
  )

  async function handleAppoint() {
    if (!selected) return
    setError(null)
    try {
      await appointContact(groupId, selected.userId)
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
        {members === undefined ? (
          <Typography sx={{ fontSize: 13, color: 'text.secondary', pt: 1 }}>
            Benennbar ist nur ein Mitglied dieser Gruppe. Rufen Sie unten die Mitgliederliste ab, um
            eine Ansprechstelle zu benennen — der Abruf ist ein Audit-Ereignis.
          </Typography>
        ) : members.length === 0 ? (
          <Typography sx={{ fontSize: 13, color: 'text.secondary', pt: 1 }}>
            Diese Gruppe hat kein Mitglied, das benannt werden könnte.
          </Typography>
        ) : (
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ pt: 1 }}>
            <Autocomplete
              sx={{ minWidth: 260 }}
              options={candidates}
              value={selected}
              onChange={(_event, next) => setSelected(next)}
              getOptionLabel={(member) => member.displayName ?? member.userId}
              isOptionEqualToValue={(option, current) => option.userId === current.userId}
              noOptionsText="Alle Mitglieder sind bereits Ansprechstelle"
              renderInput={(params) => (
                <TextField
                  {...params}
                  size="small"
                  label="Ansprechstelle"
                  placeholder="Mitglied wählen …"
                />
              )}
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
        )}
      </Stack>
    </Box>
  )
}
