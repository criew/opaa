import { Suspense } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import { NavLink, Outlet, useLocation } from 'react-router'
import GlobalBadge from '@/shared/ui/GlobalBadge'
import { darkRoles, fontSize, lightRoles } from '@/shared/theme/tokens'
import AreaSectionRow from './AreaSectionRow'
import { sectionState, type GlobalAreaSection } from './areaSections'
import PageLoading from './PageLoading'

/**
 * Either a full secondary column (title + sections) or none at all: the title names the
 * column's nav landmark, so sections without a title would produce an unnamed landmark -
 * the union makes that state unrepresentable.
 */
type GlobalAreaLayoutProps =
  | {
      /** Heading of the secondary column; also names its nav landmark. */
      title: string
      sections: GlobalAreaSection[]
    }
  | { title?: undefined; sections?: undefined }

/**
 * The global frame: right of the rail the space column falls away and a
 * light management surface takes over - a secondary column with title and "Global" badge where
 * an area has several pages, and the routed page as the main surface. Reused without sections
 * by the user settings and the library catalog.
 *
 * Every route rendered inside this layout must also be covered by the prefixes in
 * globalArea.ts ("/spaces" must never become one: the overview, every space and chat below it
 * keep the space column, guidelines 5.13). The list is what removes the space column in
 * AppShell; this layout only adds the frame. Keeping routes and list in sync is manual.
 */
export default function GlobalAreaLayout({ title, sections }: GlobalAreaLayoutProps) {
  const location = useLocation()
  const theme = useTheme()
  // bg2 (the raised light surface) has no palette slot - buildTheme maps bg1 onto both
  // background.default and background.paper - so the column reads its role directly.
  const roles = theme.palette.mode === 'light' ? lightRoles : darkRoles
  // Below 900 px the column gives way to one row above the page (guidelines 4.7); only
  // AreaSectionRow knows its form.
  const compact = useMediaQuery(theme.breakpoints.down('md'))

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: { xs: 'column', md: 'row' },
        flexGrow: 1,
        minHeight: 0,
      }}
    >
      {sections && compact && <AreaSectionRow title={title} sections={sections} />}
      {sections && !compact && (
        <Box
          component="nav"
          aria-label={title}
          sx={{
            width: 232,
            flexShrink: 0,
            bgcolor: roles.bg2,
            borderRight: 1,
            // The design outlines the column edge one step stronger than the standard rule.
            borderColor: roles.borderStrong,
            px: '12px',
            py: '18px',
            display: 'flex',
            flexDirection: 'column',
            gap: '1px',
          }}
        >
          <Box sx={{ display: 'flex', alignItems: 'center', gap: '7px', px: 1, pb: '12px' }}>
            <Typography component="span" sx={{ fontSize: fontSize.text, fontWeight: 600 }}>
              {title}
            </Typography>
            <GlobalBadge />
          </Box>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: '1px' }}>
            {sections.map((section) => {
              const { active, current } = sectionState(section, location.pathname)
              return (
                <Box
                  key={section.to}
                  component={NavLink}
                  to={section.to}
                  aria-current={current}
                  sx={{
                    display: 'block',
                    px: '10px',
                    py: '7px',
                    borderRadius: '6px',
                    fontSize: fontSize.meta,
                    textDecoration: 'none',
                    // The active entry is the raised card - page ground on the
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
        <Suspense fallback={<PageLoading />}>
          <Outlet />
        </Suspense>
      </Box>
    </Box>
  )
}
