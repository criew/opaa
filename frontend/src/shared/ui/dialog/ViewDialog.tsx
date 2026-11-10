import { useId, type ReactNode, type Ref } from 'react'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import type { SxProps, Theme } from '@mui/material/styles'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import { DIALOG_MAX_WIDTH, type DialogWidth } from './dialogWidth'
import useFullScreenDialog from './useFullScreenDialog'

export interface ViewDialogProps {
  /** Defaults to open: most dialogs are mounted only while they show. */
  open?: boolean
  /** Literally the label of the button or link that opened the view, or the name of the object. */
  title: ReactNode
  /** The id of the title, where something outside refers to it; generated otherwise. */
  titleId?: string
  /** The ✕, Escape and a click beside the dialog. Not called while `busy`. */
  onClose: () => void
  /** True while an action inside the view runs: the view then stays open. */
  busy?: boolean
  /** Icon buttons for the whole view (a download, say), left of the ✕. */
  titleActions?: ReactNode
  /** A line between title and content that does not scroll with it (a status, a toggle). */
  toolbar?: ReactNode
  /** The view takes 90 % of the viewport height from the start (a document preview). */
  tall?: boolean
  /** 600 px unless the view is a preview or a list (900 px). */
  width?: Exclude<DialogWidth, 'confirm'>
  contentSx?: SxProps<Theme>
  contentRef?: Ref<HTMLDivElement>
  'aria-describedby'?: string
  children: ReactNode
}

/**
 * A dialog that only shows something (a view, a preview, a list; guidelines 5.6): it closes
 * through the ✕ in its title and has neither a footer nor a button „Schließen“. Actions inside
 * the view belong to its content. Below 600 px viewport width the dialog fills the screen.
 */
export default function ViewDialog({
  open = true,
  title,
  titleId,
  onClose,
  busy = false,
  titleActions,
  toolbar,
  tall = false,
  width = 'form',
  contentSx,
  contentRef,
  'aria-describedby': describedBy,
  children,
}: ViewDialogProps) {
  const generatedId = useId()
  const id = titleId ?? generatedId
  const fullScreen = useFullScreenDialog()
  return (
    <Dialog
      open={open}
      onClose={busy ? undefined : onClose}
      maxWidth={DIALOG_MAX_WIDTH[width]}
      fullWidth
      fullScreen={fullScreen}
      aria-labelledby={id}
      aria-describedby={describedBy}
      slotProps={{ paper: { sx: tall && !fullScreen ? { height: '90vh' } : undefined } }}
    >
      <DialogTitleWithClose id={id} onClose={onClose} actions={titleActions} disabled={busy}>
        {title}
      </DialogTitleWithClose>
      {toolbar}
      <DialogContent
        ref={contentRef}
        sx={[{ pb: 3 }, ...(Array.isArray(contentSx) ? contentSx : [contentSx])]}
      >
        {children}
      </DialogContent>
    </Dialog>
  )
}
