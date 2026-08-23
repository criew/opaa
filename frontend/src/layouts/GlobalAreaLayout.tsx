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

/**
 * Either a full secondary column (title + sections) or none at all: the title names the
 * column's nav landmark, so sections without a title would produce an unnamed landmark
 * (#800, review #794 finding 2) - the union makes that state unrepresentable.
 */
type GlobalAreaLayoutProps =
  | {
      /** Heading of the secondary column; also names its nav landmark. */
      title: string
      sections: GlobalAreaSection[]
    }
  | { title?: undefined; sections?: undefined }

/**
 * The global frame (#787, mockup 2b): right of the rail the space column falls away and a
 * light management surface takes over - a secondary column with title and "Global" badge where
 * an area has several pages, and the routed page as the main surface. Reused without sections
 * by the user settings (#788) and the library catalog (#789).
 *
 * Every route rendered inside this layout must also be listed in GLOBAL_AREA_PREFIXES
 * (globalArea.ts) - the prefix list is what removes the space column in AppShell; this layout
 * only adds the frame. Keeping both in sync is manual (#800, review #794 finding 2).
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
            // Mockup 2b outlines the column edge one step stronger than the standard rule.
            borderColor: roles.borderStrong,
            px: '12px',
            py: { xs: 1.5, md: '18px' },
            display: 'flex',
            flexDirection: 'column',
            gap: { xs: 1, md: '1px' },
          }}
        >
          <Box
            sx={{ display: 'flex', alignItems: 'center', gap: '7px', px: 1, pb: { md: '12px' } }}
          >
            <Typography component="span" sx={{ fontSize: 14.5, fontWeight: 600 }}>
              {title}
            </Typography>
            <GlobalBadge />
          </Box>
          {/* On phones the entries wrap below the title instead of scrolling sideways - a
              320px viewport must show every destination (#800, review #794 finding 1). */}
          <Box
            sx={{
              display: 'flex',
              flexDirection: { xs: 'row', md: 'column' },
              flexWrap: { xs: 'wrap', md: 'nowrap' },
              gap: { xs: 0.5, md: '1px' },
            }}
          >
            {sections.map((section) => {
              // Prefix match like the rail: a future subroute keeps its section marked.
              const exact = location.pathname === section.to
              const active = exact || location.pathname.startsWith(`${section.to}/`)
              return (
                <Box
                  key={section.to}
                  component={NavLink}
                  to={section.to}
                  aria-current={active ? (exact ? 'page' : 'true') : undefined}
                  sx={{
                    display: 'block',
                    flex: 'none',
                    px: '10px',
                    py: '7px',
                    borderRadius: '6px',
                    fontSize: 12.5,
                    textDecoration: 'none',
                    // Mockup 2b: the active entry is the raised card - page ground on the
                    // muted column, framed one step stronger; inactive entries lift to the
                    // page ground on hover.
                    color: active ? 'text.primary' : 'text.secondary',
                    fontWeight: active ? 500 : 400,
                    bgcolor: active ? roles.bg1 : 'transparent',
                    border: 1,
                    borderColor: active ? roles.borderStrong : 'transparent',
                    '&:hover': { bgcolor: roles.bg1 },
                  }}
                >
                  {section.label}
                </Box>
              )
            })}
          </Box>
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
