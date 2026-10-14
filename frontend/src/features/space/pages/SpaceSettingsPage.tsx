import { useEffect, useMemo } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { Navigate, useParams } from 'react-router'
import type { SpaceRole } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaTabs from '@/shared/ui/AreaTabs'
import MetaBadge from '@/shared/ui/MetaBadge'
import SpaceContentSection from '../components/SpaceContentSection'
import SpaceGeneralSection from '../components/SpaceGeneralSection'
import SpaceMembersSection from '../components/SpaceMembersSection'
import {
  FORMER_SPACE_SETTINGS_TABS,
  SPACE_SETTINGS_TABS,
  spaceSettingsRoute,
  type SpaceSettingsTab,
} from '@/shared/routes'

/** The tabs in the order of the bar; every value in {@link SPACE_SETTINGS_TABS} has one. */
const tabs: Array<{ value: SpaceSettingsTab; label: string }> = [
  { value: 'general', label: 'Stammdaten' },
  { value: 'members', label: 'Mitglieder' },
  { value: 'content', label: 'Inhalte' },
]

function isSpaceSettingsTab(value: string | undefined): value is SpaceSettingsTab {
  return SPACE_SETTINGS_TABS.some((tab) => tab === value)
}

function canManageMembers(role: SpaceRole | undefined): boolean {
  return role === 'ADMIN'
}

// A CURATOR may associate and detach assets, one level below ADMIN's member management -
// docs/features/spaces-and-assets.md#space-rollen ("CURATOR: zusätzlich Assets assoziieren und
// lösen").
function canManageAssets(role: SpaceRole | undefined, isOwner: boolean): boolean {
  return role === 'CURATOR' || role === 'ADMIN' || isOwner
}

/**
 * The one settings page of a space, reachable via the gear at the foot of the
 * sidebar. Every tab is a route of its own, so that a link lands in the right area
 * and a reload keeps it; only what the visible tab needs is loaded.
 */
export default function SpaceSettingsPage() {
  const { spaceId, tab } = useParams()
  const currentUserId = useAuthStore((s) => s.user?.id)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const selectSpace = useSpaceStore((s) => s.selectSpace)
  const space = useSpaceStore((s) => s.selectedSpace)

  useEffect(() => {
    if (spaceId) {
      void loadSpaces()
      void selectSpace(spaceId)
    }
  }, [loadSpaces, selectSpace, spaceId])

  const canManage = useMemo(() => canManageMembers(space?.userRole), [space?.userRole])
  const isOwner = Boolean(currentUserId) && space?.ownerId === currentUserId

  if (!spaceId || !space) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
        <PageHeading title="Space nicht geladen" />
      </Box>
    )
  }

  // A typo in the path is not silently read as master data: the address bar
  // says in the end what is actually shown.
  if (!isSpaceSettingsTab(tab)) {
    const successor = tab ? FORMER_SPACE_SETTINGS_TABS[tab] : undefined
    return <Navigate to={spaceSettingsRoute(spaceId, successor)} replace />
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: 760 }}>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'baseline',
            gap: 2,
            mb: 3,
            flexWrap: 'wrap',
          }}
        >
          <PageHeading title="Einstellungen" documentTitle={`Einstellungen – ${space.name}`} />
          <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
            {space.name}
          </Typography>
          {space.archived && <MetaBadge>Archiviert</MetaBadge>}
        </Box>

        {space.successionOpen && (
          // ADR-0036 Entscheidung 6: state and addressee, deliberately without a date,
          // without the previous owner and without a reason - those belong in the operational list,
          // not beside a colleague's name. The space stays fully usable.
          <Alert severity="warning" sx={{ mb: 2 }}>
            Nachfolge offen — zuständig: Systemverwaltung. Der Space bleibt nutzbar, bestehende
            Rechte bleiben bestehen.
          </Alert>
        )}

        <AreaTabs
          tabs={tabs}
          value={tab}
          href={(value) => spaceSettingsRoute(spaceId, value)}
          label="Bereiche der Space-Einstellungen"
          idPrefix="space-settings"
        >
          {(value) => {
            switch (value) {
              case 'general':
                return (
                  <SpaceGeneralSection
                    spaceId={spaceId}
                    space={space}
                    canManage={canManage}
                    isOwner={isOwner}
                  />
                )
              case 'members':
                return (
                  <SpaceMembersSection
                    spaceId={spaceId}
                    space={space}
                    canManage={canManage}
                    isOwner={isOwner}
                  />
                )
              case 'content':
                return (
                  <SpaceContentSection
                    key={spaceId}
                    spaceId={spaceId}
                    canManage={canManageAssets(space.userRole, isOwner)}
                  />
                )
              // Every tab has its own branch: a value in SPACE_SETTINGS_TABS without a branch
              // lands here and is visibly led back to the master data instead of
              // silently showing another tab's content.
              default:
                return <Navigate to={spaceSettingsRoute(spaceId)} replace />
            }
          }}
        </AreaTabs>
      </Box>
    </Box>
  )
}
