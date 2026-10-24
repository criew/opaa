import { useCallback, useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import TabSection from '@/shared/ui/TabSection'
import { Link as RouterLink } from 'react-router'
import { plural } from '@/shared/utils/format'
import Link from '@mui/material/Link'
import {
  deleteLibraryMetadataField,
  getLibraryMetadataFieldUsage,
  getLibraryMetadataFieldValueUsage,
  listLibraryMetadataFields,
  relabelLibraryMetadataFieldValue,
  remapLibraryMetadataFieldValue,
  runLibraryMetadataSchemaChanges,
  updateCoreContextPrefix,
} from '../../api/libraryMetadataApi'
import type {
  CoreContextPrefixResponse,
  LibraryMetadataFieldResponse,
  LibraryMetadataSchemaChangeResponse,
} from '@/shared/api/types'
import { TYPE_LABELS, describeChange, effectChips } from './metadataFieldText'
import CoreContextPrefixSwitch from './CoreContextPrefixSwitch'
import AddValueButton from './AddValueButton'
import CreateFieldDialog from './CreateFieldDialog'
import EditFieldDialog from './EditFieldDialog'
import RemapValueDialog from './RemapValueDialog'
import DeleteFieldDialog from './DeleteFieldDialog'

interface Props {
  libraryId: string
  /** Only a person with the management right at the library may change the schema. */
  canManageSchema: boolean
  onFieldsChanged?: () => void
}

/**
 * The "Metadatenfelder" section of a library's settings (metadata-schema.md Teil V): the
 * library's own fields with their Wirkstellen and value lists, and the two operations the
 * specification guards - a field is only accepted with a retrieval effect, and a value is only
 * removed together with a confirmed mapping whose Folgekosten stand beforehand.
 */
export default function LibraryMetadataFieldsSection({
  libraryId,
  canManageSchema,
  onFieldsChanged,
}: Props) {
  const [fields, setFields] = useState<LibraryMetadataFieldResponse[]>([])
  const [coreContextPrefix, setCoreContextPrefix] = useState<CoreContextPrefixResponse | null>(null)
  const [awaitingRerun, setAwaitingRerun] = useState(0)
  const [pendingChanges, setPendingChanges] = useState<LibraryMetadataSchemaChangeResponse[]>([])
  const [running, setRunning] = useState(false)
  // No state: the run reads the marks between two batches, it needs no re-render.
  // The run survives leaving the page (the running batch is completed), but afterwards
  // no longer writes into a component that has gone.
  const paused = useRef(false)
  const unmounted = useRef(false)
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [remapField, setRemapField] = useState<LibraryMetadataFieldResponse | null>(null)
  const [remapCode, setRemapCode] = useState<string | null>(null)
  const [remapUsage, setRemapUsage] = useState<number | null>(null)
  const [remapTarget, setRemapTarget] = useState<string>('')
  const [deleteField, setDeleteField] = useState<LibraryMetadataFieldResponse | null>(null)
  const [deleteUsage, setDeleteUsage] = useState<number | null>(null)
  const [editField, setEditField] = useState<LibraryMetadataFieldResponse | null>(null)
  const [valueLabel, setValueLabel] = useState('')

  const reload = useCallback(async () => {
    try {
      const response = await listLibraryMetadataFields(libraryId)
      setFields(response.items)
      setCoreContextPrefix(response.coreContextPrefix)
      setAwaitingRerun(response.documentsAwaitingContextPrefixRerun)
      setPendingChanges(response.pendingSchemaChanges)
      setError(null)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Die Metadatenfelder konnten nicht geladen werden',
      )
    }
  }, [libraryId])

  useEffect(() => {
    unmounted.current = false
    return () => {
      unmounted.current = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    listLibraryMetadataFields(libraryId)
      .then((response) => {
        if (cancelled) return
        setFields(response.items)
        setCoreContextPrefix(response.coreContextPrefix)
        setAwaitingRerun(response.documentsAwaitingContextPrefixRerun)
        setPendingChanges(response.pendingSchemaChanges)
        setError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(
          err instanceof Error ? err.message : 'Die Metadatenfelder konnten nicht geladen werden',
        )
      })
    return () => {
      cancelled = true
    }
  }, [libraryId])

  async function saveCoreContextPrefix(documentType: boolean, documentDate: boolean) {
    try {
      setCoreContextPrefix(await updateCoreContextPrefix(libraryId, { documentType, documentDate }))
      await reload()
      onFieldsChanged?.()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Wirkstelle konnte nicht geändert werden')
    }
  }

  async function openRemap(field: LibraryMetadataFieldResponse, code: string) {
    setRemapField(field)
    setRemapCode(code)
    setRemapTarget('')
    setRemapUsage(null)
    setValueLabel(field.values.find((value) => value.code === code)?.label ?? '')
    try {
      const usage = await getLibraryMetadataFieldValueUsage(libraryId, field.fieldKey, code)
      setRemapUsage(usage.documentCount)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Die Folgekosten konnten nicht ermittelt werden',
      )
    }
  }

  async function confirmRemap() {
    if (!remapField || !remapCode) return
    try {
      const result = await remapLibraryMetadataFieldValue(
        libraryId,
        remapField.fieldKey,
        remapCode,
        remapTarget === '' ? null : remapTarget,
      )
      setRemapField(null)
      setRemapCode(null)
      await reload()
      onFieldsChanged?.()
      if (!result.complete) void runPendingChanges()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Abbildung ist fehlgeschlagen')
    }
  }

  /**
   * The follow-up run of a confirmed mapping or field deletion: one batch per call until nothing
   * is open any more. Pausing means no longer requesting the next batch; the part already
   * rewritten stays. A batch without progress ends the run as well, otherwise
   * it would run on endlessly over documents nobody can rewrite right now.
   */
  async function runPendingChanges() {
    if (running) return
    paused.current = false
    setRunning(true)
    try {
      for (;;) {
        const result = await runLibraryMetadataSchemaChanges(libraryId)
        if (unmounted.current) return
        setPendingChanges(result.pendingChanges)
        if (result.complete || result.processedDocuments === 0 || paused.current) break
      }
    } catch (err) {
      if (unmounted.current) return
      setError(err instanceof Error ? err.message : 'Der Nachlauf ist fehlgeschlagen')
    } finally {
      if (!unmounted.current) {
        setRunning(false)
        await reload()
        onFieldsChanged?.()
      }
    }
  }

  async function saveValueLabel() {
    if (!remapField || !remapCode) return
    try {
      await relabelLibraryMetadataFieldValue(libraryId, remapField.fieldKey, remapCode, valueLabel)
      setRemapField(null)
      setRemapCode(null)
      await reload()
      onFieldsChanged?.()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Bezeichnung konnte nicht geändert werden')
    }
  }

  async function openDelete(field: LibraryMetadataFieldResponse) {
    setDeleteField(field)
    setDeleteUsage(null)
    try {
      const usage = await getLibraryMetadataFieldUsage(libraryId, field.fieldKey)
      setDeleteUsage(usage.documentCount)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Die Folgekosten konnten nicht ermittelt werden',
      )
    }
  }

  async function confirmDelete() {
    if (!deleteField) return
    try {
      const pending = await deleteLibraryMetadataField(libraryId, deleteField.fieldKey)
      setDeleteField(null)
      await reload()
      onFieldsChanged?.()
      if (pending) void runPendingChanges()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Das Feld konnte nicht gelöscht werden')
    }
  }

  return (
    <TabSection
      title="Metadatenfelder"
      action={
        canManageSchema ? (
          <Button size="small" onClick={() => setCreateOpen(true)}>
            Feld anlegen
          </Button>
        ) : undefined
      }
    >
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>
        Bis zu fünf eigene Felder; jedes wirkt im Filter oder im Kontextpräfix.
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {awaitingRerun > 0 && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {`${plural(awaitingRerun, 'Dokument wartet', 'Dokumente warten')} auf Neu-Einbetten.`} Der
          Nachlauf wird von einer Systemadministratorin auf der Seite{' '}
          {/* Directly into the tab that carries the follow-up run - the bare path would land on
             the overview, and from there one would have to click on once more. */}
          <Link component={RouterLink} to="/admin/search/index">
            Suche &amp; Indexierung
          </Link>{' '}
          gestartet; bis dahin bleibt die Suche über den alten Stand verfügbar.
        </Alert>
      )}
      {pendingChanges.length > 0 && (
        <Alert
          severity="info"
          sx={{ mb: 2 }}
          action={
            canManageSchema &&
            (running ? (
              <Button size="small" onClick={() => (paused.current = true)}>
                Anhalten
              </Button>
            ) : (
              <Button size="small" onClick={() => void runPendingChanges()}>
                Fortsetzen
              </Button>
            ))
          }
        >
          {pendingChanges.map((change) => (
            <Typography key={`${change.fieldKey}|${change.valueCode ?? ''}`} variant="body2">
              {describeChange(change, fields)}
            </Typography>
          ))}
          <Typography variant="body2" sx={{ mt: 1 }}>
            Bis der Lauf durch ist, bleibt der alte Wert in der Werteliste und die betroffenen
            Dokumente werden weiterhin gefunden. Neu gesetzt werden kann er nicht mehr.
          </Typography>
        </Alert>
      )}
      <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
        {coreContextPrefix && (
          <Box component="li" sx={{ ...FIELD_ROW, display: 'block' }}>
            <Typography component="h3" sx={{ fontSize: 13.5, fontWeight: 600 }}>
              Kernfelder im Kontextpräfix
            </Typography>
            <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
              <CoreContextPrefixSwitch
                libraryId={libraryId}
                label="Dokumentart"
                fieldKey="document_type"
                checked={coreContextPrefix.documentType}
                disabled={!canManageSchema}
                onSave={(next) => void saveCoreContextPrefix(next, coreContextPrefix.documentDate)}
              />
              <CoreContextPrefixSwitch
                libraryId={libraryId}
                label="Datum/Stand"
                fieldKey="document_date"
                checked={coreContextPrefix.documentDate}
                disabled={!canManageSchema}
                onSave={(next) => void saveCoreContextPrefix(coreContextPrefix.documentType, next)}
              />
            </Stack>
          </Box>
        )}
        {fields.length === 0 ? (
          <Typography component="li" variant="body2" sx={{ color: 'text.secondary', py: 1.5 }}>
            Keine eigenen Metadatenfelder.
          </Typography>
        ) : (
          fields.map((field) => (
            <MetadataFieldRow
              key={field.fieldKey}
              field={field}
              libraryId={libraryId}
              canManageSchema={canManageSchema}
              onEdit={() => setEditField(field)}
              onDelete={() => void openDelete(field)}
              onRemap={(code) => void openRemap(field, code)}
              onValueAdded={() => void reload()}
              onError={setError}
            />
          ))
        )}
      </Box>

      <CreateFieldDialog
        open={createOpen}
        libraryId={libraryId}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setCreateOpen(false)
          void reload()
          onFieldsChanged?.()
        }}
        onError={setError}
      />

      <RemapValueDialog
        libraryId={libraryId}
        remapField={remapField}
        remapCode={remapCode}
        remapUsage={remapUsage}
        remapTarget={remapTarget}
        valueLabel={valueLabel}
        onValueLabelChange={setValueLabel}
        onRemapTargetChange={setRemapTarget}
        onSaveValueLabel={() => void saveValueLabel()}
        onConfirm={() => void confirmRemap()}
        onClose={() => setRemapField(null)}
      />

      {editField && (
        <EditFieldDialog
          key={editField.fieldKey}
          libraryId={libraryId}
          field={editField}
          onClose={() => setEditField(null)}
          onSaved={() => {
            setEditField(null)
            void reload()
            onFieldsChanged?.()
          }}
          onError={setError}
        />
      )}

      <DeleteFieldDialog
        libraryId={libraryId}
        deleteField={deleteField}
        deleteUsage={deleteUsage}
        onConfirm={() => void confirmDelete()}
        onClose={() => setDeleteField(null)}
      />
    </TabSection>
  )
}

