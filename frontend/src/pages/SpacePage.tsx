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
import { useSpaceStore } from '../stores/spaceStore'
import { spaceRoleLabel } from '../utils/labels'

function canManage(role: string | undefined): boolean {
  return role === 'ADMIN'
}

export default function SpacePage() {
  const { spaceId } = useParams()
  const navigate = useNavigate()
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const selectSpace = useSpaceStore((s) => s.selectSpace)
  const spaces = useSpaceStore((s) => s.spaces)
  const space = useSpaceStore((s) => s.selectedSpace)
  const isLoadingDetails = useSpaceStore((s) => s.isLoadingDetails)
  const error = useSpaceStore((s) => s.error)

  const [membersExpanded, setMembersExpanded] = useState(true)

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
        <Typography variant="h6">Kein Space ausgewählt</Typography>
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
                <Typography variant="h5">{space.name}</Typography>
                {space.isDefault && (
                  <Chip label="Standard" size="small" color="primary" variant="outlined" />
                )}
              </Stack>
              <Typography color="text.secondary">
                {space.description || 'Keine Beschreibung hinterlegt.'}
              </Typography>
            </Box>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              {canManage(space.userRole) && (
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
          expanded={membersExpanded}
          onChange={(_, expanded) => setMembersExpanded(expanded)}
          variant="outlined"
          disableGutters
        >
          <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 2.5 }}>
            <Typography variant="h6">Mitglieder</Typography>
          </AccordionSummary>
          <AccordionDetails sx={{ px: 2.5, pb: 2.5, pt: 0 }}>
            <Divider sx={{ mb: 2 }} />
            {space.members.length === 0 ? (
              <Typography color="text.secondary">Keine Mitglieder gefunden.</Typography>
            ) : (
              <Stack spacing={1}>
                {space.members.map((member) => (
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
            )}
          </AccordionDetails>
        </Accordion>
      </Stack>
    </Box>
  )
}
