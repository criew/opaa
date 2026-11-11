import { useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import { TextAction } from '../DetailSection'
import ValueRow from '../ValueRow'
import BusyButton from '../a11y/BusyButton'

export interface SettingValueRowProps {
  /** Names the value: the row's title and the context of „Ändern“. */
  title: string
  /** The value in force, in words. */
  value: ReactNode
  /** Resets the draft from the saved state before the field opens. */
  onOpen: () => void
  /**
   * Saves the draft. Resolves `true` when the row may close - saved, or nothing was changed - and
   * `false` when it stays open: the entry is wrong, the question was declined or the save failed.
   * What is wrong is told at the field.
   */
  onSave: () => Promise<boolean>
  /** The field of the value, shown while the row is open. */
  editor: ReactNode
  /** Without the right to change, the row has no action. */
  canEdit?: boolean
  headingLevel?: 'h2' | 'h3' | 'h4'
  /** `wide` where the title is the name of a setting rather than one word. */
  titleWidth?: 'regular' | 'wide'
  /** Shown below the value while the row is closed, e.g. the way back to the factory default. */
  children?: ReactNode
}

/**
 * A single setting as a value row (guidelines 5.12, 5.16): title | value | „Ändern“. „Ändern“
 * opens the field below the value with „Speichern“ and „Abbrechen“; no save button stands beside
 * a field. After a save or „Abbrechen“ the focus returns to „Ändern“.
 */
export default function SettingValueRow({
  title,
  value,
  onOpen,
  onSave,
  editor,
  canEdit = true,
  headingLevel,
  titleWidth,
  children,
}: SettingValueRowProps) {
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const rowRef = useRef<HTMLDivElement>(null)
  const formId = useId()

  function close() {
    setOpen(false)
    requestAnimationFrame(() =>
      rowRef.current?.querySelector<HTMLElement>(`[aria-controls="${formId}"]`)?.focus(),
    )
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    event.stopPropagation()
    if (saving) return
    setSaving(true)
    try {
      if (await onSave()) close()
      else
        requestAnimationFrame(() =>
          rowRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(),
        )
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box ref={rowRef}>
      <ValueRow
        title={title}
        value={value}
        headingLevel={headingLevel}
        titleWidth={titleWidth}
        action={
          canEdit && !open ? (
            <TextAction
              aria-label={`${title} ändern`}
              aria-controls={formId}
              aria-expanded={false}
              onClick={() => {
                onOpen()
                setOpen(true)
              }}
            >
              Ändern
            </TextAction>
          ) : undefined
        }
      >
        {open ? (
          <Box
            id={formId}
            component="form"
            noValidate
            onSubmit={(event) => void handleSubmit(event)}
          >
            {editor}
            <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
              <BusyButton
                type="submit"
                variant="contained"
                size="small"
                busy={saving}
                busyAnnouncement="Wird gespeichert"
              >
                Speichern
              </BusyButton>
              <Button variant="text" size="small" disabled={saving} onClick={close}>
                Abbrechen
              </Button>
            </Stack>
          </Box>
        ) : (
          children
        )}
      </ValueRow>
    </Box>
  )
}
