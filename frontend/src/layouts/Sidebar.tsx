import { useEffect, useMemo, useState } from 'react'
import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Divider from '@mui/material/Divider'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import ListSubheader from '@mui/material/ListSubheader'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Typography from '@mui/material/Typography'
import { ThemeProvider, useTheme } from '@mui/material/styles'
import AddIcon from '@mui/icons-material/Add'
import GridViewIcon from '@mui/icons-material/GridView'
import LogoutIcon from '@mui/icons-material/Logout'
import SettingsIcon from '@mui/icons-material/Settings'
import UnfoldMoreIcon from '@mui/icons-material/UnfoldMore'
import { NavLink, useLocation, useNavigate, useParams } from 'react-router'
import BrandMark from '../components/BrandMark'
import CreateSpaceDialog from '../components/CreateSpaceDialog'
import ChatList from '../components/chat/ChatList'
import { useChatStore } from '../stores/chatStore'
import { useAuthStore } from '../stores/authStore'
import { useBrandingStore } from '../stores/brandingStore'
import { useSpaceStore } from '../stores/spaceStore'
import { createSidebarTheme } from '../theme/theme'
import { blue, darkRoles, fontFamily, navyRoles } from '../theme/tokens'

const SIDEBAR_WIDTH = 272

export { SIDEBAR_WIDTH }

/** Mockup 1a's space subtitle: the space's kind plus what the list API can already count. */
function spaceSubtitle(space: { isDefault: boolean; memberCount: number }): string {
  const kind = space.isDefault ? 'Persönlich' : 'Team'
  const members = space.memberCount === 1 ? '1 Mitglied' : `${space.memberCount} Mitglieder`
  return `${kind} · ${members}`
}

/**
 * The sidebar of the target design (#587, mockup 1a): brand mark on top, the space switcher as
 * the most prominent navigation act, the active space's chats as the middle, and the rarer
 * destinations plus the user badge at the bottom.
 */
