import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Paper from '@mui/material/Paper'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import type { SourceReference } from '../../types/api'
import type { AccessLevel } from '../../types/chat'
import { deriveAccessLevel } from '../../utils/accessLevel'
import { accessLevelLabel } from '../../utils/labels'

const accessLevelColors: Record<AccessLevel, 'error' | 'warning' | 'success'> = {
  Confidential: 'error',
  Internal: 'warning',
  Public: 'success',
}

interface SourceCardProps {
  source: SourceReference
}

function formatIndexedAt(indexedAt: string | null): string {
  if (!indexedAt) return '-'
  const d = new Date(indexedAt)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export default function SourceCard({ source }: SourceCardProps) {
  const accessLevel = deriveAccessLevel(source.fileName)
  const relevancePercent = Math.round(source.relevanceScore * 100)

  return (
    <Paper
      variant="outlined"
      // No stable role/label fits a source card as a whole (see e2e/README.md "Selektor-
      // Konvention"): it renders a file name the E2E suite (test(e2e) #424) must assert on
      // dynamically, not fixed, human-authored copy.
      data-testid="source-card"
      data-cited={source.cited}
      sx={{
        p: 1.5,
        bgcolor: 'background.default',
        width: 220,
        minWidth: 220,
        opacity: source.cited ? 1 : 0.6,
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
        <Tooltip title={source.fileName}>
          <Typography
            variant="body2"
            noWrap
            data-testid="source-card-file-name"
            sx={{ fontWeight: 600, flexGrow: 1 }}
          >
            {source.fileName}
          </Typography>
        </Tooltip>
        <Tooltip title="Zugriffsstufen folgen in einer künftigen Version">
          <Chip
            label={accessLevelLabel(accessLevel)}
            size="small"
            color={accessLevelColors[accessLevel]}
            sx={{ height: 20, fontSize: '0.7rem' }}
          />
        </Tooltip>
      </Box>
      {source.spaceName && (
        <Chip
          label={source.spaceName}
          size="small"
          variant="outlined"
          sx={{ mb: 0.75, height: 20, fontSize: '0.7rem' }}
        />
      )}
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mt: 0.5 }}>
        <Typography variant="caption" color="text.secondary">
          {relevancePercent}% relevant
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {source.matchCount} Treffer
        </Typography>
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
        Indexiert: {formatIndexedAt(source.indexedAt)}
      </Typography>
    </Paper>
  )
}
