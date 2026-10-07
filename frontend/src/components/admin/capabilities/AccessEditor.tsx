import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import CloseIcon from '@mui/icons-material/Close'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined'
import type { CapabilityOverviewResponse } from '../../../types/api'
import { radius } from '../../../theme/tokens'
import SubjectPicker from '../../permissions/SubjectPicker'
import { emptySubjectSelection, groupLabel } from '../../permissions/subjectSelection'
import {
  currentAccess,
  planAccessChange,
  resultSentence,
  type Access,
  type AccessLevel,
  type NamedSubject,
} from './capabilityAccess'

const CHOICES: Array<{ level: AccessLevel; title: string; hint: string }> = [
  { level: 'ALL', title: 'Alle Konten', hint: 'Jede Person Ihrer Organisation.' },
  {
    level: 'SELECTED',
    title: 'Nur bestimmte Gruppen und Personen',
    hint: 'Sie wählen aus, wer darf. Die Systemverwaltung darf immer.',
  },
  { level: 'ADMIN_ONLY', title: 'Nur die Systemverwaltung', hint: 'Niemand sonst.' },
]

function SelectedSubjects({
  subjects,
  onRemove,
}: {
  subjects: NamedSubject[]
  onRemove: (subject: NamedSubject) => void
}) {
  if (subjects.length === 0) return null
  return (
    <Stack component="ul" spacing={0.5} sx={{ listStyle: 'none', p: 0, m: 0 }}>
      {subjects.map((subject) => {
        const Icon = subject.type === 'GROUP' ? GroupsOutlinedIcon : PersonOutlineIcon
        return (
          <Box
            component="li"
            key={`${subject.type}:${subject.id}`}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              pl: 1.25,
              pr: 0.5,
              py: 0.25,
              borderRadius: `${radius.sm}px`,
              bgcolor: 'action.hover',
            }}
          >
            <Icon aria-hidden="true" sx={{ fontSize: 18, color: 'text.secondary' }} />
            <Typography sx={{ fontSize: 13.5, flex: 1, minWidth: 0 }} noWrap>
              {subject.name}
            </Typography>
            <Typography component="span" sx={{ fontSize: 12, color: 'text.secondary' }}>
              {subject.type === 'GROUP' ? 'Gruppe' : 'Person'}
            </Typography>
            <IconButton
              size="small"
              aria-label={`${subject.name} entfernen`}
              onClick={() => onRemove(subject)}
            >
              <CloseIcon sx={{ fontSize: 16 }} />
            </IconButton>
          </Box>
        )
      })}
    </Stack>
  )
}

interface AccessEditorProps {
  entry: CapabilityOverviewResponse
  /** The end of the sentence "… dürfen Spaces anlegen". */
  phrase: string
  busy: boolean
  onCancel: () => void
  onSave: (target: Access) => Promise<void>
}

/**
 * The one question of the panel - who may create this - with its three answers. Nothing is sent
 * before "Speichern"; the sentence under the choice says what the state will be.
 */