export default function Sidebar() {
  const location = useLocation()
  const navigate = useNavigate()
  const { spaceId: routeSpaceId } = useParams<{ spaceId?: string }>()
  const chatSpaceId = useChatStore((s) => s.spaceId)
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const branding = useBrandingStore((s) => s.branding)
  const spaces = useSpaceStore((s) => s.spaces)
  const isLoadingSpaces = useSpaceStore((s) => s.isLoadingList)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const [spaceMenuAnchor, setSpaceMenuAnchor] = useState<HTMLElement | null>(null)
  const [userMenuAnchor, setUserMenuAnchor] = useState<HTMLElement | null>(null)
  const [createDialogOpen, setCreateDialogOpen] = useState(false)

  // Light app: the sidebar keeps the mockup's navy block; dark app: it follows the carbon dark
  // scheme (guidelines 2.3, #654) - always with the same branding accent as the rest of the app.
  // The nested provider also covers the menus below: they portal to <body>, but MUI's theme
  // context follows the React tree, not the DOM.
  const appTheme = useTheme()
  const appMode = appTheme.palette.mode
  const sidebarTheme = useMemo(
    () => createSidebarTheme(appMode, { primaryColor: branding.primaryColor }),
    [appMode, branding.primaryColor],
  )

  useEffect(() => {
    if (spaces.length === 0) {
      void loadSpaces()
    }
  }, [loadSpaces, spaces.length])

  // The chats section follows the space currently shown by the route (space overview, space
  // detail, or an open chat - all of which carry :spaceId), so switching spaces in the overview
  // updates the list immediately instead of waiting for a chat to be opened (#556). On routes
  // without a :spaceId (e.g. /settings), it falls back to the space of the still-open chat rather
  // than jumping to the default space, and only then to the default (or first) space if neither is
  // known yet.
  const defaultSpace = spaces.find((space) => space.isDefault) ?? spaces[0]
  const activeChatSpaceId = routeSpaceId ?? chatSpaceId ?? defaultSpace?.id ?? null
  const activeSpace = spaces.find((space) => space.id === activeChatSpaceId)

  const closeSpaceMenu = () => setSpaceMenuAnchor(null)
  const closeUserMenu = () => setUserMenuAnchor(null)

  return (
    <ThemeProvider theme={sidebarTheme}>
      <Box
        component="nav"
        aria-label="Hauptnavigation"
        sx={{
          width: SIDEBAR_WIDTH,
          flexShrink: 0,
          height: '100vh',
          display: 'flex',
          flexDirection: 'column',
          bgcolor: 'background.default',
          color: 'text.primary',
          borderRight: 1,
          borderColor: 'divider',
        }}
      >
        <Box sx={{ px: 2.5, pt: 2.5, pb: 2 }}>
          <BrandMark showClaim variant="h5" />
        </Box>

        <Box sx={{ px: 2, pb: 1.5 }}>
          <Button
            fullWidth
            onClick={(event) => setSpaceMenuAnchor(event.currentTarget)}
            aria-haspopup="menu"
            aria-expanded={spaceMenuAnchor ? 'true' : undefined}
            sx={{
              justifyContent: 'space-between',
              textAlign: 'left',
              px: 1.5,
              py: 1,
              borderRadius: '10px',
              border: 1,
              // Mockup 1a outlines the switcher one step brighter than the section rules (#658).
              borderColor: appMode === 'light' ? navyRoles.borderStrong : darkRoles.borderStrong,
              bgcolor: 'background.paper',
              color: 'text.primary',
            }}
            endIcon={<UnfoldMoreIcon sx={{ opacity: 0.7 }} />}
          >
            <Box sx={{ minWidth: 0 }}>
              <Typography
                variant="overline"
                component="span"
                sx={{
                  display: 'block',
                  lineHeight: 1.4,
                  color: appMode === 'light' ? blue[300] : 'text.disabled',
                }}
              >
                Space
              </Typography>
              <Typography
                component="span"
                noWrap
                sx={{ display: 'block', fontSize: 14, fontWeight: 500 }}
              >
                {activeSpace?.name ?? (isLoadingSpaces ? 'Wird geladen …' : 'Kein Space verfügbar')}
              </Typography>
            </Box>
          </Button>
          {/* Mockup 1a: the menus are light panels even over the navy block (#658). */}
          <ThemeProvider theme={appTheme}>
            <Menu
              anchorEl={spaceMenuAnchor}
              open={Boolean(spaceMenuAnchor)}
              onClose={closeSpaceMenu}
              slotProps={{ paper: { sx: { width: SIDEBAR_WIDTH - 32 } } }}
            >
              <ListSubheader
                sx={{
                  bgcolor: 'transparent',
                  lineHeight: 2.6,
                  fontFamily: fontFamily.mono,
                  fontSize: 9.5,
                  letterSpacing: '0.08em',
                  textTransform: 'uppercase',
                }}
              >
                Ihre Spaces
              </ListSubheader>
              {isLoadingSpaces && spaces.length === 0 && (
                <Box sx={{ py: 1.5, display: 'flex', justifyContent: 'center' }}>
                  <CircularProgress size={20} aria-label="Spaces werden geladen" />
                </Box>
              )}
              {spaces.map((space) => (
                <MenuItem
                  key={space.id}
                  selected={space.id === activeChatSpaceId}
                  onClick={() => {
                    closeSpaceMenu()
                    navigate(`/spaces/${space.id}`)
                  }}
                >
                  <ListItemText
                    primary={space.name}
                    secondary={spaceSubtitle(space)}
                    slotProps={{ primary: { noWrap: true } }}
                  />
                  {space.archived && <Chip label="Archiviert" size="small" sx={{ ml: 1 }} />}
                </MenuItem>
              ))}
              <Divider />
              <MenuItem
                onClick={() => {
                  closeSpaceMenu()
                  navigate('/spaces')
                }}
              >
                <ListItemIcon>
                  <GridViewIcon fontSize="small" />
                </ListItemIcon>
                Alle Spaces anzeigen
              </MenuItem>
              <MenuItem
                onClick={() => {
                  closeSpaceMenu()
                  setCreateDialogOpen(true)
                }}
              >
                <ListItemIcon>
                  <AddIcon fontSize="small" />
                </ListItemIcon>
                Neuen Space anlegen
              </MenuItem>
            </Menu>
          </ThemeProvider>
        </Box>

        <Box sx={{ px: 2, pb: 1, flexGrow: 1, minHeight: 0, overflowY: 'auto' }}>
          <Typography variant="overline" sx={{ color: 'rgba(255, 255, 255, 0.45)' }}>
            Chats
          </Typography>
          {activeChatSpaceId ? (
            <Box sx={{ mt: 0.5 }}>
              <ChatList spaceId={activeChatSpaceId} />
            </Box>
          ) : (
            <Typography color="text.secondary" variant="body2" sx={{ mt: 1 }}>
              Kein Space verfügbar.
            </Typography>
          )}
        </Box>

        <Divider />
        {/* Mockup 1a: quiet text-only section links, 12.5px on muted white (#658). */}
        <List sx={{ px: '14px', py: '10px' }}>
          {[
            ...(activeChatSpaceId
              ? [{ label: 'Space einrichten', to: `/spaces/${activeChatSpaceId}/manage` }]
              : []),
            { label: 'Wissensbibliotheken', to: '/libraries' },
            ...(user?.systemRole === 'SYSTEM_ADMIN'
              ? [
                  { label: 'Gruppen', to: '/admin/groups' },
                  { label: 'Branding', to: '/admin/branding' },
                ]
              : []),
          ].map((item) => (
            <ListItem key={item.to} disablePadding>
              <ListItemButton
                component={NavLink}
                to={item.to}
                selected={location.pathname === item.to}
                sx={{ borderRadius: '6px', px: '10px', py: '5px' }}
              >
                <ListItemText
                  primary={item.label}
                  slotProps={{
                    primary: { sx: { fontSize: 12.5, color: 'rgba(255, 255, 255, 0.72)' } },
                  }}
                />
              </ListItemButton>
            </ListItem>
          ))}
        </List>

        {user && (
          <>
            <Divider />
            <ButtonBase
              onClick={(event) => setUserMenuAnchor(event.currentTarget)}
              aria-haspopup="menu"
              aria-expanded={userMenuAnchor ? 'true' : undefined}
              aria-label="Benutzermenü"
              sx={{
                p: 2,
                display: 'flex',
                alignItems: 'center',
                gap: 1,
                width: '100%',
                justifyContent: 'flex-start',
                textAlign: 'left',
              }}
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
                {(user.displayName ?? user.email ?? '?')[0].toUpperCase()}
              </Avatar>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography noWrap sx={{ fontSize: 12.5, color: 'rgba(255, 255, 255, 0.85)' }}>
                  {user.displayName ?? user.email ?? 'Benutzer'}
                </Typography>
                {user.email && user.displayName && (
                  <Typography
                    noWrap
                    component="div"
                    sx={{ fontSize: 11, color: 'rgba(255, 255, 255, 0.5)' }}
                  >
                    {user.email}
                  </Typography>
                )}
              </Box>
              <UnfoldMoreIcon fontSize="small" sx={{ color: 'text.disabled' }} />
            </ButtonBase>
            <ThemeProvider theme={appTheme}>
              <Menu
                anchorEl={userMenuAnchor}
                open={Boolean(userMenuAnchor)}
                onClose={closeUserMenu}
                anchorOrigin={{ vertical: 'top', horizontal: 'center' }}
                transformOrigin={{ vertical: 'bottom', horizontal: 'center' }}
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

        <CreateSpaceDialog
          open={createDialogOpen}
          onClose={() => setCreateDialogOpen(false)}
          onCreated={(spaceId) => {
            setCreateDialogOpen(false)
            navigate(`/spaces/${spaceId}`)
          }}
        />
      </Box>
    </ThemeProvider>
  )
}