/** One entry of the field list: a hairline below, title and actions on its first line. */
const FIELD_ROW = {
  py: 1.5,
  borderBottom: 1,
  borderColor: 'divider',
} as const

/** From this many values on, a value list starts collapsed with a search (D19). */
const VALUES_COLLAPSED_FROM = 8

/**
 * A field in two lines - name with its Wirkstellen, then type and value count - and its value
 * list below: short lists stand open, long ones open on request and can be searched.
 */
function MetadataFieldRow({
  field,
  libraryId,
  canManageSchema,
  onEdit,
  onDelete,
  onRemap,
  onValueAdded,
  onError,
}: {
  field: LibraryMetadataFieldResponse
  libraryId: string
  canManageSchema: boolean
  onEdit: () => void
  onDelete: () => void
  onRemap: (code: string) => void
  onValueAdded: () => void
  onError: (message: string) => void
}) {
  const long = field.values.length >= VALUES_COLLAPSED_FROM
  const [open, setOpen] = useState(!long)
  const [query, setQuery] = useState('')
  const needle = query.trim().toLocaleLowerCase('de-DE')
  const values = field.values.filter(
    (value) =>
      !needle ||
      value.label.toLocaleLowerCase('de-DE').includes(needle) ||
      value.code.toLocaleLowerCase('de-DE').includes(needle),
  )
  const editable = canManageSchema && !field.deletionPending
  const valuesId = `metadata-values-${field.fieldKey}`
  const effects = effectChips(field)
  return (
    <Box component="li" sx={FIELD_ROW}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography component="h3" sx={{ fontSize: 13.5, fontWeight: 600 }}>
            {field.label}
            {effects.length > 0 && (
              <Box component="span" sx={{ fontWeight: 400, color: 'text.secondary' }}>
                {` · ${effects.join(' · ')}`}
              </Box>
            )}
          </Typography>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {TYPE_LABELS[field.type]}
            {field.valuePattern ? ` · Muster: ${field.valuePattern}` : ''}
            {field.type === 'SELECT' ? ` · ${plural(field.values.length, 'Wert', 'Werte')}` : ''}
          </Typography>
          {field.deletionPending && (
            <Typography variant="caption" sx={{ color: 'warning.main', ml: 1 }}>
              wird gelöscht
            </Typography>
          )}
        </Box>
        {field.type === 'SELECT' && long && (
          <Button
            size="small"
            aria-expanded={open}
            aria-controls={valuesId}
            onClick={() => setOpen((current) => !current)}
          >
            {open ? 'Werte ausblenden' : 'Werte anzeigen'}
          </Button>
        )}
        {editable && (
          <Button size="small" onClick={onEdit} aria-label={`Feld ${field.label} bearbeiten`}>
            Bearbeiten
          </Button>
        )}
        {editable && (
          <Button
            size="small"
            color="error"
            onClick={onDelete}
            aria-label={`Feld ${field.label} löschen`}
          >
            Löschen
          </Button>
        )}
      </Box>
      {field.type === 'SELECT' && open && (
        <Box id={valuesId} sx={{ mt: 1 }}>
          {long && (
            <TextField
              size="small"
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Wert suchen"
              slotProps={{ htmlInput: { 'aria-label': `Werte von ${field.label} durchsuchen` } }}
              sx={{ mb: 1, width: '100%', maxWidth: 280 }}
            />
          )}
          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
            {values.map((value) => (
              <Chip
                key={value.code}
                size="small"
                variant="outlined"
                label={
                  value.retiring
                    ? `${value.label} (${value.code}) — wird abgebildet`
                    : `${value.label} (${value.code})`
                }
                clickable={editable && !value.retiring}
                onClick={editable && !value.retiring ? () => onRemap(value.code) : undefined}
                aria-label={canManageSchema ? `Wert ${value.label} bearbeiten` : undefined}
              />
            ))}
            {editable && (
              <AddValueButton
                libraryId={libraryId}
                fieldKey={field.fieldKey}
                onAdded={onValueAdded}
                onError={onError}
              />
            )}
          </Stack>
        </Box>
      )}
    </Box>
  )
}
