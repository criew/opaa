import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Paper from '@mui/material/Paper'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { SourceReference } from '../../types/api'
import type { AccessLevel } from '../../types/chat'
import { deriveAccessLevel } from '../../utils/accessLevel'

const accessLevelColors: Record<AccessLevel, 'error' | 'warning' | 'success'> = {
  Confidential: 'error',
  Internal: 'warning',
  Public: 'success',
}

interface SourceCardProps {
  source: SourceReference
}

export default function SourceCard({ source }: SourceCardProps) {
  const accessLevel = deriveAccessLevel(source.fileName)
  const relevancePercent = Math.round(source.relevanceScore * 100)

  return (
    <Paper variant="outlined" sx={{ p: 1.5, bgcolor: 'background.default', maxWidth: 360 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
        <Typography variant="body2" fontWeight={600} noWrap sx={{ flexGrow: 1 }}>
          {source.fileName}
        </Typography>
        <Tooltip title="Access levels coming in a future release">
          <Chip
            label={accessLevel}
            size="small"
            color={accessLevelColors[accessLevel]}
            sx={{ height: 20, fontSize: '0.7rem' }}
          />
        </Tooltip>
      </Box>
      <Typography variant="caption" color="text.secondary">
        {relevancePercent}% relevant
      </Typography>
      {source.excerpt && (
        <Typography
          variant="body2"
          color="text.secondary"
          sx={{
            mt: 0.5,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            display: '-webkit-box',
            WebkitLineClamp: 2,
            WebkitBoxOrient: 'vertical',
          }}
        >
          {source.excerpt}
        </Typography>
      )}
    </Paper>
  )
}
