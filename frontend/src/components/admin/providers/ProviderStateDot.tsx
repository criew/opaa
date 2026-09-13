import Box from '@mui/material/Box'
import type { ProviderState } from '../oidcProviderState'

/**
 * Der Zustandspunkt eines Anbieters — dekorativ. Die Farbe trägt nie allein: Das Wort steht
 * immer daneben, in der Tabelle wie in der Legende der Einrichtungsanleitung.
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