export default function AccessEditor({ entry, phrase, busy, onCancel, onSave }: AccessEditorProps) {
  const questionId = useId()
  const initial = currentAccess(entry)
  const [level, setLevel] = useState<AccessLevel>(initial.level)
  const [subjects, setSubjects] = useState<NamedSubject[]>(initial.subjects)
  const [pickerKey, setPickerKey] = useState(0)

  const target: Access = { level, subjects }
  const unchanged = planAccessChange(entry, target).steps.length === 0
  const missingSubject = level === 'SELECTED' && subjects.length === 0

  function add(subject: NamedSubject) {
    setSubjects((current) =>
      current.some((known) => known.type === subject.type && known.id === subject.id)
        ? current
        : [...current, subject],
    )
    setPickerKey((key) => key + 1)
  }

  return (
    <Stack spacing={2.5}>
      <Box>
        <Typography sx={{ fontSize: 12, fontWeight: 600, color: 'text.secondary' }}>
          Stand jetzt
        </Typography>
        <Typography sx={{ fontSize: 14, mt: 0.25 }}>{resultSentence(initial, phrase)}</Typography>
      </Box>

      <Box>
        <Typography id={questionId} sx={{ fontSize: 14, fontWeight: 600, mb: 1 }}>
          Wer soll künftig dürfen?
        </Typography>
        <RadioGroup
          aria-labelledby={questionId}
          value={level}
          onChange={(event) => setLevel(event.target.value as AccessLevel)}
          sx={{ gap: 1 }}
        >
          {CHOICES.map((choice) => {
            const selected = level === choice.level
            return (
              <Box
                key={choice.level}
                sx={{
                  borderRadius: `${radius.md}px`,
                  bgcolor: selected ? (t) => alpha(t.palette.primary.main, 0.06) : 'transparent',
                  // The chosen answer is marked by a bar on its leading edge, not by a frame.
                  boxShadow: (t) => (selected ? `inset 3px 0 0 ${t.palette.primary.main}` : 'none'),
                  '&:hover': selected ? undefined : { bgcolor: 'action.hover' },
                  transition: (t) =>
                    t.transitions.create(['box-shadow', 'background-color'], {
                      duration: t.transitions.duration.shorter,
                    }),
                }}
              >
                <Box
                  component="label"
                  sx={{
                    display: 'flex',
                    alignItems: 'flex-start',
                    gap: 1,
                    p: 1.25,
                    cursor: 'pointer',
                  }}
                >
                  <Radio
                    value={choice.level}
                    size="small"
                    sx={{ mt: -0.5 }}
                    slotProps={{
                      input: {
                        'aria-labelledby': `${questionId}-${choice.level}`,
                        'aria-describedby': `${questionId}-${choice.level}-hint`,
                      },
                    }}
                  />
                  <Box>
                    <Typography
                      id={`${questionId}-${choice.level}`}
                      sx={{ fontSize: 14, fontWeight: 500 }}
                    >
                      {choice.title}
                    </Typography>
                    <Typography
                      id={`${questionId}-${choice.level}-hint`}
                      sx={{ fontSize: 12.5, color: 'text.secondary' }}
                    >
                      {choice.hint}
                    </Typography>
                  </Box>
                </Box>
                {choice.level === 'SELECTED' && selected && (
                  <Stack spacing={1.25} sx={{ px: 1.5, pb: 1.5 }}>
                    <SubjectPicker
                      key={pickerKey}
                      ariaLabel="Gruppe oder Person hinzufügen"
                      value={emptySubjectSelection}
                      excludedUserIds={subjects.filter((s) => s.type === 'USER').map((s) => s.id)}
                      excludedGroupIds={subjects.filter((s) => s.type === 'GROUP').map((s) => s.id)}
                      onChange={(selection) => {
                        if (selection.type === 'USER' && selection.user) {
                          const user = selection.user
                          add({
                            type: 'USER',
                            id: user.id,
                            name: user.displayName ?? user.email ?? user.id,
                          })
                        } else if (selection.type === 'GROUP' && selection.group) {
                          const group = selection.group
                          add({ type: 'GROUP', id: group.id, name: groupLabel(group), group })
                        }
                      }}
                    />
                    <SelectedSubjects
                      subjects={subjects}
                      onRemove={(subject) =>
                        setSubjects((current) =>
                          current.filter(
                            (known) => known.type !== subject.type || known.id !== subject.id,
                          ),
                        )
                      }
                    />
                  </Stack>
                )}
              </Box>
            )
          })}
        </RadioGroup>
      </Box>

      <Box
        role="status"
        sx={{
          p: 1.5,
          borderRadius: `${radius.md}px`,
          bgcolor: 'action.hover',
          borderLeft: 3,
          borderLeftColor: unchanged || missingSubject ? 'transparent' : 'primary.main',
        }}
      >
        <Typography sx={{ fontSize: 12, fontWeight: 600, color: 'text.secondary' }}>
          Danach
        </Typography>
        <Typography sx={{ fontSize: 14, mt: 0.25 }}>
          {missingSubject
            ? 'Wählen Sie mindestens eine Gruppe oder Person aus.'
            : unchanged
              ? 'Keine Änderung.'
              : resultSentence(target, phrase)}
        </Typography>
        {!unchanged && !missingSubject && (
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
            Die Änderung wirkt sofort, ohne neue Anmeldung, und wird protokolliert.
          </Typography>
        )}
      </Box>

      <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
        <Button onClick={onCancel} disabled={busy}>
          Abbrechen
        </Button>
        <Button
          variant="contained"
          disabled={busy || unchanged || missingSubject}
          onClick={() => void onSave(target)}
        >
          {busy ? 'Wird gespeichert …' : 'Speichern'}
        </Button>
      </Stack>
    </Stack>
  )
}
