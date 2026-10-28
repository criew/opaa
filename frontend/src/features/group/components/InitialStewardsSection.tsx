import { useState } from 'react'
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
import type { UserSummary } from '@/shared/api/types'

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
 * The stewards of a new group - like the „Verantwortlich“ section when
 * editing, only as a draft: appointing and removing take effect only with „Anlegen“.
 */
export default function InitialStewardsSection({
  stewards,
  onChange,
  currentUserId,
  canRemoveSelf,
}: InitialStewardsSectionProps) {
  const [selected, setSelected] = useState<UserSummary | null>(null)
  const choice = useRequiredChoice(USER_CHOICE_MISSING)

  function add() {
    if (!choice.check(Boolean(selected)) || !selected) return
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
                excludedUserIds={stewards.map((steward) => steward.id)}
              />
            }
            action={
              <Button variant="outlined" onClick={add}>
                Als verantwortlich benennen
              </Button>
            }
          />
        </Box>
      </Stack>
    </Box>
  )
}
