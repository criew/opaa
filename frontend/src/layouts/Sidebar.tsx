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
import { ThemeProvider } from '@mui/material/styles'
import AddIcon from '@mui/icons-material/Add'
import GridViewIcon from '@mui/icons-material/GridView'
import GroupsIcon from '@mui/icons-material/Groups'
import LibraryBooksIcon from '@mui/icons-material/LibraryBooks'
import LogoutIcon from '@mui/icons-material/Logout'
import PaletteIcon from '@mui/icons-material/Palette'
import SettingsIcon from '@mui/icons-material/Settings'
import TuneIcon from '@mui/icons-material/Tune'
import UnfoldMoreIcon from '@mui/icons-material/UnfoldMore'
import { NavLink, useLocation, useNavigate, useParams } from 'react-router'
import BrandMark from '../components/BrandMark'
import CreateSpaceDialog from '../components/CreateSpaceDialog'
import ChatList from '../components/chat/ChatList'
import { useChatStore } from '../stores/chatStore'
import { useAuthStore } from '../stores/authStore'
import { useBrandingStore } from '../stores/brandingStore'
import { useSpaceStore } from '../stores/spaceStore'
import { createAppTheme } from '../theme/theme'

const SIDEBAR_WIDTH = 300

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

  // The sidebar stays navy in both color schemes (guidelines 2.3) - it always renders on the
  // dark scheme's roles, carrying the same branding accent as the rest of the app. The nested
  // provider also covers the menus below: they portal to <body>, but MUI's theme context follows
  // the React tree, not the DOM.
  const sidebarTheme = useMemo(
    () => createAppTheme('dark', { primaryColor: branding.primaryColor }),
    [branding.primaryColor],
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
          <BrandMark showClaim />
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
              border: 1,
              borderColor: 'divider',
              bgcolor: 'background.paper',
              color: 'text.primary',
            }}
            endIcon={<UnfoldMoreIcon />}
          >
            <Box sx={{ minWidth: 0 }}>
              <Typography
                variant="overline"
                component="span"
                sx={{ display: 'block', color: 'text.disabled', lineHeight: 1.2 }}
              >
                Space
              </Typography>
              <Typography component="span" noWrap sx={{ display: 'block', fontWeight: 600 }}>
                {activeSpace?.name ?? (isLoadingSpaces ? 'Wird geladen …' : 'Kein Space verfügbar')}
              </Typography>
            </Box>
          </Button>
          <Menu
            anchorEl={spaceMenuAnchor}
            open={Boolean(spaceMenuAnchor)}
            onClose={closeSpaceMenu}
            slotProps={{ paper: { sx: { width: SIDEBAR_WIDTH - 32 } } }}
          >
            <ListSubheader sx={{ bgcolor: 'transparent', lineHeight: 2.5 }}>
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
        </Box>

        <Box sx={{ px: 2, pb: 1, flexGrow: 1, minHeight: 0, overflowY: 'auto' }}>
          <Typography variant="overline" sx={{ color: 'text.disabled' }}>
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
        <List sx={{ px: 1.5, py: 1 }}>
          {activeChatSpaceId && (
            <ListItem disablePadding>
              <ListItemButton
                component={NavLink}
                to={`/spaces/${activeChatSpaceId}/manage`}
                selected={location.pathname === `/spaces/${activeChatSpaceId}/manage`}
                sx={{ borderRadius: 2 }}
              >
                <ListItemIcon sx={{ minWidth: 36 }}>
                  <TuneIcon fontSize="small" />
                </ListItemIcon>
                <ListItemText primary="Space einrichten" />
              </ListItemButton>
            </ListItem>
          )}
          <ListItem disablePadding>
            <ListItemButton
              component={NavLink}
              to="/libraries"
              selected={location.pathname === '/libraries'}
              sx={{ borderRadius: 2 }}
            >
              <ListItemIcon sx={{ minWidth: 36 }}>
                <LibraryBooksIcon fontSize="small" />
              </ListItemIcon>
              <ListItemText primary="Wissensbibliotheken" />
            </ListItemButton>
          </ListItem>
          {user?.systemRole === 'SYSTEM_ADMIN' && (
            <ListItem disablePadding>
              <ListItemButton
                component={NavLink}
                to="/admin/groups"
                selected={location.pathname === '/admin/groups'}
                sx={{ borderRadius: 2 }}
              >
                <ListItemIcon sx={{ minWidth: 36 }}>
                  <GroupsIcon fontSize="small" />
                </ListItemIcon>
                <ListItemText primary="Gruppen" />
              </ListItemButton>
            </ListItem>
          )}
          {user?.systemRole === 'SYSTEM_ADMIN' && (
            <ListItem disablePadding>
              <ListItemButton
                component={NavLink}
                to="/admin/branding"
                selected={location.pathname === '/admin/branding'}
                sx={{ borderRadius: 2 }}
              >
                <ListItemIcon sx={{ minWidth: 36 }}>
                  <PaletteIcon fontSize="small" />
                </ListItemIcon>
                <ListItemText primary="Branding" />
              </ListItemButton>
            </ListItem>
          )}
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
              <Avatar sx={{ width: 32, height: 32, bgcolor: 'primary.main', fontSize: 14 }}>
                {(user.displayName ?? user.email ?? '?')[0].toUpperCase()}
              </Avatar>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography variant="body2" noWrap>
                  {user.displayName ?? user.email ?? 'Benutzer'}
                </Typography>
                {user.email && user.displayName && (
                  <Typography variant="caption" color="text.secondary" noWrap component="div">
                    {user.email}
                  </Typography>
                )}
              </Box>
              <UnfoldMoreIcon fontSize="small" sx={{ color: 'text.disabled' }} />
            </ButtonBase>
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
