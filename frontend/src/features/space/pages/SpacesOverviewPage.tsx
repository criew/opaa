import { useCallback, useEffect } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import OverviewPage, { OverviewCard } from '@/shared/ui/overview/OverviewPage'
import MetaBadge from '@/shared/ui/MetaBadge'
import { SuccessionStateNote } from '@/features/succession'
import { useSpaceStore } from '../stores/spaceStore'
import type { SpaceListResponse } from '@/shared/api/types'

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
        {/* Beside the space column the cards are narrow; a long compound word breaks instead of
            running out of its card. */}
        <Typography
          component="span"
          sx={{ fontSize: 16.5, fontWeight: 600, flex: 1, minWidth: 0, overflowWrap: 'anywhere' }}
        >
          {space.name}
        </Typography>
        {space.archived && <MetaBadge>Archiviert</MetaBadge>}
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
      {/* ADR-0036, Entscheidung 6 requires the marking in overview *and* detail view -
         state and addressee, without date, owner or reason. */}
      <SuccessionStateNote succession={space.succession} variant="badge" />
    </OverviewCard>
  )
}

/** The Spaces overview (mockup 1c) on the shared overview frame. */
export default function SpacesOverviewPage() {
  const navigate = useNavigate()
  const spaces = useSpaceStore((s) => s.spaces)
  const isLoading = useSpaceStore((s) => s.isLoadingList)
  const error = useSpaceStore((s) => s.error)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)

  // Re-read on every visit: the chat figure changes through actions on other pages,
  // and the cached cards stay visible while the fresh list loads.
  useEffect(() => {
    void loadSpaces()
  }, [loadSpaces])

  const searchText = useCallback(
    (space: SpaceListResponse) => `${space.name} ${space.description ?? ''}`,
    [],
  )

  return (
    <OverviewPage<SpaceListResponse>
      title="Spaces"
      createLabel="Neuer Space"
      onCreate={() => navigate('/spaces/new')}
      items={spaces}
      itemKey={(space) => space.id}
      searchText={searchText}
      isLoading={isLoading}
      error={error}
      renderCard={(space) => <SpaceCard space={space} />}
      emptyState={
        <Typography sx={{ color: 'text.secondary' }}>
          Noch kein Space — legen Sie über „Neuer Space“ den ersten an.
        </Typography>
      }
    />
  )
}
