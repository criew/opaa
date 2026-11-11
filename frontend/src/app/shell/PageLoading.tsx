import Box from '@mui/material/Box'
import LoadingState from '@/shared/ui/states/LoadingState'

/**
 * Suspense fallback while a page's chunk loads: the skeleton of a page, shown only after the
 * delay of {@link LoadingState}, so a chunk from the cache does not flash anything.
 */
export default function PageLoading() {
  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
      <LoadingState shape="detail" count={4} />
    </Box>
  )
}
