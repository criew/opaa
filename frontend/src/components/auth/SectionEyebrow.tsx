import Typography from '@mui/material/Typography'
import { fontFamily } from '../../theme/tokens'

/** The eyebrow that names a section of the sign-in card (guidelines 3.2). */
export default function SectionEyebrow({ id, children }: { id: string; children: string }) {
  return (
    <Typography
      id={id}
      component="h2"
      sx={{
        m: 0,
        fontFamily: fontFamily.mono,
        fontSize: 10,
        fontWeight: 500,
        letterSpacing: '0.08em',
        textTransform: 'uppercase',
        color: 'primary.main',
      }}
    >
      {children}
    </Typography>
  )
}
