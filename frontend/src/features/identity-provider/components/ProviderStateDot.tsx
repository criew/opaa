import Box from '@mui/material/Box'
import type { ProviderState } from './oidcProviderState'

/**
 * A provider's state dot — decorative. The colour never carries alone: the word is
 * always next to it, in the table as in the legend of the setup instructions.
 */
export default function ProviderStateDot({ state }: { state: ProviderState }) {
  return (
    <Box
      component="span"
      aria-hidden="true"
      sx={{
        width: 8,
        height: 8,
        borderRadius: '50%',
        flex: 'none',
        bgcolor:
          state === 'reachable'
            ? 'success.main'
            : state === 'unreachable'
              ? 'error.main'
              : 'text.disabled',
      }}
    />
  )
}
