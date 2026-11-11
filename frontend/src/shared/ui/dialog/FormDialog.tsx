import {
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
  type ReactNode,
  type RefObject,
} from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import type { PaperProps } from '@mui/material/Paper'
import type { SxProps, Theme } from '@mui/material/styles'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { DIALOG_MAX_WIDTH, DIALOG_TITLE_SX, type DialogWidth } from './dialogWidth'
import useFullScreenDialog from './useFullScreenDialog'

export interface FormDialogProps {
  /** Defaults to open: most dialogs are mounted only while they show. */
  open?: boolean
  /**
   * Literally the label of the button that opened the dialog („Gruppe anlegen“); when editing
   * „<Name> bearbeiten“.
   */
  title: ReactNode
  /** The id of the title, where something outside refers to it; generated otherwise. */
  titleId?: string
  /** The verb alone: „Anlegen“, „Speichern“. */
  submitLabel: string
  /**
   * Called on every submit that is not already running. Missing or wrong input is reported at
   * its field (`error` on the field); the dialog then moves the focus into the first such field.
   */
  onSubmit: () => void
  /** „Abbrechen“, Escape and a click beside the dialog. Not called while `busy`. */
  onClose: () => void
  /** True while the action runs: the only state in which the action is not available. */
  busy?: boolean
  /** Announced to assistive technology when the action starts. */
  busyAnnouncement?: string
  /** At most one secondary action at the left of the footer („Verbindung testen“). */
  secondaryAction?: ReactNode
  /** 600 px unless the dialog is an editor with a preview or a list (900 px). */
  width?: DialogWidth
  /**
   * A confirmation that needs more than the overlay of `confirmAction` (5.10): the signal edge of
   * its tone; with `danger` the action is a danger surface and the initial focus is on „Abbrechen“.
   */
  tone?: 'danger' | 'caution'
  /** A dialog that currently offers nothing to submit shows „Abbrechen“ alone. */
  hideSubmit?: boolean
  /** Called when the dialog starts to open, to reset its fields. */
  onEnter?: () => void
  /** A line between title and content that does not scroll with it (a search, a path). */
  toolbar?: ReactNode
  /**
   * False where Enter in a line field must not submit, because the field filters or adds to a
   * list (a selection dialog). The action then needs its button.
   */
  submitOnEnter?: boolean
  contentSx?: SxProps<Theme>
  contentRef?: RefObject<HTMLDivElement | null>
  'aria-describedby'?: string
  children: ReactNode
}

/**
 * A dialog that creates or changes something (guidelines 5.6): the title, the content and a
 * footer with fixed places - at most one secondary action on the left, „Abbrechen“ (quiet) and
 * the action (primary) on the right. It closes through „Abbrechen“ and has no ✕. The action is
 * a form submit and is unavailable only while it runs; it cannot be disabled for missing input.
 * Below 600 px viewport width the dialog fills the screen.
 */
export default function FormDialog({
  open = true,
  title,
  titleId,
  submitLabel,
  onSubmit,
  onClose,
  busy = false,
  busyAnnouncement = 'Wird ausgeführt',
  secondaryAction,
  width = 'form',
  tone,
  hideSubmit = false,
  onEnter,
  toolbar,
  submitOnEnter = true,
  contentSx,
  contentRef: outerContentRef,
  'aria-describedby': describedBy,
  children,
}: FormDialogProps) {
  const generatedId = useId()
  const id = titleId ?? generatedId
  const fullScreen = useFullScreenDialog()
  const ownContentRef = useRef<HTMLDivElement>(null)
  const contentRef = outerContentRef ?? ownContentRef
  const [submits, setSubmits] = useState(0)
  const danger = tone === 'danger'

  // After a submit the focus goes into the first field that reported an error.
  useEffect(() => {
    if (submits === 0) return
    const invalid = contentRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')
    // a radio group is not focusable itself: its chosen or first radio is
    const radio =
      invalid?.getAttribute('role') === 'radiogroup'
        ? (invalid.querySelector<HTMLElement>('input:checked') ??
          invalid.querySelector<HTMLElement>('input'))
        : null
    ;(radio ?? invalid)?.focus()
  }, [submits, contentRef])

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    // React lets the event bubble through the portal into a form around the opening button.
    event.stopPropagation()
    // Only the footer action submits: a plain <button> in the content has no `type` and would
    // otherwise count as a submit button of the form.
    const submitter = (event.nativeEvent as SubmitEvent).submitter
    if (submitter && submitter.getAttribute('type') !== 'submit') return
    // Enter in a field must not run an action the dialog does not offer
    if (busy || hideSubmit) return
    onSubmit()
    setSubmits((count) => count + 1)
  }

  // The paper is the form, so title, content and footer keep the dialog's own layout.
  const formPaper: PaperProps<'form'> = {
    component: 'form',
    noValidate: true,
    onSubmit: handleSubmit,
    onKeyDown: submitOnEnter
      ? undefined
      : (event: KeyboardEvent) => {
          if (event.key === 'Enter' && event.target instanceof HTMLInputElement) {
            event.preventDefault()
          }
        },
    sx: tone && { borderLeft: 3, borderLeftColor: danger ? 'error.main' : 'warning.main' },
  }

  return (
    <Dialog
      open={open}
      onClose={busy ? undefined : onClose}
      maxWidth={DIALOG_MAX_WIDTH[width]}
      fullWidth
      fullScreen={fullScreen}
      aria-labelledby={id}
      aria-describedby={describedBy}
      slotProps={{ paper: formPaper, transition: { onEnter } }}
    >
      <DialogTitle id={id} sx={DIALOG_TITLE_SX}>
        {title}
      </DialogTitle>
      {toolbar}
      <DialogContent ref={contentRef} sx={contentSx}>
        {children}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2, flexWrap: 'wrap', rowGap: 1 }}>
        {secondaryAction && <Box sx={{ mr: 'auto' }}>{secondaryAction}</Box>}
        {/* For an irreversible action the initial focus is on the cancel button. */}
        {/* eslint-disable-next-line jsx-a11y-x/no-autofocus */}
        <Button variant="text" onClick={onClose} disabled={busy} autoFocus={danger}>
          Abbrechen
        </Button>
        {!hideSubmit && (
          <BusyButton
            type="submit"
            variant="contained"
            color={danger ? 'error' : 'primary'}
            busy={busy}
            busyAnnouncement={busyAnnouncement}
          >
            {submitLabel}
          </BusyButton>
        )}
      </DialogActions>
    </Dialog>
  )
}
