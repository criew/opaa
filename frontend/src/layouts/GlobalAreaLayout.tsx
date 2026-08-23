import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { NavLink, Outlet, useLocation } from 'react-router'
import GlobalBadge from '../components/GlobalBadge'
import { darkRoles, lightRoles } from '../theme/tokens'

interface GlobalAreaSection {
  label: string
  to: string
}

interface GlobalAreaLayoutProps {
  /** Heading of the secondary column; also names its nav landmark. */
  title?: string
  /** Column entries. Without sections no column renders - the frame is just the light surface. */
  sections?: GlobalAreaSection[]
}

/**
 * The global frame (#787, mockup 2b): right of the rail the space column falls away and a
 * light management surface takes over - a secondary column with title and "Global" badge where
 * an area has several pages, and the routed page as the main surface. Reused without sections
 * by the user settings (#788) and the library catalog (#789).
 */
export default function GlobalAreaLayout({ title, sections }: GlobalAreaLayoutProps) {
  const location = useLocation()
  const theme = useTheme()
  // bg2 (the raised light surface) has no palette slot - buildTheme maps bg1 onto both
  // background.default and background.paper - so the column reads its role directly.
  const roles = theme.palette.mode === 'light' ? lightRoles : darkRoles

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: { xs: 'column', md: 'row' },
        flexGrow: 1,
        minHeight: 0,
      }}
    >
      {sections && (
        <Box
          component="nav"
          aria-label={title}
          sx={{
            width: { md: 232 },
            flexShrink: 0,
            bgcolor: roles.bg2,
            borderRight: { md: 1 },
            borderBottom: { xs: 1, md: 0 },
            borderColor: { xs: 'divider', md: 'divider' },
            px: '12px',
            py: { xs: 1.5, md: '18px' },
            display: 'flex',
            flexDirection: { xs: 'row', md: 'column' },
            alignItems: { xs: 'center', md: 'stretch' },
            gap: { xs: 1.5, md: '1px' },
            overflowX: { xs: 'auto', md: 'visible' },
          }}
        >
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: '7px',
              px: 1,
              pb: { md: '12px' },
              flex: 'none',
            }}
          >
            <Typography component="span" sx={{ fontSize: 14.5, fontWeight: 600 }}>
              {title}
            </Typography>
            <GlobalBadge />
          </Box>
          {sections.map((section) => {
            const active = location.pathname === section.to
            return (
              <Box
                key={section.to}
                component={NavLink}
                to={section.to}
                aria-current={active ? 'page' : undefined}
                sx={{
                  display: 'block',
                  flex: 'none',
                  px: '10px',
                  py: '7px',
                  borderRadius: '6px',
                  fontSize: 12.5,
                  textDecoration: 'none',
                  // Mockup 2b: the active entry is the raised card - page ground on the
                  // muted column, framed by the standard border.
                  color: active ? 'text.primary' : 'text.secondary',
                  fontWeight: active ? 500 : 400,
                  bgcolor: active ? roles.bg1 : 'transparent',
                  border: 1,
                  borderColor: active ? 'divider' : 'transparent',
                  '&:hover': { bgcolor: active ? roles.bg1 : roles.bg3 },
                }}
              >
                {section.label}
              </Box>
            )
          })}
        </Box>
      )}
      <Box
        sx={{
          flexGrow: 1,
          minWidth: 0,
          minHeight: 0,
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <Outlet />
      </Box>
    </Box>
  )
}
