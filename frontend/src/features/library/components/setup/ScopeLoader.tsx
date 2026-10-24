import { useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'

interface ScopeLoaderProps {
  /** „Ordner laden“, „Spaces laden“ */
  loadLabel: string
  /** What loading needs and shows, before the first load. */
  hint: string
  /** The values the listing depends on; a change asks for a new load. */
  token: string
  /** Load right away, e.g. in edit mode or once the connection check passed. */
  autoLoad?: boolean
  /** The list or tree; mounted afresh on every load. */
  children: (generation: number) => ReactNode
  /** Shown before the first load next to the button, e.g. the stored selection. */
  before?: ReactNode
}

/**
 * The listing part of „Umfang“: nothing is fetched before the person asks for it, because the
 * listing signs in with the values above. One button, the same wording for every source.
 */
export default function ScopeLoader({
  loadLabel,
  hint,
  token,
  autoLoad = false,
  children,
  before,
}: ScopeLoaderProps) {
  const [loaded, setLoaded] = useState<{ token: string; generation: number } | null>(
    autoLoad ? { token, generation: 1 } : null,
  )
  const current = loaded?.token === token ? loaded : autoLoad ? { token, generation: 0 } : null
  return (
    <Box>
      {!current && (
        <>
          {before}
          <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1 }}>
            {loaded ? 'Die Angaben oben haben sich geändert. Bitte neu laden.' : hint}
          </Typography>
        </>
      )}
      <Button
        variant="outlined"
        size="small"
        onClick={() =>
          setLoaded({ token, generation: (current?.generation ?? loaded?.generation ?? 0) + 1 })
        }
        sx={{ mb: current ? 1.5 : 0 }}
      >
        {current ? 'Erneut laden' : loadLabel}
      </Button>
      {current && <Box key={current.generation}>{children(current.generation)}</Box>}
    </Box>
  )
}
