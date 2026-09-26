import { useCallback, useEffect } from 'react'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import TableCell from '@mui/material/TableCell'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import MetaBadge from '../components/MetaBadge'
import OverviewPage, { OverviewCard, OverviewRowLink } from '../components/overview/OverviewPage'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'
import { useSpaceStore } from '../stores/spaceStore'
import { spaceMembershipLabel, spaceRoleLabel } from '../utils/labels'
import type { SpaceListResponse } from '../types/api'

function plural(count: number, singular: string, pluralForm: string): string {
  return `${count} ${count === 1 ? singular : pluralForm}`
}

/** A card selects the space and opens an empty chat in it. An archived space accepts no new chats
 *  (ChatService rejects the create), so it leads to its overview instead. */
function spaceHref(space: SpaceListResponse): string {
  return space.archived ? `/spaces/${space.id}` : `/spaces/${space.id}/chats/new`
}

function SpaceCard({ space }: { space: SpaceListResponse }) {
  return (
    <OverviewCard to={spaceHref(space)}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Typography component="span" sx={{ fontSize: 16.5, fontWeight: 600, flex: 1 }}>
          {space.name}
        </Typography>
        {space.archived && <Chip label="Archiviert" size="small" variant="outlined" />}
      </Box>
      <Typography
        component="p"
        sx={{
          fontSize: 12.5,
          color: 'text.secondary',
          m: 0,
          flex: 1,
          display: '-webkit-box',
          WebkitLineClamp: 2,
          WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
        }}
      >
        {space.description ?? ''}
      </Typography>
      {space.chatCount !== undefined && (
        <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
          {plural(space.chatCount, 'Chat', 'Chats')}
        </Typography>
      )}
      {/* ADR-0036, Entscheidung 6 verlangt die Kennzeichnung in Übersicht *und* Detailansicht -
          Zustand und Adressat, ohne Datum, Eigentümer oder Grund. */}
      <SuccessionStateNote succession={space.succession} variant="badge" />
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75 }}>
        <MetaBadge accent>{spaceRoleLabel(space.userRole)}</MetaBadge>
        <MetaBadge>{spaceMembershipLabel(space.memberships)}</MetaBadge>
      </Box>
    </OverviewCard>
  )
}

function SpaceRow({ space }: { space: SpaceListResponse }) {
  return (
    <>
      <TableCell>
        <OverviewRowLink to={spaceHref(space)}>{space.name}</OverviewRowLink>
        {space.description && (
          <Typography component="div" sx={{ fontSize: 11.5, color: 'text.disabled' }}>
            {space.description}
          </Typography>
        )}
        <SuccessionStateNote succession={space.succession} variant="badge" />
      </TableCell>
      <TableCell>{space.chatCount ?? '–'}</TableCell>
      <TableCell>{spaceMembershipLabel(space.memberships)}</TableCell>
      <TableCell>
        <MetaBadge accent>{spaceRoleLabel(space.userRole)}</MetaBadge>
      </TableCell>
      <TableCell>{space.archived ? 'Archiviert' : 'Aktiv'}</TableCell>
    </>
  )
}

const columns = [
  { key: 'name', label: 'Name' },
  { key: 'chats', label: 'Chats' },
  { key: 'members', label: 'Mitglieder' },
  { key: 'role', label: 'Ihre Rolle' },
  { key: 'state', label: 'Zustand' },
]

/** The Spaces overview (#593, mockup 1c) on the shared overview frame (#1913). */
export default function SpacesOverviewPage() {
  const navigate = useNavigate()
  const spaces = useSpaceStore((s) => s.spaces)
  const isLoading = useSpaceStore((s) => s.isLoadingList)
  const error = useSpaceStore((s) => s.error)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)

  useEffect(() => {
    if (spaces.length === 0) {
      void loadSpaces()
    }
  }, [loadSpaces, spaces.length])

  const searchText = useCallback(
    (space: SpaceListResponse) => `${space.name} ${space.description ?? ''}`,
    [],
  )

  return (
    <OverviewPage<SpaceListResponse>
      title="Spaces"
      heading={(count) => (count === 1 ? '1 Space' : `${count} Spaces`)}
      createLabel="Neuer Space"
      onCreate={() => navigate('/spaces/new')}
      storageKey="spaces"
      defaultView="cards"
      items={spaces}
      itemKey={(space) => space.id}
      searchText={searchText}
      isLoading={isLoading}
      error={error}
      columns={columns}
      renderCard={(space) => <SpaceCard space={space} />}
      renderRow={(space) => <SpaceRow space={space} />}
      emptyState={
        <Typography sx={{ color: 'text.secondary' }}>
          Noch kein Space — legen Sie über „Neuer Space“ den ersten an.
        </Typography>
      }
    />
  )
}
