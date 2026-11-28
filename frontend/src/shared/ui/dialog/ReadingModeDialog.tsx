import { useId, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import CloseIcon from '@mui/icons-material/Close'
import { fontSize, fontWeight } from '@/shared/theme/tokens'
import useFullScreenDialog from './useFullScreenDialog'

export interface ReadingModeDialogProps {
  /** Defaults to open: most views are mounted only while they show. */
  open?: boolean
  /** The name of what is read, usually a file name. */
  title: ReactNode
  /** The id of the title, where something outside refers to it; generated otherwise. */
  titleId?: string
  /** The way back: the arrow, the ✕ and Escape. */
  onClose: () => void
  /** Names the arrow after where it leads, „Zurück zur Antwort“ say. */
  backLabel: string
  /** Icon buttons for the whole view (a download, say), left of the ✕. */
  titleActions?: ReactNode
  /** The view below the header; it brings its own scrolling areas. */
  children: ReactNode
}

/**
 * A full-screen reading mode for one document (guidelines 5.6): a header with the way back, the
 * title and the view's actions, and below it the desk of the document view, light in the light
 * scheme and navy in the dark one. Below 600 px the way back is an arrow named by `backLabel`.
 */
export default function ReadingModeDialog({
  open = true,
  title,
  titleId,
  onClose,
  backLabel,
  titleActions,
  children,
}: ReadingModeDialogProps) {
  const generatedId = useId()
  const id = titleId ?? generatedId
  const phone = useFullScreenDialog()
  return (
    <Dialog
      open={open}
      onClose={onClose}
      fullScreen
      aria-labelledby={id}
      slotProps={{
        paper: {
          sx: {
            bgcolor: 'documentView.desk',
            backgroundImage: 'none',
            display: 'flex',
            flexDirection: 'column',
          },
        },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          flex: 'none',
          px: { xs: 1, sm: 2.5 },
          py: 1,
        }}
      >
        {phone ? (
          <Tooltip title={backLabel}>
            <IconButton onClick={onClose} aria-label={backLabel}>
              <ArrowBackIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        ) : (
          <Button
            onClick={onClose}
            startIcon={<ArrowBackIcon />}
            sx={{ flex: 'none', whiteSpace: 'nowrap', mr: 1 }}
          >
            {backLabel}
          </Button>
        )}
        <Box
          component="h2"
          id={id}
          sx={{
            m: 0,
            flex: 1,
            minWidth: 0,
            fontSize: fontSize.title,
            fontWeight: fontWeight.semibold,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {title}
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, flex: 'none' }}>
          {titleActions}
          <Tooltip title="Schließen">
            <IconButton onClick={onClose} aria-label="Schließen">
              <CloseIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        </Box>
      </Box>
      <Box sx={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>{children}</Box>
    </Dialog>
  )
}
