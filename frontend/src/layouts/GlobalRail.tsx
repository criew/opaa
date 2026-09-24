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
import HistoryOutlinedIcon from '@mui/icons-material/HistoryOutlined'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import LogoutIcon from '@mui/icons-material/Logout'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import SettingsIcon from '@mui/icons-material/Settings'
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined'
import TextSnippetOutlinedIcon from '@mui/icons-material/TextSnippetOutlined'
import { Link as RouterLink, useLocation, useNavigate } from 'react-router'
import AboutDialog from './AboutDialog'
import BrandMark from '../components/BrandMark'
import NotificationBell from '../components/notifications/NotificationBell'
import { useAuthStore } from '../stores/authStore'
import { userInitial } from '../utils/userInitial'
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
 * Ein Eintrag je Asset-Typ, in der Reihenfolge der Leiste; die Seite dahinter trägt denselben
 * Namen wie der Eintrag. Ein weiterer Typ braucht drei Stellen: diese Zeile, seine Route unter
 * `GlobalAreaLayout` in App.tsx und sein Präfix in `GLOBAL_AREA_PREFIXES` (globalArea.ts) — ohne
 * das letzte rendert die neue Seite mit der Space-Spalte daneben statt im globalen Rahmen.
 */
const ASSET_DESTINATIONS: RailDestination[] = [
  {
    label: 'Wissen',
    to: '/libraries',
    activePrefixes: ['/libraries'],
    icon: MenuBookOutlinedIcon,
  },
  {
    label: 'Prompts',
    to: '/prompts',
    activePrefixes: ['/prompts'],
    icon: TextSnippetOutlinedIcon,
  },
]

/**
 * The global rail (#786, mockup 2a): the always-visible first navigation level left of the
 * space column, one shade darker so global and space scope read apart at a glance. It carries
 * the brand emblem, the global destinations - Spaces, one entry per asset type, administration -
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
  const [aboutOpen, setAboutOpen] = useState(false)

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
    // Je Asset-Typ ein eigener Punkt (#1915): „Wissen" und „Prompts" führen auf die lesbaren
    // Bestände ihres Typs. Der organisationsweite Katalog ist davon getrennt.
    ...ASSET_DESTINATIONS,
    ...(user?.systemRole === 'SYSTEM_ADMIN'
      ? [
          {
            label: 'Admin',
            to: '/admin/users',
            activePrefixes: ['/admin'],
            icon: SettingsOutlinedIcon,
          },
        ]
      : []),
    // Die Revision hat einen eigenen Einstieg: Die Stichtagsauskunft haengt an der AUDITOR-Rolle,
    // nicht an der Systemverwaltung, und beide Rollen schliessen einander aus (#1822).
    ...(user?.systemRole === 'AUDITOR'
      ? [
          {
            label: 'Revision',
            to: '/revision/rechtehistorie',
            activePrefixes: ['/revision'],
            icon: HistoryOutlinedIcon,
          },
        ]
      : []),
  ]

  const isActive = (destination: RailDestination) =>
    destination.activePrefixes.some(
      (prefix) => location.pathname === prefix || location.pathname.startsWith(`${prefix}/`),
    )

  const closeUserMenu = () => setUserMenuAnchor(null)
  const initial = userInitial(user)

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
                {/* #1921: Produktname, Version und der Demo-Hinweis stehen hier statt dauerhaft
                    unter jeder Seite. */}
                <MenuItem
                  onClick={() => {
                    closeUserMenu()
                    setAboutOpen(true)
                  }}
                >
                  <ListItemIcon>
                    <InfoOutlinedIcon fontSize="small" />
                  </ListItemIcon>
                  Info zu {branding.productName}
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
              <AboutDialog open={aboutOpen} onClose={() => setAboutOpen(false)} />
            </ThemeProvider>
          </>
        )}
      </Box>
    </ThemeProvider>
  )
}
