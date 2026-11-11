import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { alpha, keyframes, useTheme } from '@mui/material/styles'
import { fontFamily, fontSize, motion, radius } from '@/shared/theme/tokens'

const markIn = keyframes`
  from { opacity: 0; transform: translateY(3px) scale(0.6); }
  to { opacity: 1; transform: none; }
`

function Mark({ n, delayMs }: { n: number; delayMs: number }) {
  const theme = useTheme()
  const tint = theme.palette.mode === 'dark' ? 0.22 : 0.1
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 16,
        height: 16,
        mx: 0.25,
        borderRadius: '50%',
        bgcolor: alpha(theme.palette.primary.main, tint),
        color: 'primary.main',
        fontFamily: fontFamily.mono,
        fontSize: fontSize.label,
        fontWeight: 600,
        verticalAlign: 'super',
        lineHeight: 1,
        animation: `${markIn} ${motion.durationBaseMs}ms ${motion.easeOut} ${delayMs}ms both`,
        '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
      }}
    >
      {n}
    </Box>
  )
}

/**
 * A fixed, inert copy of an answer with footnote marks for the first station: the tour needs no
 * sample chat, so it works on an empty installation. Hidden from assistive technology - the
 * station's sentence carries the meaning.
 */
export default function AnswerMiniature() {
  return (
    <Box
      aria-hidden
      sx={{
        bgcolor: 'background.default',
        border: 1,
        borderColor: 'divider',
        borderRadius: `${radius.md}px`,
        px: 2,
        py: 1.5,
        userSelect: 'none',
      }}
    >
      <Typography sx={{ fontSize: fontSize.text, lineHeight: 1.7, color: 'text.primary' }}>
        Fristverlängerungen bearbeitet das Referat 21 in zehn Arbeitstagen.
        <Mark n={1} delayMs={500} /> Für Eilfälle gilt die Dienstanweisung 4/2025.
        <Mark n={2} delayMs={800} />
      </Typography>
    </Box>
  )
}
