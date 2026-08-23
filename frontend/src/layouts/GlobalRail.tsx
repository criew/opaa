import { useMemo, useState } from 'react'
import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Typography from '@mui/material/Typography'
import { ThemeProvider, useTheme } from '@mui/material/styles'
import GridViewOutlinedIcon from '@mui/icons-material/GridViewOutlined'
import LogoutIcon from '@mui/icons-material/Logout'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import SettingsIcon from '@mui/icons-material/Settings'
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined'
import { Link as RouterLink, useLocation, useNavigate } from 'react-router'
import BrandMark from '../components/BrandMark'
import NotificationBell from '../components/notifications/NotificationBell'
import { useAuthStore } from '../stores/authStore'
import { useBrandingStore } from '../stores/brandingStore'
import { createRailTheme } from '../theme/theme'
import { darkRoles, railRoles, shadow } from '../theme/tokens'

const RAIL_WIDTH = 64

export { RAIL_WIDTH }

interface RailDestination {
  label: string
  to: string
  /** Route prefixes on which this entry counts as the active scope. */
  activePrefixes: string[]
  icon: typeof GridViewOutlinedIcon
}

/**
 * The global rail (#786, mockup 2a): the always-visible first navigation level left of the
 * space column, one shade darker so global and space scope read apart at a glance. It carries
 * the brand emblem, the global destinations - Spaces, the library catalog, administration -
 * and the user's avatar with the account menu; the navy column next to it stays purely
 * space-scoped.
 */
export default function GlobalRail() {
  const location = useLocation()
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const branding = useBrandingStore((s) => s.branding)
  const [userMenuAnchor, setUserMenuAnchor] = useState<HTMLElement | null>(null)

  const appTheme = useTheme()
  const appMode = appTheme.palette.mode
  const railTheme = useMemo(
    () => createRailTheme(appMode, { primaryColor: branding.primaryColor }),
    [appMode, branding.primaryColor],
  )
  // Hover and active fills come from the role set directly - the theme maps bg1 onto both
  // background.default and background.paper, so the intermediate steps have no palette slot.
  const roles = appMode === 'light' ? railRoles : darkRoles

  const destinations: RailDestination[] = [
    // "/chat" lands in a space chat, so the Spaces scope owns it ("/" redirects there).
    {
      label: 'Spaces',
      to: '/spaces',
      activePrefixes: ['/spaces', '/chat'],
      icon: GridViewOutlinedIcon,
    },
    {
      label: 'Katalog',
      to: '/libraries',
      activePrefixes: ['/libraries'],
      icon: MenuBookOutlinedIcon,
    },
    ...(user?.systemRole === 'SYSTEM_ADMIN'
      ? [
          {
            label: 'Admin',
            to: '/admin/groups',
            activePrefixes: ['/admin'],
            icon: SettingsOutlinedIcon,
          },
        ]
      : []),
  ]

  const isActive = (destination: RailDestination) =>
    destination.activePrefixes.some(
      (prefix) => location.pathname === prefix || location.pathname.startsWith(`${prefix}/`),
    )

  const closeUserMenu = () => setUserMenuAnchor(null)
  const initial = (user?.displayName ?? user?.email ?? '?')[0].toUpperCase()

  return (
    <ThemeProvider theme={railTheme}>
      <Box
        component="nav"
        aria-label="Globale Navigation"
        sx={{
          width: RAIL_WIDTH,
          flexShrink: 0,
          height: '100vh',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: 0.5,
          py: '14px',
          bgcolor: 'background.default',
          color: 'text.primary',
        }}
      >
        <Box sx={{ mb: '10px' }}>
          <BrandMark logoOnly logoHeight={28} />
        </Box>

        {destinations.map((destination) => {
          const active = isActive(destination)
          const Icon = destination.icon
          return (
            <ButtonBase
              key={destination.to}
              component={RouterLink}
              to={destination.to}
              aria-current={
                active ? (location.pathname === destination.to ? 'page' : 'true') : undefined
              }
              sx={{
                width: 52,
                py: 1,
                borderRadius: '6px',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '3px',
                color: active ? 'text.primary' : 'text.secondary',
                bgcolor: active ? roles.bg3 : 'transparent',
                border: 1,
                borderColor: active ? roles.borderStrong : 'transparent',
                '&:hover': { bgcolor: active ? roles.bg3 : roles.bg2 },
              }}
            >
              <Icon sx={{ fontSize: 18 }} />
              {/* Mockup 2a labels the tiles at micro size; 9.5px matches the sidebar's own
                  micro-caption step. The icon alone would leave the tile ambiguous. */}
              <Typography component="span" sx={{ fontSize: 9.5, fontWeight: 500, lineHeight: 1 }}>
                {destination.label}
              </Typography>
            </ButtonBase>
          )
        })}

        <Box sx={{ flexGrow: 1 }} />

        <NotificationBell />

        {user && (
          <>
            <ButtonBase
              onClick={(event) => setUserMenuAnchor(event.currentTarget)}
              aria-haspopup="menu"
              aria-expanded={userMenuAnchor ? 'true' : undefined}
              aria-label="Profil und Einstellungen"
              sx={{ mt: 0.75, borderRadius: '50%' }}
            >
              <Avatar
                sx={{
                  width: 30,
                  height: 30,
                  bgcolor: 'primary.main',
                  fontSize: 11,
                  fontWeight: 600,
                }}
              >
                {initial}
              </Avatar>
            </ButtonBase>
            {/* Like the sidebar's menus: a light panel over the dark surface - the portal leaves
                the DOM but not the React theme context, hence the explicit app theme. */}
            <ThemeProvider theme={appTheme}>
              <Menu
                anchorEl={userMenuAnchor}
                open={Boolean(userMenuAnchor)}
                onClose={closeUserMenu}
                anchorOrigin={{ vertical: 'top', horizontal: 'right' }}
                transformOrigin={{ vertical: 'bottom', horizontal: 'left' }}
                slotProps={{ paper: { sx: { boxShadow: shadow.overlay } } }}
              >
                <MenuItem
                  onClick={() => {
                    closeUserMenu()
                    navigate('/settings')
                  }}
                >
                  <ListItemIcon>
                    <SettingsIcon fontSize="small" />
                  </ListItemIcon>
                  Einstellungen
                </MenuItem>
                <MenuItem
                  onClick={() => {
                    closeUserMenu()
                    void logout()
                  }}
                >
                  <ListItemIcon>
                    <LogoutIcon fontSize="small" />
                  </ListItemIcon>
                  Abmelden
                </MenuItem>
              </Menu>
            </ThemeProvider>
          </>
        )}
      </Box>
    </ThemeProvider>
  )
}
