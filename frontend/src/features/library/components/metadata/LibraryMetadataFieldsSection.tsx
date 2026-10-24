import { useCallback, useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
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
    <Box>
      <Stack direction="row" sx={{ mb: 1, alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="subtitle1">Metadatenfelder</Typography>
        {canManageSchema && (
          <Button size="small" onClick={() => setCreateOpen(true)}>
            Feld anlegen
          </Button>
        )}
      </Stack>
      <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
        Eigene Felder dieser Bibliothek, zusätzlich zu Titel, Dokumentart und Datum/Stand. Jedes
        Feld muss im Filter oder im Kontextpräfix wirken; höchstens fünf Felder je Bibliothek und
        höchstens zwei davon in der Belegzeile.
      </Typography>
      <Alert severity="info" sx={{ mb: 2 }}>
        Wertelisten sind für jede Person sichtbar, die die Bibliothek benutzen darf. Sie dürfen
        deshalb keine schutzbedürftigen Bezeichnungen tragen.
      </Alert>
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
      {coreContextPrefix && (
        <Box sx={{ mb: 2 }}>
          <Typography variant="subtitle2">Kernfelder im Kontextpräfix</Typography>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Der Titel steht immer im Kontextpräfix. Dokumentart und Datum/Stand sind je Bibliothek
            eine bewusste Entscheidung — eingeschaltet kostet jede spätere Änderung daran ein
            Neu-Einbetten.
          </Typography>
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
        </Box>
      )}
      {fields.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Diese Bibliothek führt keine eigenen Metadatenfelder — der Normalfall.
        </Typography>
      ) : (
        <Stack spacing={2} divider={<Divider flexItem />}>
          {fields.map((field) => (
            <Box key={field.fieldKey}>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Typography variant="subtitle2">{field.label}</Typography>
                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                  {field.fieldKey} · {TYPE_LABELS[field.type]}
                </Typography>
                {effectChips(field).map((chip) => (
                  <Chip key={chip} size="small" label={chip} />
                ))}
                {field.deletionPending && (
                  <Chip size="small" color="warning" label="wird gelöscht" />
                )}
                {canManageSchema && !field.deletionPending && (
                  <Button
                    size="small"
                    onClick={() => setEditField(field)}
                    aria-label={`Feld ${field.label} bearbeiten`}
                  >
                    Bearbeiten
                  </Button>
                )}
                {canManageSchema && !field.deletionPending && (
                  <Button
                    size="small"
                    color="error"
                    onClick={() => void openDelete(field)}
                    aria-label={`Feld ${field.label} löschen`}
                  >
                    Löschen
                  </Button>
                )}
              </Stack>
              {field.valuePattern && (
                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                  Muster: {field.valuePattern}
                </Typography>
              )}
              {field.type === 'SELECT' && (
                <Stack direction="row" spacing={1} sx={{ mt: 1, flexWrap: 'wrap' }}>
                  {field.values.map((value) => (
                    <Chip
                      key={value.code}
                      size="small"
                      variant="outlined"
                      color={value.retiring ? 'warning' : 'default'}
                      label={
                        value.retiring
                          ? `${value.label} (${value.code}) — wird abgebildet`
                          : `${value.label} (${value.code})`
                      }
                      clickable={canManageSchema && !value.retiring && !field.deletionPending}
                      onClick={
                        canManageSchema && !value.retiring && !field.deletionPending
                          ? () => void openRemap(field, value.code)
                          : undefined
                      }
                      aria-label={canManageSchema ? `Wert ${value.label} bearbeiten` : undefined}
                    />
                  ))}
                  {canManageSchema && !field.deletionPending && (
                    <AddValueButton
                      libraryId={libraryId}
                      fieldKey={field.fieldKey}
                      onAdded={() => void reload()}
                      onError={setError}
                    />
                  )}
                </Stack>
              )}
            </Box>
          ))}
        </Stack>
      )}

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
    </Box>
  )
}
