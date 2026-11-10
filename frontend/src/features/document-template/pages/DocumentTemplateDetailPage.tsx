import { useCallback, useEffect, useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import DownloadOutlinedIcon from '@mui/icons-material/DownloadOutlined'
import type { AssetRole, DocumentTemplateResponse } from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { assetRoleLabel, formatFileSize } from '@/shared/utils/labels'
import {
  CATALOG_ROUTE,
  DOCUMENT_TEMPLATE_TABS,
  documentTemplateRoute,
  type DocumentTemplateTab,
} from '@/shared/routes'
import AreaTabs from '@/shared/ui/AreaTabs'
import { useAuthStore } from '@/shared/session/authStore'
import MetaBadge from '@/shared/ui/MetaBadge'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { SuccessionStateNote, successionAwareMessage } from '@/features/succession'
import {
  AssetAccessDerivationSection,
  AssetDetailHeader,
  AssetGrantsSection,
  AssetOwnerSection,
  AssetSpacesSection,
  responsibleParty,
  useAssetCatalogEntry,
} from '@/features/asset'
import {
  deleteDocumentTemplate,
  downloadDocumentTemplateFile,
  getDocumentTemplate,
  replaceDocumentTemplateFile,
  updateDocumentTemplate,
} from '../api/documentTemplateApi'
import PlaceholderList from '../components/PlaceholderList'
import TemplateFilePicker from '../components/TemplateFilePicker'
import { contentWidth, fontSize, readingWidth } from '@/shared/theme/tokens'

const ROLE_ORDER: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER']

function holds(role: AssetRole | undefined, minimum: AssetRole): boolean {
  return role !== undefined && ROLE_ORDER.indexOf(role) >= ROLE_ORDER.indexOf(minimum)
}

const tabs: Array<{ value: DocumentTemplateTab; label: string }> = [
  { value: 'platzhalter', label: 'Platzhalter' },
  { value: 'freigaben', label: 'Freigaben' },
  { value: 'zuordnungen', label: 'Zuordnungen' },
]

function isDocumentTemplateTab(value: string | undefined): value is DocumentTemplateTab {
  return DOCUMENT_TEMPLATE_TABS.some((tab) => tab === value)
}

function placeholderExtent(count: number): string {
  return count === 1 ? '1 Platzhalter' : `${count} Platzhalter`
}

function saveAs(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

/** The file and its placeholders; editors replace the file here. */
function FileArea({
  template,
  canReplace,
  onReplaced,
}: {
  template: DocumentTemplateResponse
  canReplace: boolean
  onReplaced: (template: DocumentTemplateResponse) => void
}) {
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState<'download' | 'replace' | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function handleDownload() {
    setBusy('download')
    setError(null)
    try {
      saveAs(await downloadDocumentTemplateFile(template.id), template.fileName)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Herunterladen fehlgeschlagen')
    } finally {
      setBusy(null)
    }
  }

  async function handleReplace() {
    if (!file) return
    setBusy('replace')
    setError(null)
    try {
      onReplaced(await replaceDocumentTemplateFile(template.id, file))
      setFile(null)
    } catch (err) {
      setError(successionAwareMessage(err, 'Die Datei konnte nicht ersetzt werden'))
    } finally {
      setBusy(null)
    }
  }

  return (
    <Stack spacing={3}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 2,
          flexWrap: 'wrap',
        }}
      >
        <Typography
          sx={{ fontSize: fontSize.meta, color: 'text.secondary', maxWidth: readingWidth }}
        >
          {template.fileName} · {formatFileSize(template.sizeBytes)}. Ein Agent füllt diese
          Platzhalter mit den Werten aus dem Chat; die Datei selbst bleibt unverändert.
        </Typography>
        <BusyButton
          variant="outlined"
          size="small"
          startIcon={<DownloadOutlinedIcon />}
          busy={busy === 'download'}
          busyAnnouncement="Datei wird heruntergeladen"
          onClick={() => void handleDownload()}
        >
          Datei herunterladen
        </BusyButton>
      </Box>
      {error && (
        <Alert severity="error" onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <PlaceholderList placeholders={template.placeholders} />
      {canReplace && (
        <Box>
          <Typography component="h2" sx={{ fontSize: fontSize.title, fontWeight: 600, mb: 1 }}>
            Datei ersetzen
          </Typography>
          <TemplateFilePicker
            file={file}
            onFileChange={setFile}
            label="Neue Word-Datei auswählen"
            disabled={busy !== null}
          />
          {file && (
            <BusyButton
              variant="contained"
              size="small"
              sx={{ mt: 1.5 }}
              busy={busy === 'replace'}
              busyAnnouncement="Datei wird ersetzt"
              onClick={() => void handleReplace()}
            >
              Datei ersetzen
            </BusyButton>
          )}
        </Box>
      )}
    </Stack>
  )
}

/**
 * One document template, built like the prompt library's page: the shared head, then the areas
 * „Platzhalter", „Freigaben" and „Zuordnungen", each a route of its own and open to every reader.
 */
export default function DocumentTemplateDetailPage() {
  const { documentTemplateId, tab } = useParams()
  const navigate = useNavigate()
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const catalog = useAssetCatalogEntry('DOCUMENT_TEMPLATE', documentTemplateId ?? '')
  const [template, setTemplate] = useState<DocumentTemplateResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [associationsVersion, setAssociationsVersion] = useState(0)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const load = useCallback(
    (id: string) =>
      getDocumentTemplate(id)
        .then((loaded) => {
          setTemplate(loaded)
          setLoadError(null)
        })
        .catch((err: unknown) =>
          setLoadError(
            err instanceof Error ? err.message : 'Die Vorlage konnte nicht geladen werden',
          ),
        ),
    [],
  )

  useEffect(() => {
    if (documentTemplateId) void load(documentTemplateId)
  }, [documentTemplateId, load])

  if (!documentTemplateId) return <Navigate to={CATALOG_ROUTE} replace />
  if (tab !== undefined && (!isDocumentTemplateTab(tab) || tab === DOCUMENT_TEMPLATE_TABS[0])) {
    return <Navigate to={documentTemplateRoute(documentTemplateId)} replace />
  }

  if (!template || template.id !== documentTemplateId) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
        {loadError ? (
          <Alert severity="error">{loadError}</Alert>
        ) : (
          <Typography sx={{ color: 'text.secondary' }}>Vorlage wird geladen …</Typography>
        )}
      </Box>
    )
  }

  const activeTab: DocumentTemplateTab = tab ?? DOCUMENT_TEMPLATE_TABS[0]
  const canManage = holds(template.myRole, 'MANAGER')
  const canReplace = holds(template.myRole, 'EDITOR')
  const canDelete = template.myRole === 'OWNER'
  // The catalog holds the role of the formula; a shown role above it comes from the bypass of
  // the system administration, which may not use the template in a space of its own.
  const administrative =
    isSystemAdmin &&
    catalog.entry !== undefined &&
    (catalog.entry === null || catalog.entry.myRole !== template.myRole)
  const templateId = template.id

  async function saveHeadline(name: string, description: string) {
    try {
      setTemplate(
        await updateDocumentTemplate(templateId, {
          name: name.trim(),
          description: description.trim() || null,
        }),
      )
      catalog.reload()
    } catch (err) {
      throw new Error(successionAwareMessage(err, 'Speichern fehlgeschlagen'), { cause: err })
    }
  }

  async function handleDelete() {
    const confirmed = await confirmAction({
      question: `„${template?.name}“ löschen?`,
      consequence:
        'Die Datei, die Rechte und die Space-Zuordnungen dieser Vorlage werden gelöscht.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    setDeleteError(null)
    try {
      await deleteDocumentTemplate(templateId)
      navigate(CATALOG_ROUTE)
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    }
  }

  function associationsChanged() {
    catalog.reload()
    setAssociationsVersion((version) => version + 1)
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.detail }}>
        <AssetDetailHeader
          assetType="DOCUMENT_TEMPLATE"
          assetId={templateId}
          name={template.name}
          description={template.description}
          isPublic={template.reach.allAccounts}
          badges={<MetaBadge>{assetRoleLabel(template.myRole)}</MetaBadge>}
          administrative={administrative}
          headline={{
            idPrefix: 'document-template-detail',
            nameLabel: 'Name der Vorlage',
            editLabel: 'Name und Beschreibung ändern',
            canEdit: canManage,
            onSave: saveHeadline,
          }}
          extent={placeholderExtent(template.placeholders.length)}
          spaceCount={catalog.entry?.spaceCount}
          responsible={responsibleParty({
            ownerType: template.ownerType,
            ownerLabel: template.ownerName,
            succession: template.succession,
          })}
          updatedAt={template.updatedAt}
          favorite={catalog.entry?.favorite}
          onFavoriteChange={catalog.setFavorite}
          onDelete={canDelete ? () => void handleDelete() : undefined}
          note={<SuccessionStateNote succession={template.succession} />}
        />

        {deleteError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setDeleteError(null)}>
            {deleteError}
          </Alert>
        )}

        <AreaTabs
          tabs={tabs}
          value={activeTab}
          href={(value) => documentTemplateRoute(templateId, value)}
          label="Bereiche der Vorlage"
          idPrefix="document-template"
        >
          {(value) => {
            if (value === 'freigaben') {
              return (
                <Stack>
                  <AssetOwnerSection
                    assetType="DOCUMENT_TEMPLATE"
                    assetId={templateId}
                    ownerType={template.ownerType}
                    ownerName={template.ownerName}
                    canTransfer={template.myRole === 'OWNER'}
                    onTransferred={() => load(templateId)}
                  />
                  {canManage && (
                    <AssetGrantsSection assetType="DOCUMENT_TEMPLATE" assetId={templateId} />
                  )}
                  <AssetAccessDerivationSection
                    assetType="DOCUMENT_TEMPLATE"
                    assetId={templateId}
                  />
                </Stack>
              )
            }
            if (value === 'zuordnungen') {
              return (
                <AssetSpacesSection
                  assetType="DOCUMENT_TEMPLATE"
                  assetId={templateId}
                  name={template.name}
                  canManage={canManage}
                  mayUseInSpace={!administrative}
                  reachFrozen={Boolean(template.succession)}
                  refreshToken={associationsVersion}
                  onChanged={associationsChanged}
                />
              )
            }
            return (
              <FileArea
                key={template.id}
                template={template}
                canReplace={canReplace}
                onReplaced={(next) => {
                  setTemplate(next)
                  catalog.reload()
                }}
              />
            )
          }}
        </AreaTabs>
      </Box>
    </Box>
  )
}
