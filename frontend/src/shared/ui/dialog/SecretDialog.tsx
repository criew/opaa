import { useId, type ReactNode } from 'react'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import ContentCopyOutlinedIcon from '@mui/icons-material/ContentCopyOutlined'
import { DIALOG_MAX_WIDTH, DIALOG_TITLE_SX, type DialogWidth } from './dialogWidth'
import useFullScreenDialog from './useFullScreenDialog'

export interface SecretDialogProps {
  /** Defaults to open: most dialogs are mounted only while they show. */
  open?: boolean
  title: ReactNode
  /** The id of the title, where something outside refers to it; generated otherwise. */
  titleId?: string
  /** Copies the one secret of the dialog; left out where the content carries its own copy buttons. */
  onCopy?: () => void
  /** Only „Schließen“ closes: the secret is never shown again. */
  onClose: () => void
  /** 600 px unless the secret comes with a longer instruction (900 px). */
  width?: Exclude<DialogWidth, 'confirm'>
  'aria-describedby'?: string
  children: ReactNode
}

/**
 * The one exception among the views (guidelines 5.6): a dialog that shows a secret exactly once
 * (a token, a generated password, a setup link). It has no ✕ and ignores Escape and a click
 * beside it, so the secret is not lost by accident; its footer carries „Kopieren“ (secondary)
 * and „Schließen“ (primary).
 */
export default function SecretDialog({
  open = true,
  title,
  titleId,
  onCopy,
  onClose,
  width = 'form',
  'aria-describedby': describedBy,
  children,
}: SecretDialogProps) {
  const generatedId = useId()
  const id = titleId ?? generatedId
  const fullScreen = useFullScreenDialog()
  return (
    <Dialog
      open={open}
      maxWidth={DIALOG_MAX_WIDTH[width]}
      fullWidth
      fullScreen={fullScreen}
      aria-labelledby={id}
      aria-describedby={describedBy}
    >
      <DialogTitle id={id} sx={DIALOG_TITLE_SX}>
        {title}
      </DialogTitle>
      <DialogContent>{children}</DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        {onCopy && (
          <Button variant="outlined" startIcon={<ContentCopyOutlinedIcon />} onClick={onCopy}>
            Kopieren
          </Button>
        )}
        <Button variant="contained" onClick={onClose}>
          Schließen
        </Button>
      </DialogActions>
    </Dialog>
  )
}
