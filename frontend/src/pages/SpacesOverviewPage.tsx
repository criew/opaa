import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import MetaBadge from '../components/MetaBadge'
import { Link as RouterLink, useNavigate } from 'react-router'
import PageHeading from '../components/a11y/PageHeading'
import { useSpaceStore } from '../stores/spaceStore'
import { blue, fontFamily } from '../theme/tokens'
import { spaceRoleLabel } from '../utils/labels'
import type { SpaceListResponse } from '../types/api'

function plural(count: number, singular: string, pluralForm: string): string {
  return `${count} ${count === 1 ? singular : pluralForm}`
}

/** Mockup 1c's figures line: "n Quellen · n Chats · n Mitglieder" (or "… · nur Sie" for the
 *  personal space). The source and chat figures arrived with #682 and are optional in the API,
 *  so a list without them still shows the member figure alone. */
function spaceFigures(space: SpaceListResponse): string {
  const members =
    space.isDefault && space.memberCount <= 1
      ? 'nur Sie'
      : plural(space.memberCount, 'Mitglied', 'Mitglieder')
  if (space.libraryCount === undefined || space.chatCount === undefined) return members
  return [
    plural(space.libraryCount, 'Quelle', 'Quellen'),
    plural(space.chatCount, 'Chat', 'Chats'),
    members,
  ].join(' · ')
}

/**
 * The Spaces overview as a card grid (#593, mockup 1c) - until now /spaces bounced straight to
 * the first space; this page makes "Alle Spaces anzeigen" a real destination.
 */
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

  const raeume = spaces.length === 1 ? '1 Raum, in dem' : `${spaces.length} Räume, in denen`

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2, mb: 2.5, flexWrap: 'wrap' }}>
        <PageHeading title="Spaces" />
        <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
          {raeume} Sie Mitglied sind
        </Typography>
        <Button
          variant="contained"
          onClick={() => navigate('/spaces/new')}
          sx={{ ml: 'auto', flex: 'none' }}
        >
          Neuer Space
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {isLoading && spaces.length === 0 ? (
        <Box sx={{ py: 6, display: 'flex', justifyContent: 'center' }}>
          <CircularProgress size={24} aria-label="Spaces werden geladen" />
        </Box>
      ) : (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
            gap: 2.25,
          }}
        >
          {spaces.map((space) => (
            <ButtonBase
              key={space.id}
              component={RouterLink}
              to={`/spaces/${space.id}`}
              // Mockup 1c's card: 16px radius, quiet border, lift on hover - motion stays on
              // transform only (guidelines 4.5).
              sx={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'stretch',
                textAlign: 'left',
                gap: 1,
                p: 2.5,
                border: 1,
                borderColor: 'divider',
                borderRadius: '16px',
                bgcolor: 'background.paper',
                transition: (theme) =>
                  theme.transitions.create(['border-color', 'transform'], {
                    duration: theme.transitions.duration.shortest,
                  }),
                '&:hover': {
                  borderColor: blue[300],
                  transform: 'translateY(-2px)',
                },
              }}
            >
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <Typography
                  component="span"
                  sx={{
                    fontFamily: fontFamily.mono,
                    fontSize: 9.5,
                    letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                    color: space.isDefault ? 'primary.main' : 'text.secondary',
                    flex: 1,
                  }}
                >
                  {space.isDefault ? 'Persönlich' : 'Team'}
                </Typography>
                {space.archived && <Chip label="Archiviert" size="small" variant="outlined" />}
              </Box>
              <Typography component="span" sx={{ fontSize: 16.5, fontWeight: 600 }}>
                {space.name}
              </Typography>
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
              <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
                {spaceFigures(space)}
              </Typography>
              <Box sx={{ display: 'flex', gap: 0.75 }}>
                {/* #957: MetaBadge instead of a hand-rolled twin - same optics, and its accent
                    colour is scheme-aware (blue[700] only reached 3.8:1 on the dark card). */}
                <MetaBadge accent>{spaceRoleLabel(space.userRole)}</MetaBadge>
              </Box>
            </ButtonBase>
          ))}

          <ButtonBase
            onClick={() => navigate('/spaces/new')}
            aria-label="Neuen Space anlegen"
            sx={{
              minHeight: 150,
              border: '1px dashed',
              borderColor: 'text.disabled',
              borderRadius: '16px',
              display: 'grid',
              placeItems: 'center',
              fontSize: 13.5,
              fontWeight: 500,
              color: 'primary.main',
              '&:hover': { borderColor: blue[300], bgcolor: 'action.hover' },
            }}
          >
            {spaces.length === 0 ? (
              <Box sx={{ textAlign: 'center', px: 2 }}>
                <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 0.5 }}>
                  Noch kein Space — legen Sie den ersten an.
                </Typography>
                <Typography component="span" sx={{ fontSize: 13.5, fontWeight: 500 }}>
                  + Neuen Space anlegen
                </Typography>
              </Box>
            ) : (
              <>+ Neuen Space anlegen</>
            )}
          </ButtonBase>
        </Box>
      )}
    </Box>
  )
}
