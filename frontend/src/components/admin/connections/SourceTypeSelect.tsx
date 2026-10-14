import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { SourceTypeDescriptor, SourceTypeKey } from '@/shared/api/types'
import SourceTypeIcon from './sourceTypeIcon'
import { sourceRegistration } from '../../library/sources/registry'
import { admitsProfiles, NO_PROFILES_REASON } from './connectionProfileLabels'

function descriptionOf(type: SourceTypeDescriptor): string | undefined {
  return sourceRegistration(type.type as SourceTypeKey)?.description
}

interface SourceTypeSelectProps {
  types: SourceTypeDescriptor[]
  value: string | null
  onChange: (sourceType: string) => void
}

/**
 * The source type of a new profile as one select field: each entry with its icon, name and a short
 * description, the closed field with the name only and the description of the choice below it. A
 * type without profiles stays in the list with its reason; it is marked unavailable rather than
 * dimmed, so the reason keeps its contrast, and choosing it changes nothing.
 */
export default function SourceTypeSelect({ types, value, onChange }: SourceTypeSelectProps) {
  const chosen = types.find((type) => type.type === value) ?? null
  const nameOf = (key: string) => types.find((type) => type.type === key)?.displayName ?? key

  return (
    <TextField
      select
      required
      size="small"
      label="Quellart"
      value={value ?? ''}
      onChange={(event) => {
        const next = types.find((type) => type.type === event.target.value)
        if (next && admitsProfiles(next)) onChange(next.type)
      }}
      helperText={
        chosen
          ? (descriptionOf(chosen) ?? 'Für welche Quellart gilt der Zugang?')
          : 'Für welche Quellart gilt der Zugang?'
      }
      slotProps={{
        select: {
          renderValue: (selected) => nameOf(String(selected)),
          MenuProps: { slotProps: { paper: { sx: { maxHeight: 420 } } } },
        },
      }}
    >
      {types.map((type) => {
        const available = admitsProfiles(type)
        const secondary = available ? descriptionOf(type) : `Nicht möglich: ${NO_PROFILES_REASON}`
        return (
          <MenuItem
            key={type.type}
            value={type.type}
            aria-disabled={available ? undefined : true}
            sx={{
              alignItems: 'flex-start',
              gap: 1.5,
              py: 1,
              whiteSpace: 'normal',
              ...(!available && { cursor: 'not-allowed' }),
            }}
          >
            <Box
              aria-hidden="true"
              sx={{
                display: 'flex',
                mt: '2px',
                color: available ? 'primary.main' : 'text.secondary',
              }}
            >
              <SourceTypeIcon sourceType={type.type as SourceTypeKey} fontSize={18} />
            </Box>
            <Box sx={{ minWidth: 0 }}>
              <Typography
                sx={{
                  fontSize: 14,
                  fontWeight: 500,
                  color: available ? 'text.primary' : 'text.secondary',
                }}
              >
                {type.displayName}
              </Typography>
              {secondary && (
                <Typography
                  sx={{
                    fontSize: 12.5,
                    lineHeight: 1.4,
                    color: available ? 'text.secondary' : 'warning.main',
                  }}
                >
                  {secondary}
                </Typography>
              )}
            </Box>
          </MenuItem>
        )
      })}
    </TextField>
  )
}
