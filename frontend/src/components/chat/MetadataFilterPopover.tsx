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
import type {
  MetadataFilter,
  MetadataFilterLibraryFieldCondition,
  MetadataFilterLibraryFieldOption,
  MetadataFilterOptionsResponse,
} from '../../types/api'
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

/** A library field's identity is the pair (library, key) - two libraries may use the same key. */
function libraryFieldKey(field: { libraryId: string; fieldKey: string }) {
  return `${field.libraryId}/${field.fieldKey}`
}

function conditionsByKey(
  filter: MetadataFilter | null,
): Record<string, MetadataFilterLibraryFieldCondition> {
  const byKey: Record<string, MetadataFilterLibraryFieldCondition> = {}
  for (const condition of filter?.libraryFields ?? []) {
    byKey[libraryFieldKey(condition)] = condition
  }
  return byKey
}

function isEmptyCondition(condition: MetadataFilterLibraryFieldCondition): boolean {
  return (
    (condition.codes ?? []).length === 0 &&
    !condition.dateFrom &&
    !condition.dateTo &&
    !condition.value
  )
}

/**
 * the filter popover next to the chip bar. Per filterable core field it shows the
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
  const [draftLibraryFields, setDraftLibraryFields] = useState<
    Record<string, MetadataFilterLibraryFieldCondition>
  >(conditionsByKey(filter))
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

  const libraryFields: MetadataFilterLibraryFieldOption[] = useMemo(
    () => (optionsCurrent ? (options?.libraryFields ?? []) : []),
    [options, optionsCurrent],
  )
  const anyOffered =
    (typeField?.offered ?? false) ||
    (dateField?.offered ?? false) ||
    libraryFields.some((field) => field.offered)

  // A field below the threshold is not offered, but a condition already set on it stays in force
  // (Koordinator-Festlegung an): its existing value is carried through untouched, and only
  // its chip removes it.
  const draftFilter = useMemo((): MetadataFilter | null => {
    const next: MetadataFilter = {}
    const types = typeField?.offered ? draftTypes : (filter?.documentTypes ?? [])
    if (types.length > 0) next.documentTypes = [...types].sort()
    const from = dateField?.offered ? draftFrom : (filter?.documentDateFrom ?? '')
    const to = dateField?.offered ? draftTo : (filter?.documentDateTo ?? '')
    if (from !== '') next.documentDateFrom = from
    if (to !== '') next.documentDateTo = to
    // A library field the scope no longer offers keeps whatever the chat already carries - the same
    // rule the core fields follow; only its chip removes it.
    const offeredKeys = new Set(libraryFields.filter((f) => f.offered).map(libraryFieldKey))
    const carried = Object.entries(conditionsByKey(filter))
      .filter(([key]) => !offeredKeys.has(key))
      .map(([, condition]) => condition)
    const chosen = Object.entries(draftLibraryFields)
      .filter(([key]) => offeredKeys.has(key))
      .map(([, condition]) => condition)
      .filter((condition) => !isEmptyCondition(condition))
    const conditions = [...carried, ...chosen]
    if (conditions.length > 0) next.libraryFields = conditions
    return Object.keys(next).length === 0 ? null : next
  }, [
    dateField?.offered,
    draftFrom,
    draftTo,
    draftTypes,
    draftLibraryFields,
    filter,
    libraryFields,
    typeField?.offered,
  ])

  const handleOpen = (event: React.MouseEvent<HTMLButtonElement>) => {
    setDraftTypes(filter?.documentTypes ?? [])
    setDraftFrom(filter?.documentDateFrom ?? '')
    setDraftTo(filter?.documentDateTo ?? '')
    setDraftLibraryFields(conditionsByKey(filter))
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
            {libraryFields.map((field) => (
              <Box key={libraryFieldKey(field)} data-testid="filter-library-field">
                <Typography sx={{ fontSize: 13, fontWeight: 500 }}>
                  {field.label} · {field.libraryName}
                </Typography>
                {field.offered ? (
                  <>
                    <Typography variant="caption" color="text.secondary" component="p">
                      {fillLevelText(field)}
                    </Typography>
                    {field.type === 'SELECT' && field.values.length === 0 && (
                      <Typography variant="caption" color="text.secondary" component="p">
                        Im Suchbereich kommt kein Wert dieses Feldes vor.
                      </Typography>
                    )}
                    {field.type === 'SELECT' && field.values.length > 0 && (
                      <FormGroup aria-label={field.label}>
                        {field.values.map((value) => (
                          <FormControlLabel
                            key={value.code}
                            control={
                              <Checkbox
                                size="small"
                                checked={(
                                  draftLibraryFields[libraryFieldKey(field)]?.codes ?? []
                                ).includes(value.code)}
                                onChange={(e) =>
                                  setDraftLibraryFields((current) => {
                                    const key = libraryFieldKey(field)
                                    const codes = current[key]?.codes ?? []
                                    return {
                                      ...current,
                                      [key]: {
                                        libraryId: field.libraryId,
                                        fieldKey: field.fieldKey,
                                        codes: e.target.checked
                                          ? [...codes, value.code]
                                          : codes.filter((code) => code !== value.code),
                                      },
                                    }
                                  })
                                }
                              />
                            }
                            label={`${value.label} (${value.documentCount})`}
                            slotProps={{ typography: { sx: { fontSize: 13 } } }}
                          />
                        ))}
                      </FormGroup>
                    )}
                    {field.type === 'PATTERN' && (
                      <TextField
                        label="Kennung"
                        size="small"
                        fullWidth
                        helperText="Genau dieser Wert, kein Teiltreffer."
                        value={draftLibraryFields[libraryFieldKey(field)]?.value ?? ''}
                        onChange={(e) =>
                          setDraftLibraryFields((current) => ({
                            ...current,
                            [libraryFieldKey(field)]: {
                              libraryId: field.libraryId,
                              fieldKey: field.fieldKey,
                              value: e.target.value,
                            },
                          }))
                        }
                      />
                    )}
                    {field.type === 'DATE' && (
                      <Stack direction="row" spacing={1}>
                        <TextField
                          label="Von"
                          type="date"
                          size="small"
                          fullWidth
                          slotProps={{ inputLabel: { shrink: true } }}
                          value={draftLibraryFields[libraryFieldKey(field)]?.dateFrom ?? ''}
                          onChange={(e) =>
                            setDraftLibraryFields((current) => ({
                              ...current,
                              [libraryFieldKey(field)]: {
                                ...current[libraryFieldKey(field)],
                                libraryId: field.libraryId,
                                fieldKey: field.fieldKey,
                                dateFrom: e.target.value,
                              },
                            }))
                          }
                        />
                        <TextField
                          label="Bis"
                          type="date"
                          size="small"
                          fullWidth
                          slotProps={{ inputLabel: { shrink: true } }}
                          value={draftLibraryFields[libraryFieldKey(field)]?.dateTo ?? ''}
                          onChange={(e) =>
                            setDraftLibraryFields((current) => ({
                              ...current,
                              [libraryFieldKey(field)]: {
                                ...current[libraryFieldKey(field)],
                                libraryId: field.libraryId,
                                fieldKey: field.fieldKey,
                                dateTo: e.target.value,
                              },
                            }))
                          }
                        />
                      </Stack>
                    )}
                  </>
                ) : (
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    component="p"
                    data-testid="filter-field-not-offered"
                  >
                    {notOfferedText(field)}
                  </Typography>
                )}
              </Box>
            ))}
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
