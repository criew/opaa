import { useId, useRef, useState } from 'react'
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

/**
 * The named persons and groups with their remove buttons. Removing one moves the focus to the next
 * remove button, or to `onEmptied` when the list runs empty - never to the top of the panel.
 */
function SelectedSubjects({
  subjects,
  onRemove,
  onEmptied,
}: {
  subjects: NamedSubject[]
  onRemove: (subject: NamedSubject) => void
  onEmptied: () => void
}) {
  const listRef = useRef<HTMLUListElement>(null)
  if (subjects.length === 0) return null

  function remove(subject: NamedSubject, index: number) {
    onRemove(subject)
    requestAnimationFrame(() => {
      const buttons = listRef.current?.querySelectorAll<HTMLButtonElement>('button[data-remove]')
      if (buttons && buttons.length > 0) buttons[Math.min(index, buttons.length - 1)].focus()
      else onEmptied()
    })
  }

  return (
    <Stack ref={listRef} component="ul" spacing={0.5} sx={{ listStyle: 'none', p: 0, m: 0 }}>
      {subjects.map((subject, index) => {
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
              data-remove=""
              aria-label={`${subject.name} entfernen`}
              onClick={() => remove(subject, index)}
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
  const rootRef = useRef<HTMLDivElement>(null)

  const target: Access = { level, subjects }
  const plan = planAccessChange(entry, target)
  const unchanged = plan.steps.length === 0
  // Under all accounts the only possible change besides opening is removing resting entries.
  const removedUnderAll =
    level === 'ALL'
      ? plan.steps.filter((step) => step.kind === 'REVOKE').map((step) => step.name)
      : []
  const missingSubject = level === 'SELECTED' && subjects.length === 0

  function removeSubject(subject: NamedSubject) {
    setSubjects((current) =>
      current.filter((known) => known.type !== subject.type || known.id !== subject.id),
    )
  }

  /** Where the focus goes when the last named subject is removed. */
  function focusAfterEmptied(selector: string) {
    rootRef.current?.querySelector<HTMLElement>(selector)?.focus()
  }

  function add(subject: NamedSubject) {
    setSubjects((current) =>
      current.some((known) => known.type === subject.type && known.id === subject.id)
        ? current
        : [...current, subject],
    )
    setPickerKey((key) => key + 1)
  }

  return (
    <Stack ref={rootRef} spacing={2.5}>
      <Box>
        <Typography sx={{ fontSize: 12, fontWeight: 600, color: 'text.secondary' }}>
          Stand jetzt
        </Typography>
        {/* The backend's plain-text line of the state (ADR-0036, Entscheidung 5). */}
        <Typography sx={{ fontSize: 14, mt: 0.25 }}>{entry.statement}</Typography>
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
                      onRemove={removeSubject}
                      onEmptied={() =>
                        focusAfterEmptied('input[aria-label="Gruppe oder Person hinzufügen"]')
                      }
                    />
                  </Stack>
                )}
                {choice.level === 'ALL' && selected && subjects.length > 0 && (
                  <Stack spacing={1} sx={{ px: 1.5, pb: 1.5 }}>
                    <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                      Zusätzlich eingetragen. Solange alle Konten dürfen, wirken diese Einträge
                      nicht; sie gelten wieder, wenn Sie das Recht einschränken. Nicht mehr
                      benötigte Einträge können Sie hier entfernen.
                    </Typography>
                    <SelectedSubjects
                      subjects={subjects}
                      onRemove={removeSubject}
                      onEmptied={() => focusAfterEmptied('input[type="radio"][value="ALL"]')}
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
        {removedUnderAll.length > 0 && (
          <Typography sx={{ fontSize: 13.5, mt: 0.25 }}>
            Entfernt {removedUnderAll.length === 1 ? 'wird der Eintrag' : 'werden die Einträge'}{' '}
            {removedUnderAll.join(', ')}.
          </Typography>
        )}
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
