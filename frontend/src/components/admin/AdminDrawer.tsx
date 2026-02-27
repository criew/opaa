import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { useIndexingStore } from '../../stores/indexingStore'

const DRAWER_WIDTH = 320

export default function AdminDrawer() {
  const drawerOpen = useIndexingStore((s) => s.drawerOpen)
  const setDrawerOpen = useIndexingStore((s) => s.setDrawerOpen)
  const status = useIndexingStore((s) => s.status)
  const documentCount = useIndexingStore((s) => s.documentCount)
  const totalDocuments = useIndexingStore((s) => s.totalDocuments)
  const timestamp = useIndexingStore((s) => s.timestamp)
  const trigger = useIndexingStore((s) => s.triggerIndexing)

  const isRunning = status === 'RUNNING'
  const progressPercent =
    totalDocuments > 0 ? Math.round((documentCount / totalDocuments) * 100) : 0

  return (
    <Drawer
      anchor="right"
      open={drawerOpen}
      onClose={() => setDrawerOpen(false)}
      sx={{ '& .MuiDrawer-paper': { width: DRAWER_WIDTH } }}
    >
      <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            p: 2,
          }}
        >
          <Typography variant="h6" fontWeight={700}>
            Admin
          </Typography>
          <IconButton onClick={() => setDrawerOpen(false)} aria-label="Close admin drawer">
            <CloseIcon />
          </IconButton>
        </Box>

        <Divider />

        <Box sx={{ p: 2 }}>
          <Typography variant="overline" color="text.secondary">
            Document Indexing
          </Typography>

          <Button
            variant="contained"
            startIcon={<PlayArrowIcon />}
            onClick={trigger}
            disabled={isRunning}
            fullWidth
            sx={{ mt: 1.5, mb: 2 }}
          >
            {isRunning ? 'Indexing...' : 'Index Documents'}
          </Button>

          {isRunning && (
            <Box sx={{ mb: 2 }}>
              <LinearProgress
                variant={totalDocuments > 0 ? 'determinate' : 'indeterminate'}
                value={progressPercent}
                sx={{ mb: 1 }}
              />
              <Typography variant="body2" color="text.secondary">
                {totalDocuments > 0
                  ? `${documentCount} of ${totalDocuments} documents indexed`
                  : 'Discovering documents...'}
              </Typography>
            </Box>
          )}

          {status !== 'IDLE' && !isRunning && (
            <Box sx={{ mt: 1 }}>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                Last indexing: {status === 'COMPLETED' ? 'Completed' : 'Failed'}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Documents: {documentCount}
              </Typography>
              {timestamp && (
                <Typography variant="caption" color="text.secondary">
                  {new Date(timestamp).toLocaleString()}
                </Typography>
              )}
            </Box>
          )}
        </Box>
      </Box>
    </Drawer>
  )
}
