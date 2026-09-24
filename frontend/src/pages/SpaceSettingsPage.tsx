import { useEffect, useMemo } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { Navigate, useParams } from 'react-router'
import type { SpaceRole } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import PageHeading from '../components/a11y/PageHeading'
import AreaTabs from '../components/AreaTabs'
import MetaBadge from '../components/MetaBadge'
import SpaceGeneralSection from '../components/space/SpaceGeneralSection'
import SpaceKnowledgeSection from '../components/space/SpaceKnowledgeSection'
import SpaceMembersSection from '../components/space/SpaceMembersSection'
import { SPACE_SETTINGS_TABS, spaceSettingsRoute, type SpaceSettingsTab } from '../routes'

/**
 * Die Reiter in der Reihenfolge der Leiste. Ein weiterer Asset-Typ („Prompts") ist eine weitere
 * Zeile hier, ein weiterer Wert in {@link SPACE_SETTINGS_TABS} und ein weiterer Zweig unten.
 */
const tabs: Array<{ value: SpaceSettingsTab; label: string }> = [
  { value: 'general', label: 'Stammdaten' },
  { value: 'members', label: 'Mitglieder' },
  { value: 'knowledge', label: 'Wissen' },
]

function isSpaceSettingsTab(value: string | undefined): value is SpaceSettingsTab {
  return SPACE_SETTINGS_TABS.some((tab) => tab === value)
}

function canManageMembers(role: SpaceRole | undefined): boolean {
  return role === 'ADMIN'
}

// #203: a CURATOR may associate and detach libraries, one level below ADMIN's member management -
// docs/features/spaces-and-assets.md#space-rollen ("CURATOR: zusätzlich Assets assoziieren und
// lösen").
function canManageLibraries(role: SpaceRole | undefined, isOwner: boolean): boolean {
  return role === 'CURATOR' || role === 'ADMIN' || isOwner
}

/**
 * Die eine Einstellungsseite eines Space (#1917), erreichbar über das Zahnrad am Fuß der
 * Seitenleiste. Jeder Reiter ist eine eigene Route, damit ein Verweis im richtigen Bereich landet
 * und ein Neuladen ihn behält; geladen wird nur, was der sichtbare Reiter braucht.
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

  // Ein Tippfehler im Pfad wird nicht stillschweigend als Stammdaten gelesen: Die Adresszeile
  // sagt am Ende, was tatsächlich zu sehen ist.
  if (!isSpaceSettingsTab(tab)) {
    return <Navigate to={spaceSettingsRoute(spaceId)} replace />
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
          // #1815, ADR-0036 Entscheidung 6: state and addressee, deliberately without a date,
          // without the previous owner and without a reason - those belong in the operational list
          // (#1819), not beside a colleague's name. The space stays fully usable.
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
          {(value) =>
            value === 'general' ? (
              <SpaceGeneralSection
                spaceId={spaceId}
                space={space}
                canManage={canManage}
                isOwner={isOwner}
              />
            ) : value === 'members' ? (
              <SpaceMembersSection
                spaceId={spaceId}
                space={space}
                canManage={canManage}
                isOwner={isOwner}
              />
            ) : (
              <SpaceKnowledgeSection
                spaceId={spaceId}
                canManage={canManageLibraries(space.userRole, isOwner)}
              />
            )
          }
        </AreaTabs>
      </Box>
    </Box>
  )
}
