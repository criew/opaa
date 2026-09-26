import { useId, useState, type ReactNode } from 'react'
import Link from '@mui/material/Link'
import Popover from '@mui/material/Popover'
import Typography from '@mui/material/Typography'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'

/**
 * Ein Hinweis der Admin-Listen (#1978): ein kurzer Link mit Symbol in der Zeile der primären
 * Handlung, dahinter ein Popover mit Titel und Einzelheiten - statt eines farbigen Kastens über
 * der Liste. `tone="warning"` für einen Zustand, der eine Entscheidung braucht.
 */
export default function HintLink({
  text,
  title,
  tone = 'info',
  children,
}: {
  text: string
  title: string
  tone?: 'info' | 'warning'
  /** The details; receives `close` so an action inside can dismiss the popover. */
  children: (close: () => void) => ReactNode
}) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null)
  const popoverId = useId()
  const titleId = useId()
  const open = anchorEl !== null
  const close = () => setAnchorEl(null)
  const Icon = tone === 'warning' ? WarningAmberIcon : InfoOutlinedIcon

  return (
    <>
      <Link
        component="button"
        type="button"
        underline="hover"
        onClick={(e) => setAnchorEl(e.currentTarget)}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? popoverId : undefined}
        sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, fontSize: 13.5 }}
      >
        <Icon fontSize="small" sx={{ color: 'warning.main' }} aria-hidden />
        {text}
      </Link>
      <Popover
        id={popoverId}
        open={open}
        anchorEl={anchorEl}
        onClose={close}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
        transformOrigin={{ vertical: 'top', horizontal: 'left' }}
        slotProps={{
          paper: {
            role: 'dialog',
            'aria-labelledby': titleId,
            sx: { p: 2, mt: 0.5, width: 400, maxWidth: '90vw' },
          },
        }}
      >
        <Typography id={titleId} component="h2" sx={{ fontSize: 14, fontWeight: 600, mb: 0.75 }}>
          {title}
        </Typography>
        {children(close)}
      </Popover>
    </>
  )
}
