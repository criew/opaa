import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import ListSearch from '@/shared/ui/ListSearch'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
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
import { describeChange, effectChips } from './metadataFieldText'
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
  const [valuesOpen, setValuesOpen] = useState<Record<string, boolean>>({})

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
    <DetailSection
      title="Felder"
      action={
        canManageSchema ? (
          <TextAction onClick={() => setCreateOpen(true)}>Feld anlegen</TextAction>
        ) : undefined
      }
    >
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {awaitingRerun > 0 && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {`${plural(awaitingRerun, 'Dokument wartet', 'Dokumente warten')} auf Neu-Einbetten.`} Der
          Nachlauf wird von einer Systemadministratorin auf der Seite{' '}
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
      <Box
        component="ul"
        sx={{ m: 0, p: 0, listStyle: 'none', borderTop: 1, borderColor: 'divider' }}
      >
        {coreContextPrefix && (
          <FieldRow
            label="Kernfelder"
            detail={
              // a reader reads the choice; whoever manages the schema switches it
              canManageSchema
                ? 'Titel, Dokumentart, Datum/Stand'
                : `Titel, Dokumentart, Datum/Stand · im Kontextpräfix: ${[
                    'Titel',
                    coreContextPrefix.documentType && 'Dokumentart',
                    coreContextPrefix.documentDate && 'Datum/Stand',
                  ]
                    .filter(Boolean)
                    .join(', ')}`
            }
          >
            {canManageSchema && (
              <Box
                sx={{
                  display: 'flex',
                  flexDirection: { xs: 'column', sm: 'row' },
                  alignItems: { xs: 'flex-start', sm: 'center' },
                  columnGap: 2,
                }}
              >
                <Typography component="span" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                  im Kontextpräfix
                </Typography>
                <CoreContextPrefixSwitch
                  libraryId={libraryId}
                  label="Dokumentart"
                  fieldKey="document_type"
                  checked={coreContextPrefix.documentType}
                  disabled={false}
                  onSave={(next) =>
                    void saveCoreContextPrefix(next, coreContextPrefix.documentDate)
                  }
                />
                <CoreContextPrefixSwitch
                  libraryId={libraryId}
                  label="Datum/Stand"
                  fieldKey="document_date"
                  checked={coreContextPrefix.documentDate}
                  disabled={false}
                  onSave={(next) =>
                    void saveCoreContextPrefix(coreContextPrefix.documentType, next)
                  }
                />
              </Box>
            )}
          </FieldRow>
        )}
        {fields.map((field) => (
          <FieldRow
            key={field.fieldKey}
            label={field.label}
            detail={
              <>
                {SHORT_TYPE[field.type]}
                {field.type === 'SELECT' && (
                  <>
                    {' · '}
                    <TextAction
                      onClick={() =>
                        setValuesOpen((prev) => ({
                          ...prev,
                          [field.fieldKey]: !prev[field.fieldKey],
                        }))
                      }
                      aria-expanded={Boolean(valuesOpen[field.fieldKey])}
                      context={`von ${field.label}`}
                    >
                      {`${field.values.length} Werte`}
                    </TextAction>
                  </>
                )}
                {effectChips(field).length > 0 && ` · ${effectChips(field).join(', ')}`}
                {field.deletionPending && ' · wird gelöscht'}
              </>
            }
            actions={
              canManageSchema && !field.deletionPending ? (
                <TextAction onClick={() => setEditField(field)} context={`Feld ${field.label}`}>
                  Ändern
                </TextAction>
              ) : undefined
            }
          >
            {field.type === 'SELECT' && valuesOpen[field.fieldKey] && (
              <ValueList
                field={field}
                editable={canManageSchema && !field.deletionPending}
                onOpen={(code) => void openRemap(field, code)}
                addButton={
                  <AddValueButton
                    libraryId={libraryId}
                    fieldKey={field.fieldKey}
                    onAdded={() => void reload()}
                    onError={setError}
                  />
                }
              />
            )}
          </FieldRow>
        ))}
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
          onDelete={() => {
            const target = editField
            setEditField(null)
            void openDelete(target)
          }}
        />
      )}

      <DeleteFieldDialog
        libraryId={libraryId}
        deleteField={deleteField}
        deleteUsage={deleteUsage}
        onConfirm={() => void confirmDelete()}
        onClose={() => setDeleteField(null)}
      />
    </DetailSection>
  )
}

/** One field as a list row: name, what it is and does, its actions; its values below. */
function FieldRow({
  label,
  detail,
  actions,
  children,
}: {
  label: string
  detail: ReactNode
  actions?: ReactNode
  children?: ReactNode
}) {
  return (
    <Box component="li" sx={{ py: 1.25, borderBottom: 1, borderColor: 'divider' }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2 }}>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{label}</Typography>
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>{detail}</Typography>
        </Box>
        {actions && (
          <Stack direction="row" spacing={2} sx={{ flex: 'none' }}>
            {actions}
          </Stack>
        )}
      </Box>
      {children && (
        <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', mt: 0.5 }}>
          {children}
        </Box>
      )}
    </Box>
  )
}

/** The type of a field in two or three words; the pattern itself stands in „Ändern“. */
const SHORT_TYPE: Record<LibraryMetadataFieldResponse['type'], string> = {
  SELECT: 'Auswahl',
  DATE: 'Jahr oder Datum',
  PATTERN: 'Kennung nach Muster',
}

/** From eight values on the list gets a search (D19). */
const VALUES_SHOWN = 8

/** The value list of a selection field: a few as text, all with a search on request. */
function ValueList({
  field,
  editable,
  onOpen,
  addButton,
}: {
  field: LibraryMetadataFieldResponse
  editable: boolean
  onOpen: (code: string) => void
  addButton: ReactNode
}) {
  const [query, setQuery] = useState('')
  const values = field.values
  const needle = query.trim().toLowerCase()
  const matching = needle
    ? values.filter((value) => value.label.toLowerCase().includes(needle))
    : values
  return (
    <Box sx={{ width: '100%', mt: 0.5 }}>
      {values.length >= VALUES_SHOWN && (
        <Box sx={{ mb: 1, width: 280, maxWidth: '100%' }}>
          <ListSearch
            value={query}
            onChange={setQuery}
            placeholder="Wert enthält …"
            label={`Werte von ${field.label} durchsuchen`}
          />
        </Box>
      )}
      <Stack direction="row" useFlexGap spacing={0.75} sx={{ flexWrap: 'wrap' }}>
        {matching.map((value) => (
          <Chip
            key={value.code}
            size="small"
            variant="outlined"
            label={value.retiring ? `${value.label} · wird abgebildet` : value.label}
            clickable={editable && !value.retiring}
            onClick={editable && !value.retiring ? () => onOpen(value.code) : undefined}
            aria-label={editable ? `Wert ${value.label} bearbeiten` : undefined}
          />
        ))}
        {editable && addButton}
      </Stack>
      {editable && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.75 }}>
          Wertelisten sieht jede Person, die die Bibliothek nutzt.
        </Typography>
      )}
    </Box>
  )
}
