import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import type { SxProps, Theme } from '@mui/material/styles'
import { notify } from '../../stores/notificationStore'
import { formWidth } from '../../theme/tokens'
import BusyButton from '../a11y/BusyButton'
import { useLeaveGuard } from './useLeaveGuard'

export interface SettingsFormProps {
  /**
   * Saves the entries. Called only with changes; what is wrong is told at its field
   * (`aria-invalid`), and the focus goes into the first such field.
   */
  onSave: () => void | Promise<void>
  /** True while the save runs; „Speichern“ then swallows further clicks. */
  busy: boolean
  busyAnnouncement?: string
  /** Whether the entries differ from the saved state. */
  dirty: boolean
  /** Returns the entries to the saved state. */
  onDiscard: () => void
  /** At most one secondary action beside „Speichern“: {@link ConnectionTestButton}. */
  secondaryAction?: ReactNode
  /** What the secondary action found, or a save error without a field (5.16), below the footer. */
  result?: ReactNode
  /** Names the form where no heading around it does. */
  'aria-label'?: string
  'aria-labelledby'?: string
  /** Placement only; the footer and the column width are fixed. */
  sx?: SxProps<Theme>
  children: ReactNode
}

/**
 * The form of a settings page or tab with several fields (guidelines 5.16): one column of fields
 * and a fixed footer at the lower left - „Speichern“ (primary, always active, labelled by the
 * verb alone), at most one secondary action, and a quiet „Verwerfen“ only while something is
 * changed. Leaving with changes asks first. What takes effect only with „Speichern“ is a checkbox
 * here, never a switch; a save is acknowledged by the caller through `notify`.
 */
export default function SettingsForm({
  onSave,
  busy,
  busyAnnouncement = 'Einstellungen werden gespeichert',
  dirty,
  onDiscard,
  secondaryAction,
  result,
  sx,
  children,
  ...aria
}: SettingsFormProps) {
  const formRef = useRef<HTMLFormElement>(null)
  const [submits, setSubmits] = useState(0)
  useLeaveGuard(dirty)

  // After a submit the focus goes into the first field that reported an error.
  useEffect(() => {
    if (submits === 0) return
    const invalid = formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')
    // a radio group is not focusable itself: its chosen or first radio is
    const radio =
      invalid?.getAttribute('role') === 'radiogroup'
        ? (invalid.querySelector<HTMLElement>('input:checked') ??
          invalid.querySelector<HTMLElement>('input'))
        : null
    ;(radio ?? invalid)?.focus()
  }, [submits])

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    event.stopPropagation()
    if (busy) return
    if (!dirty) {
      notify('Es gibt keine Änderungen zu speichern.', 'info')
      return
    }
    void Promise.resolve(onSave()).finally(() => setSubmits((count) => count + 1))
  }

  return (
    <Box
      ref={formRef}
      component="form"
      noValidate
      onSubmit={handleSubmit}
      {...aria}
      sx={[{ maxWidth: formWidth.column }, ...(Array.isArray(sx) ? sx : [sx])]}
    >
      {children}
      <Stack
        direction="row"
        spacing={1.5}
        useFlexGap
        sx={{ mt: 3, alignItems: 'center', flexWrap: 'wrap' }}
      >
        <BusyButton
          type="submit"
          variant="contained"
          busy={busy}
          busyAnnouncement={busyAnnouncement}
        >
          Speichern
        </BusyButton>
        {secondaryAction}
        {dirty && (
          <Button variant="text" disabled={busy} onClick={onDiscard}>
            Verwerfen
          </Button>
        )}
      </Stack>
      {result && <Box sx={{ mt: 2 }}>{result}</Box>}
    </Box>
  )
}
