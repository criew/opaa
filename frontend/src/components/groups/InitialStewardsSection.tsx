import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import SectionHead from '../SectionHead'
import UserPicker from './UserPicker'
import type { UserSummary } from '../../types/api'

interface InitialStewardsSectionProps {
  stewards: UserSummary[]
  onChange: (stewards: UserSummary[]) => void
  currentUserId: string | undefined
  /**
   * Whether the caller may leave themselves out. Only the system administration may create a group
   * for others; whoever else creates one stays responsible for it.
   */
  canRemoveSelf: boolean
}

function nameOf(user: UserSummary): string {
  return user.displayName ?? user.email ?? user.id
}

/**
 * Die Verantwortlichen einer neuen Gruppe (#1978) - wie der Abschnitt „Verantwortlich“ beim
 * Bearbeiten, nur als Entwurf: Benennen und Entfernen wirken erst mit „Anlegen“.
 */
export default function InitialStewardsSection({
  stewards,
  onChange,
  currentUserId,
  canRemoveSelf,
}: InitialStewardsSectionProps) {
  const [selected, setSelected] = useState<UserSummary | null>(null)

  function add() {
    if (!selected) return
    onChange([...stewards, selected])
    setSelected(null)
  }

  return (
    <Box>
      <SectionHead>Verantwortlich</SectionHead>
      <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1.5 }}>
        Verantwortliche pflegen die Gruppe; Mitglied werden sie dadurch nicht.
        {canRemoveSelf
          ? ' Benennen Sie niemanden, werden Sie selbst verantwortlich.'
          : ' Sie selbst bleiben verantwortlich.'}
      </Typography>
      <Stack spacing={1}>
        {stewards.map((steward) => {
          const own = steward.id === currentUserId
          return (
            <Box
              key={steward.id}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 1,
              }}
            >
              <Typography variant="body2">
                {nameOf(steward)}
                {own ? ' (Sie)' : ''}
              </Typography>
              {(!own || canRemoveSelf) && (
                <Button
                  color="error"
                  size="small"
                  aria-label={`${nameOf(steward)} nicht benennen`}
                  onClick={() => onChange(stewards.filter((entry) => entry.id !== steward.id))}
                >
                  Entfernen
                </Button>
              )}
            </Box>
          )
        })}
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ pt: 1 }}>
          <UserPicker
            ariaLabel="Verantwortliche Person"
            placeholder="Person suchen …"
            value={selected}
            onChange={setSelected}
            excludedUserIds={stewards.map((steward) => steward.id)}
          />
          <Button variant="outlined" size="small" disabled={!selected} onClick={add}>
            Als verantwortlich benennen
          </Button>
        </Stack>
      </Stack>
    </Box>
  )
}
