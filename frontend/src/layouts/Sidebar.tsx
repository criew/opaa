import { useEffect, useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
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
import CheckIcon from '@mui/icons-material/Check'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined'
import { Link as RouterLink, useLocation, useNavigate, useParams } from 'react-router'
import { spaceSettingsRoute } from '../routes'
import ChatList from '../components/chat/ChatList'
import { useChatStore } from '../stores/chatStore'
import { useBrandingStore } from '../stores/brandingStore'
import { useSpaceStore } from '../stores/spaceStore'
import { createSidebarTheme } from '../theme/theme'
import { blue, darkRoles, fontFamily, navyRoles, shadow } from '../theme/tokens'

const SIDEBAR_WIDTH = 248

export { SIDEBAR_WIDTH }

/**
 * Mockup 1a's space subtitle: the space's kind plus what the list API can already count.
 *
 * <p>"Mitgliedschaften", not "Mitglieder" (#1815): memberCount counts rows, and a row may be a
 * group standing for any number of people. Counting the people behind the groups instead would
 * disclose group sizes to everybody who sees the space - the very figure ADR-0036, Entscheidung 9
 * withholds below the minimum group size.
 */
function spaceSubtitle(space: { isDefault: boolean; memberCount: number }): string {
  const kind = space.isDefault ? 'Persönlich' : 'Team'
  const members =
    space.memberCount === 1 ? '1 Mitgliedschaft' : `${space.memberCount} Mitgliedschaften`
  return `${kind} · ${members}`
}

/**
 * The space column of the target design (#587, since #786 mockup 2a): purely space-scoped -
 * the space switcher as the most prominent navigation act, the active space's chats as the
 * middle, and the space's own destinations at the bottom. Everything global - brand mark,
 * catalog, administration, the user badge - lives on the {@link GlobalRail} to its left.
 */
export default function Sidebar() {
  const location = useLocation()
  const navigate = useNavigate()
  const { spaceId: routeSpaceId } = useParams<{ spaceId?: string }>()
  const chatSpaceId = useChatStore((s) => s.spaceId)
  const branding = useBrandingStore((s) => s.branding)
  const spaces = useSpaceStore((s) => s.spaces)
  const isLoadingSpaces = useSpaceStore((s) => s.isLoadingList)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const [spaceMenuAnchor, setSpaceMenuAnchor] = useState<HTMLElement | null>(null)

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

  // The chats section follows the space currently shown by the route (the space page, its
  // management view, or an open chat - all of which carry :spaceId), so switching spaces
  // updates the list immediately instead of waiting for a chat to be opened (#556). On routes
  // without a :spaceId (e.g. /chat while it resolves), it falls back to the space of the
  // still-open chat rather than jumping to the default space, and only then to the default
  // (or first) space if neither is known yet.
  const defaultSpace = spaces.find((space) => space.isDefault) ?? spaces[0]
  const activeChatSpaceId = routeSpaceId ?? chatSpaceId ?? defaultSpace?.id ?? null
  const activeSpace = spaces.find((space) => space.id === activeChatSpaceId)

  // #1917: Den Einstieg sieht, wer an diesem Space etwas zu verwalten hat - ein Administrator
  // (Stammdaten, Mitglieder) oder ein Kurator (zugeordnetes Wissen). Für alle anderen sind die
  // Einstellungen leer, und der Dienst weist ihre Schreibzugriffe ohnehin ab.
  const mayOpenSettings = activeSpace?.userRole === 'ADMIN' || activeSpace?.userRole === 'CURATOR'
  const settingsRoute = activeChatSpaceId ? spaceSettingsRoute(activeChatSpaceId) : ''
  const inSettings = location.pathname.startsWith(`/spaces/${activeChatSpaceId}/settings`)

  const closeSpaceMenu = () => setSpaceMenuAnchor(null)

  return (
    <ThemeProvider theme={sidebarTheme}>
      <Box
        component="aside"
        aria-label="Space-Bereich"
        sx={{
          width: SIDEBAR_WIDTH,
          // In the mobile drawer the column shares 92vw with the rail and must give way;
          // on desktop it keeps its fixed width.
          flexShrink: { xs: 1, md: 0 },
          minWidth: 0,
          height: '100vh',
          display: 'flex',
          flexDirection: 'column',
          bgcolor: 'background.default',
          color: 'text.primary',
          borderLeft: 1,
          borderRight: 1,
          borderColor: 'divider',
        }}
      >
        <Box sx={{ px: 2, pt: 2, pb: 1.5 }}>
          <Button
            fullWidth
            onClick={(event) => setSpaceMenuAnchor(event.currentTarget)}
            aria-haspopup="menu"
            aria-expanded={spaceMenuAnchor ? 'true' : undefined}
            sx={{
              justifyContent: 'space-between',
              textAlign: 'left',
              px: 1.5,
              py: 1.25,
              borderRadius: '10px',
              border: 1,
              // Mockup 1a outlines the switcher one step brighter than the section rules (#658).
              borderColor: appMode === 'light' ? navyRoles.borderStrong : darkRoles.borderStrong,
              bgcolor: 'background.paper',
              color: 'text.primary',
            }}
            endIcon={<ExpandMoreIcon sx={{ opacity: 0.8 }} />}
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
              slotProps={{
                paper: { sx: { width: SIDEBAR_WIDTH - 32, boxShadow: shadow.overlay } },
                list: { sx: { py: 0.5 } },
              }}
            >
              <ListSubheader
                sx={{
                  bgcolor: 'transparent',
                  lineHeight: 2.6,
                  borderBottom: 1,
                  borderColor: 'divider',
                  mb: 0.5,
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
                    // Picking a space lands on an empty chat in it, not on its overview page -
                    // the overview stays reachable from the spaces list. An archived space accepts
                    // no new chats (ChatService rejects the create), so it keeps landing on its
                    // overview.
                    navigate(
                      space.archived ? `/spaces/${space.id}` : `/spaces/${space.id}/chats/new`,
                    )
                  }}
                >
                  <ListItemText
                    primary={space.name}
                    secondary={spaceSubtitle(space)}
                    slotProps={{
                      primary: {
                        noWrap: true,
                        sx: { fontWeight: space.id === activeChatSpaceId ? 500 : 400 },
                      },
                    }}
                  />
                  {space.archived && <Chip label="Archiviert" size="small" sx={{ ml: 1 }} />}
                  {space.id === activeChatSpaceId && (
                    <CheckIcon sx={{ ml: 1, fontSize: 15, color: 'primary.main' }} />
                  )}
                </MenuItem>
              ))}
              <Divider sx={{ my: 0.5 }} />
              <MenuItem
                onClick={() => {
                  closeSpaceMenu()
                  navigate('/spaces')
                }}
                sx={{ fontSize: 12.5, color: 'text.secondary' }}
              >
                Alle Spaces anzeigen
              </MenuItem>
              <Divider sx={{ my: 0.5 }} />
              <MenuItem
                onClick={() => {
                  closeSpaceMenu()
                  navigate('/spaces/new')
                }}
                sx={{ fontWeight: 500, color: 'primary.main' }}
              >
                <AddIcon sx={{ fontSize: 15, mr: 1 }} />
                Neuen Space anlegen
              </MenuItem>
            </Menu>
          </ThemeProvider>
        </Box>

        <Box
          component="nav"
          aria-label="Chats"
          sx={{ px: 2, pb: 1, flexGrow: 1, minHeight: 0, overflowY: 'auto' }}
        >
          {activeChatSpaceId ? (
            <Box sx={{ mt: 0.5 }}>
              <ChatList
                spaceId={activeChatSpaceId}
                menuTheme={appTheme}
                header={
                  <Typography
                    component="h2"
                    variant="overline"
                    sx={{ color: 'rgba(255, 255, 255, 0.55)' }}
                  >
                    Chats
                  </Typography>
                }
              />
            </Box>
          ) : (
            <>
              <Typography
                component="h2"
                variant="overline"
                sx={{ color: 'rgba(255, 255, 255, 0.55)' }}
              >
                Chats
              </Typography>
              <Typography variant="body2" sx={{ color: 'text.secondary', mt: 1 }}>
                Kein Space verfügbar.
              </Typography>
            </>
          )}
        </Box>

        {activeChatSpaceId && mayOpenSettings && (
          <>
            <Divider />
            {/* Mockup 2a: the foot of the column stays space-scoped - quiet text-only links,
                12.5px on muted white (#658); everything global moved onto the rail (#786). Its
                own nav landmark, so landmark navigation still reaches these links (review
                #791, finding 5) - as a container AROUND the list, not instead of its <ul>:
                List component="nav" replaced the <ul> and left the <li>s without a list
                parent, an axe "serious" violation (#792). */}
            <Box component="nav" aria-label="Space-Navigation">
              <List sx={{ px: '14px', py: '10px' }}>
                {/* #1917: ein Einstiegspunkt statt zweier - alles Verwaltende dieses Space liegt
                    hinter dem Zahnrad. Wer den Space nicht verwalten darf, sieht ihn nicht. */}
                <ListItem disablePadding>
                  <ListItemButton
                    component={RouterLink}
                    to={settingsRoute}
                    selected={inSettings}
                    // Wie auf der globalen Leiste: „page" nur für das eigene Ziel, „true" für
                    // jeden anderen Reiter der Einstellungen - sonst wäre der Eintrag auf
                    // .../settings/members hervorgehoben, ohne es anzusagen.
                    aria-current={
                      inSettings
                        ? location.pathname === settingsRoute
                          ? 'page'
                          : 'true'
                        : undefined
                    }
                    sx={{ borderRadius: '6px', px: '10px', py: '5px' }}
                  >
                    <ListItemIcon sx={{ minWidth: 26 }}>
                      <SettingsOutlinedIcon
                        sx={{ fontSize: 16, color: 'rgba(255, 255, 255, 0.72)' }}
                      />
                    </ListItemIcon>
                    <ListItemText
                      primary="Einstellungen"
                      slotProps={{
                        primary: { sx: { fontSize: 12.5, color: 'rgba(255, 255, 255, 0.72)' } },
                      }}
                    />
                  </ListItemButton>
                </ListItem>
              </List>
            </Box>
          </>
        )}
      </Box>
    </ThemeProvider>
  )
}
