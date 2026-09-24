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
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined'
import { useNavigate, useParams } from 'react-router'
import { spaceSettingsRoute } from '../routes'
import ChatList from '../components/chat/ChatList'
import AccessDerivation from '../components/permissions/AccessDerivation'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import { spaceRoleLabel } from '../utils/labels'
import PageHeading from '../components/a11y/PageHeading'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'

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
  const assetAssociations = useSpaceStore((s) => s.assetAssociations)
  const hasAssetAssociations = useSpaceStore((s) => s.hasAssetAssociations)
  const isLoadingAssetAssociations = useSpaceStore((s) => s.isLoadingAssetAssociations)
  const loadAssetAssociations = useSpaceStore((s) => s.loadAssetAssociations)

  const [membersExpanded, setMembersExpanded] = useState(true)
  const [chatsExpanded, setChatsExpanded] = useState(true)
  const [librariesExpanded, setLibrariesExpanded] = useState(true)
  // Zugeklappt: Die Herleitung ist eine Nachfrage, keine Dauerinformation - und sie kostet eine
  // eigene Anfrage, die nur stellt, wer sie aufklappt.
  const [derivationExpanded, setDerivationExpanded] = useState(false)
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
      void loadAssetAssociations(space.id)
    }
  }, [loadAssetAssociations, space])

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
        {/* Der Kopf des Space stand bis #1609 in einem Kasten, während alles darunter durch Linien
            gegliedert ist. Jetzt trägt ihn dieselbe Haarlinie wie jeden Abschnitt der Seite. */}
        <Box component="header" sx={{ borderBottom: 1, borderColor: 'divider', pb: 2 }}>
          <Stack
            direction={{ xs: 'column', md: 'row' }}
            spacing={2}
            sx={{ justifyContent: 'space-between' }}
          >
            <Box>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5 }}>
                <PageHeading title={space.name} />
                {space.isDefault && (
                  <Chip label="Standard" size="small" color="primary" variant="outlined" />
                )}
              </Stack>
              <Typography sx={{ color: 'text.secondary' }}>
                {space.description || 'Keine Beschreibung hinterlegt.'}
              </Typography>
              {/* ADR-0036, Entscheidung 6: Zustand und Adressat für jeden Leseberechtigten -
                  ohne Datum, früheren Eigentümer oder Grund. */}
              <SuccessionStateNote succession={space.succession} />
            </Box>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              {/* #1917: Ein Kurator verwaltet zwar keine Mitglieder, aber das zugeordnete Wissen -
                  auch für ihn führt der Weg dorthin über die Einstellungen. */}
              {(canManage(space.userRole, isOwner) || space.userRole === 'CURATOR') && (
                <Button
                  variant="outlined"
                  startIcon={<SettingsOutlinedIcon />}
                  onClick={() => navigate(spaceSettingsRoute(space.id))}
                >
                  Einstellungen
                </Button>
              )}
            </Stack>
          </Stack>
        </Box>

        <Accordion expanded={chatsExpanded} onChange={(_, expanded) => setChatsExpanded(expanded)}>
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
                <Typography sx={{ color: 'text.secondary' }}>Keine Mitglieder gefunden.</Typography>
              ) : (
                <Stack spacing={1}>
                  {members.map((member) => (
                    <Box key={member.id} sx={{ display: 'flex', justifyContent: 'space-between' }}>
                      {/* #1820: Eine geschützte Gruppe erscheint namenlos - der Dienst liefert
                          keinen Namen (ADR-0036, Entscheidung 9). */}
                      <Typography
                        sx={
                          member.protectedGroup
                            ? { fontStyle: 'italic' }
                            : member.displayName
                              ? undefined
                              : { fontFamily: 'monospace' }
                        }
                      >
                        {member.protectedGroup
                          ? 'Geschützte Gruppe'
                          : (member.displayName ?? member.subjectId)}
                        {member.subjectType === 'GROUP' && !member.protectedGroup
                          ? ' · Gruppe'
                          : ''}
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

        {/* #1822, ADR-0036 Entscheidung 9: der eigene Weg in diesen Space - für jede Person, nicht
            nur für die Verwaltung. Die Mitglieder einer Gruppe werden dabei nicht genannt. */}
        <Accordion
          expanded={derivationExpanded}
          onChange={(_, expanded) => setDerivationExpanded(expanded)}
        >
          <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 2.5 }}>
            <Typography component="h2" variant="h6">
              Warum sehe ich diesen Space?
            </Typography>
          </AccordionSummary>
          <AccordionDetails sx={{ px: 2.5, pb: 2.5, pt: 0 }}>
            <Divider sx={{ mb: 2 }} />
            {derivationExpanded && (
              <AccessDerivation target={{ kind: 'space', spaceId: space.id }} />
            )}
          </AccordionDetails>
        </Accordion>

        <Accordion
          expanded={librariesExpanded}
          onChange={(_, expanded) => setLibrariesExpanded(expanded)}
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
            {isLoadingAssetAssociations ? (
              <Typography sx={{ color: 'text.secondary' }}>
                Datenquellen werden geladen …
              </Typography>
            ) : !hasAssetAssociations ? (
              <Typography sx={{ color: 'text.secondary' }}>
                Diesem Space sind keine Bibliotheken zugeordnet — die Suche greift auf alle für Sie
                lesbaren Bibliotheken zurück.
              </Typography>
            ) : assetAssociations.length === 0 ? (
              // #706 review, finding 2: hasAssetAssociations is true here, but the (rechtege-
              // filterte) items list is empty - the space IS curated, just with libraries the
              // viewer cannot read. Spec (docs/features/spaces-and-assets.md#suchbereich-je-
              // chatart): a valid state, not an error, and deliberately without a count of the
              // unreadable libraries.
              <Typography sx={{ color: 'text.secondary' }}>
                In diesem Space ist für Sie derzeit kein Wissen verfügbar.
              </Typography>
            ) : (
              <Stack spacing={1}>
                {assetAssociations.map((association) => (
                  <Box
                    key={association.assetId}
                    sx={{ display: 'flex', justifyContent: 'space-between' }}
                  >
                    <Typography>{association.name}</Typography>
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
