import type { ReactNode } from 'react'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import Box from '@mui/material/Box'
import CloseIcon from '@mui/icons-material/Close'

interface DialogTitleWithCloseProps {
  /** The id the dialog's `aria-labelledby` points at. */
  id: string
  onClose: () => void
  children: ReactNode
}

/**
 * The title of a dialog without a cancel button of its own (a view, a preview, a list of events):
 * the title on the left, a ✕ named „Schließen“ at the top right. Escape and a click beside the
 * dialog still close it; a form dialog with „Abbrechen“ keeps that as its only way out.
 */
export default function DialogTitleWithClose({ id, onClose, children }: DialogTitleWithCloseProps) {
  return (
    <DialogTitle
      component="div"
      sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 1 }}
    >
      {/* the id sits on the text, so the dialog's name leaves out the ✕ */}
      <Box
        component="h2"
        id={id}
        sx={{ m: 0, font: 'inherit', minWidth: 0, overflowWrap: 'anywhere' }}
      >
        {children}
      </Box>
      <IconButton
        onClick={onClose}
        aria-label="Schließen"
        title="Schließen"
        sx={{ flex: 'none', mt: -0.75, mr: -1.5 }}
      >
        <CloseIcon fontSize="small" />
      </IconButton>
    </DialogTitle>
  )
}
