import { useState, useEffect } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Divider from '@mui/material/Divider'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import ManageAccountsIcon from '@mui/icons-material/ManageAccounts'
import { useNavigate, useParams } from 'react-router'
import ChatList from '../components/chat/ChatList'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import { spaceRoleLabel } from '../utils/labels'
import PageHeading from '../components/a11y/PageHeading'

// #674 review: the owner is not always ADMIN - transferOwnership only reassigns Space.ownerId and
// never touches the new owner's own SpaceMembership role (see SpaceService#requireMemberListViewer,
// which checks the same two conditions on the backend). A non-ADMIN owner and a system admin
// without their own membership (userRole null) must still see the full member list and the
// management entry point.
function canManage(role: string | undefined, isOwner: boolean): boolean {
  return role === 'ADMIN' || isOwner
}

export default function SpacePage() {
  const { spaceId } = useParams()
  const navigate = useNavigate()
  const currentUserId = useAuthStore((s) => s.user?.id)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const selectSpace = useSpaceStore((s) => s.selectSpace)
  const loadMembers = useSpaceStore((s) => s.loadMembers)
  const spaces = useSpaceStore((s) => s.spaces)
  const space = useSpaceStore((s) => s.selectedSpace)
  const members = useSpaceStore((s) => s.members)
  const isLoadingDetails = useSpaceStore((s) => s.isLoadingDetails)
  const error = useSpaceStore((s) => s.error)
  const isOwner = Boolean(currentUserId) && space?.ownerId === currentUserId
  const libraryAssociations = useSpaceStore((s) => s.libraryAssociations)
  const hasLibraryAssociations = useSpaceStore((s) => s.hasLibraryAssociations)
  const isLoadingLibraryAssociations = useSpaceStore((s) => s.isLoadingLibraryAssociations)
  const loadLibraryAssociations = useSpaceStore((s) => s.loadLibraryAssociations)

  const [membersExpanded, setMembersExpanded] = useState(true)
  const [chatsExpanded, setChatsExpanded] = useState(true)
  const [librariesExpanded, setLibrariesExpanded] = useState(true)
  // #203 acceptance criterion "die UI erklärt einmal, sichtbar, warum die Liste je Mitglied
  // unterschiedlich sein kann" - a dismissible hint, shown once per browser rather than every
  // visit, since a permanent warning banner on something that is by design (not an error) would
  // itself become the "warning sign attached to nearly every space" the spec explicitly rejects
  // for the mixed-audience case (docs/features/spaces-and-assets.md#geprüfte-und-verworfene-
  // alternativen).
  const [libraryHintDismissed, setLibraryHintDismissed] = useState(
    () => window.localStorage.getItem('opaa.space-library-hint-dismissed') === 'true',
  )
  function dismissLibraryHint() {
    window.localStorage.setItem('opaa.space-library-hint-dismissed', 'true')
    setLibraryHintDismissed(true)
  }

  useEffect(() => {
    if (spaces.length === 0) {
      void loadSpaces()
    }
  }, [loadSpaces, spaces.length])

  useEffect(() => {
    const effectiveSpaceId = spaceId ?? spaces[0]?.id
    if (effectiveSpaceId) {
      void selectSpace(effectiveSpaceId)
      if (!spaceId) {
        navigate(`/spaces/${effectiveSpaceId}`, { replace: true })
      }
    }
  }, [navigate, selectSpace, spaceId, spaces])

  // #144: the full member list is only fetched for ADMIN and the owner - anyone else would just
  // get a 403 from listSpaceMembers, so the accordion below shows the aggregated roleCounts to
  // them instead of an empty-looking list.
  useEffect(() => {
    if (space && canManage(space.userRole, isOwner)) {
      void loadMembers(space.id)
    }
  }, [loadMembers, space, isOwner])

  useEffect(() => {
    if (space) {
      void loadLibraryAssociations(space.id)
    }
  }, [loadLibraryAssociations, space])

  if (isLoadingDetails && !space) {
    return (
      <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <CircularProgress />
      </Box>
    )
  }

  if (!space) {
    return (
      <Box sx={{ flexGrow: 1, p: 3 }}>
        <PageHeading title="Kein Space ausgewählt" variant="h6" />
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, overflowY: 'auto' }}>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      <Stack spacing={2.5}>
        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Stack
            direction={{ xs: 'column', md: 'row' }}
            spacing={2}
            sx={{ justifyContent: 'space-between' }}
          >
            <Box>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
                <PageHeading title={space.name} />
                {space.isDefault && (
                  <Chip label="Standard" size="small" color="primary" variant="outlined" />
                )}
              </Stack>
              <Typography color="text.secondary">
                {space.description || 'Keine Beschreibung hinterlegt.'}
              </Typography>
            </Box>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              {canManage(space.userRole, isOwner) && (
                <Button
                  variant="outlined"
                  startIcon={<ManageAccountsIcon />}
                  onClick={() => navigate(`/spaces/${space.id}/manage`)}
                >
                  Space verwalten
                </Button>
              )}
            </Stack>
          </Stack>
        </Paper>

        <Accordion
          expanded={chatsExpanded}
          onChange={(_, expanded) => setChatsExpanded(expanded)}
          variant="outlined"
          disableGutters
        >
          <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 2.5 }}>
            <Typography component="h2" variant="h6">
              Chats
            </Typography>
          </AccordionSummary>
          <AccordionDetails sx={{ px: 2.5, pb: 2.5, pt: 0 }}>
            <Divider sx={{ mb: 2 }} />
            <ChatList spaceId={space.id} />
          </AccordionDetails>
        </Accordion>

        <Accordion
          expanded={membersExpanded}
          onChange={(_, expanded) => setMembersExpanded(expanded)}
          variant="outlined"
          disableGutters
        >
          <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 2.5 }}>
            <Typography component="h2" variant="h6">
              Mitglieder
            </Typography>
          </AccordionSummary>
          <AccordionDetails sx={{ px: 2.5, pb: 2.5, pt: 0 }}>
            <Divider sx={{ mb: 2 }} />
            {canManage(space.userRole, isOwner) ? (
              members.length === 0 ? (
                <Typography color="text.secondary">Keine Mitglieder gefunden.</Typography>
              ) : (
                <Stack spacing={1}>
                  {members.map((member) => (
                    <Box
                      key={member.userId}
                      sx={{ display: 'flex', justifyContent: 'space-between' }}
                    >
                      <Typography sx={member.displayName ? undefined : { fontFamily: 'monospace' }}>
                        {member.displayName ?? member.userId}
                      </Typography>
                      <Chip label={spaceRoleLabel(member.role)} size="small" />
                    </Box>
                  ))}
                </Stack>
              )
            ) : (
              // #144: non-admins no longer receive the full member list - only the aggregated
              // count per role, which does not name anyone.
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
                {Object.entries(space.roleCounts ?? {})
                  .filter(([, count]) => count > 0)
                  .map(([role, count]) => (
                    <Chip key={role} label={`${spaceRoleLabel(role)}: ${count}`} size="small" />
                  ))}
              </Stack>
            )}
          </AccordionDetails>
        </Accordion>

        <Accordion
          expanded={librariesExpanded}
          onChange={(_, expanded) => setLibrariesExpanded(expanded)}
          variant="outlined"
          disableGutters
        >
          <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 2.5 }}>
            <Typography component="h2" variant="h6">
              Datenquellen
            </Typography>
          </AccordionSummary>
          <AccordionDetails sx={{ px: 2.5, pb: 2.5, pt: 0 }}>
            <Divider sx={{ mb: 2 }} />
            {!libraryHintDismissed && (
              <Alert severity="info" sx={{ mb: 2 }} onClose={dismissLibraryHint}>
                Diese Liste zeigt nur die Bibliotheken, auf die Sie selbst Zugriff haben. Eine
                Zuordnung gewährt keinen zusätzlichen Zugriff — andere Mitglieder können deshalb
                eine andere Liste sehen als Sie.
              </Alert>
            )}
            {isLoadingLibraryAssociations ? (
              <Typography color="text.secondary">Datenquellen werden geladen …</Typography>
            ) : !hasLibraryAssociations ? (
              <Typography color="text.secondary">
                Diesem Space sind keine Bibliotheken zugeordnet — die Suche greift auf alle für Sie
                lesbaren Bibliotheken zurück.
              </Typography>
            ) : libraryAssociations.length === 0 ? (
              // #706 review, finding 2: hasLibraryAssociations is true here, but the (rechtege-
              // filterte) items list is empty - the space IS curated, just with libraries the
              // viewer cannot read. Spec (docs/features/spaces-and-assets.md#suchbereich-je-
              // chatart): a valid state, not an error, and deliberately without a count of the
              // unreadable libraries.
              <Typography color="text.secondary">
                In diesem Space ist für Sie derzeit kein Wissen verfügbar.
              </Typography>
            ) : (
              <Stack spacing={1}>
                {libraryAssociations.map((association) => (
                  <Box
                    key={association.libraryId}
                    sx={{ display: 'flex', justifyContent: 'space-between' }}
                  >
                    <Typography>{association.libraryName}</Typography>
                  </Box>
                ))}
              </Stack>
            )}
          </AccordionDetails>
        </Accordion>
      </Stack>
    </Box>
  )
}
