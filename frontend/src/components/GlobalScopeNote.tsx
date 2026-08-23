import Typography from '@mui/material/Typography'

/**
 * The scope sentence global administration pages carry under their heading (#787, mockup 2b) -
 * one component so the wording cannot drift between pages.
 */
export default function GlobalScopeNote() {
  return (
    <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: -1.5, mb: 2.5 }}>
      Gilt für die gesamte Anwendung. Änderungen wirken sich auf alle Spaces und Benutzer aus.
    </Typography>
  )
}
