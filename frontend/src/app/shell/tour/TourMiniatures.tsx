import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { alpha, keyframes, useTheme } from '@mui/material/styles'
import AllInclusiveIcon from '@mui/icons-material/AllInclusive'
import ArrowForwardIcon from '@mui/icons-material/ArrowForward'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import TextSnippetOutlinedIcon from '@mui/icons-material/TextSnippetOutlined'
import { fontFamily, motion, radius } from '@/shared/theme/tokens'

/**
 * Small, inert copies of the interface's own patterns for the welcome dialog (variant A): instead
 * of drawn illustrations the dialog shows what the person will meet - the footnote marks of an
 * answer, the input with @Space-Wissen, the space rows, the catalog tiles. All of it is
 * `aria-hidden`; the page's sentence carries the meaning.
 */
function Stage({ children }: { children: ReactNode }) {
  return (
    <Box
      aria-hidden
      sx={{
        minHeight: 176,
        bgcolor: 'background.default',
        border: 1,
        borderColor: 'divider',
        borderRadius: `${radius.lg}px`,
        px: { xs: 2, sm: 3 },
        py: 2,
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        gap: 1.5,
        userSelect: 'none',
      }}
    >
      {children}
    </Box>
  )
}

const markIn = keyframes`
  from { opacity: 0; transform: translateY(3px) scale(0.6); }
  to { opacity: 1; transform: none; }
`

function Mark({ n, delay }: { n: number; delay: number }) {
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
        fontSize: 10,
        fontWeight: 600,
        verticalAlign: 'super',
        lineHeight: 1,
        animation: `${markIn} ${motion.durationBaseMs}ms ${motion.easeOut} ${delay}ms both`,
        '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
      }}
    >
      {n}
    </Box>
  )
}

export function AnswerMiniature() {
  return (
    <Stage>
      <Typography sx={{ fontSize: 14, lineHeight: 1.7, color: 'text.primary' }}>
        Fristverlängerungen bearbeitet das Referat 21 in zehn Arbeitstagen.
        <Mark n={1} delay={500} /> Für Eilfälle gilt die Dienstanweisung 4/2025.
        <Mark n={2} delay={800} />
      </Typography>
      <Box
        sx={{
          height: '1px',
          background: (t) => `linear-gradient(90deg, ${t.palette.divider}, transparent)`,
        }}
      />
      <Box
        sx={{
          alignSelf: 'flex-start',
          display: 'inline-flex',
          alignItems: 'center',
          gap: 1,
          border: 1,
          borderColor: 'divider',
          borderRadius: `${radius.pill}px`,
          pl: 0.75,
          pr: 1.25,
          py: 0.5,
        }}
      >
        <Box sx={{ display: 'inline-flex' }}>
          <Mark n={1} delay={1100} />
          <Mark n={2} delay={1200} />
        </Box>
        <Typography component="span" sx={{ fontSize: 13, fontWeight: 500 }}>
          Belege anzeigen
        </Typography>
        <ArrowForwardIcon sx={{ fontSize: 14, color: 'primary.main' }} />
      </Box>
    </Stage>
  )
}

export function InputMiniature() {
  return (
    <Stage>
      <Box
        sx={{
          alignSelf: 'flex-start',
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.5,
          bgcolor: 'primary.dark',
          color: 'primary.contrastText',
          borderRadius: `${radius.pill}px`,
          px: 1,
          py: 0.25,
          fontSize: 12,
          fontWeight: 500,
        }}
      >
        <AllInclusiveIcon sx={{ fontSize: 14 }} />
        @Space-Wissen
      </Box>
      <Box
        sx={{
          border: 2,
          borderColor: 'primary.main',
          borderRadius: `${radius.md}px`,
          px: 2,
          py: 1.5,
          fontSize: 14,
          color: 'text.primary',
          bgcolor: 'background.paper',
        }}
      >
        Wie lange dauert eine Fristverlängerung?
      </Box>
    </Stage>
  )
}

function SpaceRow({ letter, name, active }: { letter: string; name: string; active?: boolean }) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.25,
        px: 1.25,
        py: 1,
        borderRadius: `${radius.sm}px`,
        border: 1,
        borderColor: active ? 'action.disabled' : 'transparent',
        bgcolor: active ? 'background.paper' : 'transparent',
      }}
    >
      <Box
        sx={{
          width: 20,
          height: 20,
          border: 1,
          borderColor: active ? 'primary.main' : 'divider',
          color: active ? 'primary.main' : 'text.secondary',
          borderRadius: `${radius.xs}px`,
          fontSize: 11,
          fontWeight: 600,
          display: 'grid',
          placeItems: 'center',
        }}
      >
        {letter}
      </Box>
      <Typography sx={{ fontSize: 14, fontWeight: active ? 500 : 400 }}>{name}</Typography>
    </Box>
  )
}

export function SpacesMiniature() {
  return (
    <Stage>
      <Box sx={{ width: 220, display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        <SpaceRow letter="P" name="Privater Bereich" active />
        <SpaceRow letter="B" name="Bauordnung" />
        <SpaceRow letter="H" name="Haushalt 2027" />
      </Box>
    </Stage>
  )
}

function MiniTile({
  kind,
  name,
  meta,
  icon,
}: {
  kind: string
  name: string
  meta: string
  icon: ReactNode
}) {
  return (
    <Box
      sx={{
        flex: 1,
        minWidth: 0,
        border: 1,
        borderColor: 'divider',
        borderRadius: `${radius.md}px`,
        bgcolor: 'background.paper',
        p: 1.5,
        display: 'flex',
        flexDirection: 'column',
        gap: 0.5,
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.5,
          color: 'text.secondary',
          fontSize: 12,
        }}
      >
        {icon}
        {kind}
      </Box>
      <Typography sx={{ fontSize: 14, fontWeight: 600 }} noWrap>
        {name}
      </Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>{meta}</Typography>
    </Box>
  )
}

export function CatalogMiniature() {
  return (
    <Stage>
      <Box sx={{ display: 'flex', gap: 1.5 }}>
        <MiniTile
          kind="Wissen"
          name="Dienstanweisungen"
          meta="128 Dokumente"
          icon={<MenuBookOutlinedIcon sx={{ fontSize: 14 }} />}
        />
        <MiniTile
          kind="Prompts"
          name="Bescheide prüfen"
          meta="6 Prompts"
          icon={<TextSnippetOutlinedIcon sx={{ fontSize: 14 }} />}
        />
      </Box>
    </Stage>
  )
}
