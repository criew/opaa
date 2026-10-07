import { useId } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { CapabilityOverviewResponse } from '../../../types/api'
import { radius } from '../../../theme/tokens'
import AccessBadge from './AccessBadge'
import {
  accessBadgeLabel,
  bucketByAccess,
  currentAccess,
  scopeKind,
  scopeShortLabel,
  type AccessBucket,
} from './capabilityAccess'
import { presentationOf } from './capabilityPresentation'

const NAMES_SHOWN = 3

/** "Referat 32 Ordnung, Ines Vogel und 2 weitere" - the named subjects of a right. */
function namesLine(entry: CapabilityOverviewResponse): string | null {
  const names = currentAccess(entry).subjects.map((subject) => subject.name)
  if (names.length === 0) return null
  if (names.length <= NAMES_SHOWN) return names.join(', ')
  return `${names.slice(0, NAMES_SHOWN).join(', ')} und ${names.length - NAMES_SHOWN} weitere`
}

function ScopeBuckets({ title, buckets }: { title: string; buckets: AccessBucket[] }) {
  return (
    <Box>
      <Typography
        component="h4"
        sx={{ fontSize: 11.5, fontWeight: 600, color: 'text.secondary', letterSpacing: 0.4 }}
      >
        {title}
      </Typography>
      <Box
        component="ul"
        sx={{
          listStyle: 'none',
          p: 0,
          m: 0,
          mt: 0.75,
          display: 'grid',
          gridTemplateColumns: 'max-content 1fr',
          columnGap: 1.25,
          rowGap: 0.75,
          alignItems: 'baseline',
        }}
      >
        {buckets.map((bucket) => (
          <Box
            component="li"
            key={`${bucket.level}:${bucket.label}`}
            sx={{
              gridColumn: '1 / -1',
              display: 'grid',
              gridTemplateColumns: 'subgrid',
              alignItems: 'baseline',
            }}
          >
            <Box>
              <AccessBadge
                tone={bucket.level}
                label={accessBadgeLabel(currentAccess(bucket.entries[0]))}
              />
            </Box>
            <Box>
              <Typography component="span" sx={{ fontSize: 13.5 }}>
                {bucket.entries.map(scopeShortLabel).join(' · ')}
              </Typography>
              {bucket.level === 'SELECTED' && (
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                  Freigegeben für {bucket.label}
                </Typography>
              )}
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  )
}

interface CapabilityRowProps {
  /** One right; several entries when it is granted per scope. */
  entries: CapabilityOverviewResponse[]
  onEdit: () => void
}

/**
 * One right of the overview: what it creates, who may, and the one way to change it. A right with
 * scopes lists them grouped by state, so a deviating source stands out instead of drowning among
 * equal ones. The backend's plain-text line (ADR-0036, Entscheidung 5) is part of the row's text
 * for screen readers and stands visibly in the panel.
 */
export default function CapabilityRow({ entries, onEdit }: CapabilityRowProps) {
  const headingId = useId()
  const first = entries[0]
  const { title, description, icon: Icon } = presentationOf(first.capability, first.label)
  const scoped = Boolean(first.scope)
  const typeBuckets = bucketByAccess(entries.filter((entry) => scopeKind(entry) === 'TYPE'))
  const profileBuckets = bucketByAccess(entries.filter((entry) => scopeKind(entry) === 'PROFILE'))
  const access = currentAccess(first)
  const names = scoped ? null : namesLine(first)

  return (
    <Box
      component="li"
      aria-labelledby={headingId}
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: 'auto 1fr', md: 'auto 1fr auto' },
        columnGap: 2,
        rowGap: 1.5,
        alignItems: 'start',
        px: { xs: 0.5, md: 1 },
        py: 2.5,
        '& + &': { borderTop: 1, borderColor: 'divider' },
      }}
    >
      <Box
        aria-hidden="true"
        sx={{
          width: 40,
          height: 40,
          borderRadius: `${radius.md}px`,
          display: 'grid',
          placeItems: 'center',
          color: 'primary.main',
          bgcolor: (t) => alpha(t.palette.primary.main, 0.08),
        }}
      >
        <Icon sx={{ fontSize: 22 }} />
      </Box>

      <Box sx={{ minWidth: 0 }}>
        <Typography id={headingId} component="h3" sx={{ fontSize: 15.5, fontWeight: 600 }}>
          {title}
        </Typography>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.25 }}>
          {description}
        </Typography>
        {!scoped && (
          <Box
            sx={{ mt: 1.25, display: 'flex', gap: 1.25, alignItems: 'baseline', flexWrap: 'wrap' }}
          >
            <AccessBadge tone={access.level} label={accessBadgeLabel(access)} />
            {names && access.level === 'SELECTED' && (
              <Typography sx={{ fontSize: 13.5 }}>{names}</Typography>
            )}
            {names && access.level === 'ALL' && (
              // Named grants next to all accounts take no effect until the right is restricted.
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                Zusätzlich eingetragen: {names}
              </Typography>
            )}
            <Box component="span" sx={visuallyHidden}>
              {first.statement}
            </Box>
          </Box>
        )}
        {scoped && (
          <Stack spacing={1.5} sx={{ mt: 1.5 }}>
            {typeBuckets.length > 0 && <ScopeBuckets title="Quellen" buckets={typeBuckets} />}
            {profileBuckets.length > 0 && <ScopeBuckets title="Zugänge" buckets={profileBuckets} />}
            <Box component="span" sx={visuallyHidden}>
              {entries.map((entry) => entry.statement).join(' ')}
            </Box>
          </Stack>
        )}
      </Box>

      <Box sx={{ gridColumn: { xs: '2', md: '3' }, justifySelf: { md: 'end' } }}>
        <Button variant="outlined" size="small" onClick={onEdit} aria-label={`Ändern: ${title}`}>
          Ändern
        </Button>
      </Box>
    </Box>
  )
}
