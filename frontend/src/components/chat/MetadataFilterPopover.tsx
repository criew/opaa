import { useEffect, useId, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import CircularProgress from '@mui/material/CircularProgress'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormGroup from '@mui/material/FormGroup'
import Popover from '@mui/material/Popover'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FilterListIcon from '@mui/icons-material/FilterList'
import type { MetadataFilter, MetadataFilterOptionsResponse } from '../../types/api'
import {
  metadataFilterScopeKey,
  useMetadataFilterOptionsStore,
  type MetadataFilterScope,
} from '../../stores/metadataFilterOptionsStore'
import { fillLevelText, notOfferedText } from './metadataFilterText'

interface MetadataFilterPopoverProps {
  /** The scope the next question would search - the options are loaded for exactly this scope. */
  scope: MetadataFilterScope
  filter: MetadataFilter | null
  onChange: (filter: MetadataFilter | null) => void
  disabled?: boolean
}

function fieldOf(options: MetadataFilterOptionsResponse, fieldKey: string) {
  return options.fields.find((field) => field.fieldKey === fieldKey)
}

/**
 * #1070: the filter popover next to the chip bar. Per filterable core field it shows the
 * Füllstand in the person's own search scope and offers the field only above the committed
 * threshold (metadata-schema.md, "Eintrittsbedingung für den Kernfeld-Filter"); the Dokumentart
 * choices are the values that actually occur in that scope, never the whole vocabulary. Nothing is
 * derived from the question - the person sets the filter, and it sticks to the chat.
 */
export default function MetadataFilterPopover({
  scope,
  filter,
  onChange,
  disabled = false,
}: MetadataFilterPopoverProps) {
  const [anchorEl, setAnchorEl] = useState<HTMLButtonElement | null>(null)
  const [draftTypes, setDraftTypes] = useState<string[]>(filter?.documentTypes ?? [])
  const [draftFrom, setDraftFrom] = useState(filter?.documentDateFrom ?? '')
  const [draftTo, setDraftTo] = useState(filter?.documentDateTo ?? '')
  const popoverId = useId()

  const options = useMetadataFilterOptionsStore((s) => s.options)
  const optionsScopeKey = useMetadataFilterOptionsStore((s) => s.optionsScopeKey)
  const isLoading = useMetadataFilterOptionsStore((s) => s.isLoading)
  const error = useMetadataFilterOptionsStore((s) => s.error)
  const loadOptions = useMetadataFilterOptionsStore((s) => s.loadOptions)

  const open = anchorEl !== null
  const scopeKey = metadataFilterScopeKey(scope)
  const optionsCurrent = optionsScopeKey === scopeKey && options !== null

  // Reloaded on every opening: the numbers must describe the bestand as it is now, and the
  // backend's per-person cache keeps the cost of that honest.
  useEffect(() => {
    if (open) {
      void loadOptions(scope)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, scopeKey])

  const typeField = optionsCurrent ? fieldOf(options, 'document_type') : undefined
  const dateField = optionsCurrent ? fieldOf(options, 'document_date') : undefined
  const dateInvalid = draftFrom !== '' && draftTo !== '' && draftTo < draftFrom

  const anyOffered = (typeField?.offered ?? false) || (dateField?.offered ?? false)

  // A field below the threshold is not offered, but a condition already set on it stays in force
  // (Koordinator-Festlegung an #1070): its existing value is carried through untouched, and only
  // its chip removes it.
  const draftFilter = useMemo((): MetadataFilter | null => {
    const next: MetadataFilter = {}
    const types = typeField?.offered ? draftTypes : (filter?.documentTypes ?? [])
    if (types.length > 0) next.documentTypes = [...types].sort()
    const from = dateField?.offered ? draftFrom : (filter?.documentDateFrom ?? '')
    const to = dateField?.offered ? draftTo : (filter?.documentDateTo ?? '')
    if (from !== '') next.documentDateFrom = from
    if (to !== '') next.documentDateTo = to
    return Object.keys(next).length === 0 ? null : next
  }, [dateField?.offered, draftFrom, draftTo, draftTypes, filter, typeField?.offered])

  const handleOpen = (event: React.MouseEvent<HTMLButtonElement>) => {
    setDraftTypes(filter?.documentTypes ?? [])
    setDraftFrom(filter?.documentDateFrom ?? '')
    setDraftTo(filter?.documentDateTo ?? '')
    setAnchorEl(event.currentTarget)
  }

  const handleApply = () => {
    onChange(draftFilter)
    setAnchorEl(null)
  }

  const handleClear = () => {
    onChange(null)
    setAnchorEl(null)
  }

  return (
    <>
      <Button
        size="small"
        variant="text"
        startIcon={<FilterListIcon />}
        onClick={handleOpen}
        disabled={disabled}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? popoverId : undefined}
        aria-label="Metadatenfilter setzen"
        sx={{ textTransform: 'none', fontSize: 12, py: 0.25 }}
      >
        Filter
      </Button>
      <Popover
        id={popoverId}
        open={open}
        anchorEl={anchorEl}
        onClose={() => setAnchorEl(null)}
        anchorOrigin={{ vertical: 'top', horizontal: 'left' }}
        transformOrigin={{ vertical: 'bottom', horizontal: 'left' }}
        slotProps={{ paper: { sx: { p: 2, width: 360, maxWidth: '90vw' } } }}
      >
        <Typography component="h2" sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>
          Nach Kernfeldern filtern
        </Typography>
        <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1.5 }}>
          Der Filter gilt für jede Frage dieses Chats. Ein Dokument ohne Angabe im gefilterten Feld
          bleibt gefunden und wird als „ohne Angabe“ gekennzeichnet.
        </Typography>
        {isLoading && !optionsCurrent && (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
            <CircularProgress size={20} aria-label="Filteroptionen werden geladen" />
          </Box>
        )}
        {error && (
          <Alert severity="error" sx={{ mb: 1 }}>
            {error}
          </Alert>
        )}
        {optionsCurrent && (
          <Stack spacing={2}>
            <Box>
              <Typography sx={{ fontSize: 13, fontWeight: 500 }}>Dokumentart</Typography>
              {typeField?.offered ? (
                <>
                  <Typography variant="caption" color="text.secondary" component="p">
                    {fillLevelText(typeField)}
                  </Typography>
                  {options.documentTypes.length === 0 ? (
                    <Typography variant="caption" color="text.secondary" component="p">
                      Im Suchbereich kommt keine Dokumentart vor.
                    </Typography>
                  ) : (
                    <FormGroup aria-label="Dokumentart">
                      {options.documentTypes.map((type) => (
                        <FormControlLabel
                          key={type.code}
                          control={
                            <Checkbox
                              size="small"
                              checked={draftTypes.includes(type.code)}
                              onChange={(e) =>
                                setDraftTypes((current) =>
                                  e.target.checked
                                    ? [...current, type.code]
                                    : current.filter((code) => code !== type.code),
                                )
                              }
                            />
                          }
                          label={`${type.label} (${type.documentCount})`}
                          slotProps={{ typography: { sx: { fontSize: 13 } } }}
                        />
                      ))}
                    </FormGroup>
                  )}
                </>
              ) : (
                typeField && (
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    component="p"
                    data-testid="filter-field-not-offered"
                  >
                    {notOfferedText(typeField)}
                    {(filter?.documentTypes ?? []).length > 0 &&
                      ' Die bereits gesetzte Bedingung bleibt wirksam; der Chip entfernt sie.'}
                  </Typography>
                )
              )}
            </Box>
            <Box>
              <Typography sx={{ fontSize: 13, fontWeight: 500 }}>Datum/Stand</Typography>
              {dateField?.offered ? (
                <>
                  <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1 }}>
                    {fillLevelText(dateField)}
                    {options.documentDateMin &&
                      options.documentDateMax &&
                      ` · Werte von ${options.documentDateMin} bis ${options.documentDateMax}`}
                  </Typography>
                  <Stack direction="row" spacing={1}>
                    <TextField
                      label="Von"
                      type="date"
                      size="small"
                      value={draftFrom}
                      onChange={(e) => setDraftFrom(e.target.value)}
                      slotProps={{ inputLabel: { shrink: true } }}
                      fullWidth
                    />
                    <TextField
                      label="Bis"
                      type="date"
                      size="small"
                      value={draftTo}
                      onChange={(e) => setDraftTo(e.target.value)}
                      error={dateInvalid}
                      helperText={dateInvalid ? 'Das Fenster endet vor seinem Beginn.' : undefined}
                      slotProps={{ inputLabel: { shrink: true } }}
                      fullWidth
                    />
                  </Stack>
                </>
              ) : (
                dateField && (
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    component="p"
                    data-testid="filter-field-not-offered"
                  >
                    {notOfferedText(dateField)}
                    {(filter?.documentDateFrom || filter?.documentDateTo) &&
                      ' Die bereits gesetzte Bedingung bleibt wirksam; der Chip entfernt sie.'}
                  </Typography>
                )
              )}
            </Box>
            <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
              <Button size="small" onClick={handleClear} disabled={filter === null}>
                Filter entfernen
              </Button>
              <Button
                size="small"
                variant="contained"
                onClick={handleApply}
                disabled={!anyOffered || dateInvalid}
              >
                Anwenden
              </Button>
            </Stack>
          </Stack>
        )}
      </Popover>
    </>
  )
}
