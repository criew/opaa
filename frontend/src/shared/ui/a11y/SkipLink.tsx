import Link from '@mui/material/Link'
import { MAIN_CONTENT_ID } from './PageHeading'

/** First focusable element of the shell; hidden until focused, then jumps keyboard users past the navigation. */
export default function SkipLink() {
  return (
    <Link
      href={`#${MAIN_CONTENT_ID}`}
      onClick={(event) => {
        event.preventDefault()
        document.getElementById(MAIN_CONTENT_ID)?.focus()
      }}
      sx={(theme) => ({
        position: 'absolute',
        left: -9999,
        top: theme.spacing(2),
        zIndex: theme.zIndex.modal + 1,
        px: 2,
        py: 1,
        borderRadius: 1,
        bgcolor: 'background.paper',
        color: 'text.primary',
        border: 1,
        borderColor: 'divider',
        fontWeight: 600,
        textDecoration: 'none',
        '&:focus-visible': { left: theme.spacing(2) },
      })}
    >
      Zum Inhalt springen
    </Link>
  )
}
