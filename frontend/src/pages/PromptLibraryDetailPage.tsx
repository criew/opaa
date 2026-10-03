import { useEffect, useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { AssetRole, PromptLibraryResponse, PromptResponse } from '../types/api'
import { usePromptLibraryStore } from '../stores/promptLibraryStore'
import { confirmAction } from '../stores/confirmStore'
import { assetRoleLabel, promptVariableTypeLabel } from '../utils/labels'
import { fontFamily } from '../theme/tokens'
import {
  CATALOG_ROUTE,
  FORMER_PROMPT_LIBRARY_TABS,
  PROMPT_LIBRARY_TABS,
  promptLibraryRoute,
  type PromptLibraryTab,
} from '../routes'
import AreaTabs from '../components/AreaTabs'
import { useAuthStore } from '../stores/authStore'
import MetaBadge from '../components/MetaBadge'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'
import { successionAwareMessage } from '../components/succession/successionConflict'
import AssetAccessDerivationSection from '../components/assets/AssetAccessDerivationSection'
import AssetDetailHeader from '../components/assets/AssetDetailHeader'
import AssetOwnerSection from '../components/assets/AssetOwnerSection'
import AssetSpacesSection from '../components/assets/AssetSpacesSection'
import { responsibleParty } from '../components/assets/assetTileData'
import { useAssetCatalogEntry } from '../components/assets/useAssetCatalogEntry'
import AssetGrantsSection from '../components/permissions/AssetGrantsSection'
import PromptEditorDialog from '../components/prompts/PromptEditorDialog'
import PromptTextHighlight from '../components/prompts/PromptTextHighlight'

const ROLE_ORDER: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER']

function holds(role: AssetRole | undefined, minimum: AssetRole): boolean {
  return role !== undefined && ROLE_ORDER.indexOf(role) >= ROLE_ORDER.indexOf(minimum)
}

const tabs: Array<{ value: PromptLibraryTab; label: string }> = [
  { value: 'prompts', label: 'Prompts' },
  { value: 'freigaben', label: 'Freigaben' },
  { value: 'zuordnungen', label: 'Zuordnungen' },
]

function isPromptLibraryTab(value: string | undefined): value is PromptLibraryTab {
  return PROMPT_LIBRARY_TABS.some((tab) => tab === value)
}

function variablesSummary(prompt: PromptResponse): string {
  const count = prompt.variables.length
  if (count === 0) return 'ohne Variablen'
  const required = prompt.variables.filter((variable) => variable.required).length
  const base = count === 1 ? '1 Variable' : `${count} Variablen`
  return required > 0 ? `${base}, davon ${required} Pflicht` : base
}

/** The prompts of one library; editors add, change and delete them here. */
function PromptsArea({
  library,
  canEditPrompts,
}: {
  library: PromptLibraryResponse
  canEditPrompts: boolean
}) {
  const prompts = usePromptLibraryStore((s) => s.promptsByLibrary[library.id])
  const promptsError = usePromptLibraryStore((s) => s.promptsErrorByLibrary[library.id])
  const loadPrompts = usePromptLibraryStore((s) => s.loadPrompts)
  const removePrompt = usePromptLibraryStore((s) => s.removePrompt)
  // `undefined` = closed, `null` = a new prompt, otherwise the prompt being changed.
  const [editing, setEditing] = useState<PromptResponse | null | undefined>(undefined)
  const [actionError, setActionError] = useState<string | null>(null)

  useEffect(() => {
    void loadPrompts(library.id)
  }, [library.id, loadPrompts])

  async function handleDelete(prompt: PromptResponse) {
    const confirmed = await confirmAction({
      question: `Prompt „${prompt.title}“ löschen?`,
      consequence: `Der Befehl /${prompt.name} steht danach nicht mehr zur Verfügung.`,
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    setActionError(null)
    try {
      await removePrompt(library.id, prompt.id)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    }
  }

  const mayCreate = canEditPrompts && !promptsError

  // No heading of its own: the tab „Prompts" already names the area.
  return (
    <Box>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 2,
          flexWrap: 'wrap',
          mb: 2,
        }}
      >
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', maxWidth: '80ch' }}>
          Benannte Anweisungen dieser Prompt-Bibliothek. Der Befehl ist der Name, unter dem ein
          Prompt im Chat aufgerufen wird.
        </Typography>
        {mayCreate && (
          <Button variant="contained" size="small" onClick={() => setEditing(null)}>
            Neuer Prompt
          </Button>
        )}
      </Box>
      {actionError && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setActionError(null)}>
          {actionError}
        </Alert>
      )}
      {promptsError ? (
        <Alert severity="warning">{promptsError}</Alert>
      ) : prompts === undefined ? (
        <Typography sx={{ color: 'text.secondary' }}>Prompts werden geladen …</Typography>
      ) : prompts.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          {canEditPrompts
            ? 'Diese Prompt-Bibliothek enthält noch keine Prompts. Legen Sie den ersten mit „Neuer Prompt“ an.'
            : 'Diese Prompt-Bibliothek enthält noch keine Prompts.'}
        </Typography>
      ) : (
        <Box>
          {prompts.map((prompt) => (
            // The summary is the prompt's heading (MUI's heading slot); the title inside is a span.
            <Accordion key={prompt.id} slotProps={{ heading: { component: 'h2' } }}>
              <AccordionSummary
                expandIcon={<ExpandMoreIcon />}
                aria-controls={`prompt-${prompt.id}-content`}
                id={`prompt-${prompt.id}-header`}
              >
                <Box sx={{ minWidth: 0 }}>
                  <Stack
                    direction="row"
                    spacing={1.5}
                    sx={{ alignItems: 'baseline', flexWrap: 'wrap' }}
                  >
                    <Typography component="span" sx={{ fontSize: 14.5, fontWeight: 600 }}>
                      {prompt.title}
                    </Typography>
                    <Typography
                      component="span"
                      sx={{ fontFamily: fontFamily.mono, fontSize: 12, color: 'primary.main' }}
                    >
                      /{prompt.name}
                    </Typography>
                  </Stack>
                  <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    {[prompt.description, variablesSummary(prompt)].filter(Boolean).join(' · ')}
                  </Typography>
                </Box>
              </AccordionSummary>
              <AccordionDetails id={`prompt-${prompt.id}-content`}>
                <Stack spacing={1.5}>
                  <PromptTextHighlight text={prompt.text} label={`Text von ${prompt.title}`} />
                  {prompt.variables.length > 0 && (
                    <Box component="ul" sx={{ m: 0, pl: 2.5, fontSize: 12.5 }}>
                      {prompt.variables.map((variable) => (
                        <li key={variable.name}>
                          <Box component="span" sx={{ fontFamily: fontFamily.mono }}>
                            {`{{${variable.name}}}`}
                          </Box>{' '}
                          — {variable.label} · {promptVariableTypeLabel(variable.type)}
                          {variable.required ? ' · Pflicht' : ''}
                          {variable.defaultValue ? ` · Vorbelegung „${variable.defaultValue}“` : ''}
                        </li>
                      ))}
                    </Box>
                  )}
                  {canEditPrompts && (
                    <Stack direction="row" spacing={1}>
                      <Button
                        size="small"
                        variant="outlined"
                        onClick={() => setEditing(prompt)}
                        aria-label={`Prompt ${prompt.title} bearbeiten`}
                      >
                        Bearbeiten
                      </Button>
                      <Button
                        size="small"
                        color="error"
                        onClick={() => void handleDelete(prompt)}
                        aria-label={`Prompt ${prompt.title} löschen`}
                      >
                        Löschen
                      </Button>
                    </Stack>
                  )}
                </Stack>
              </AccordionDetails>
            </Accordion>
          ))}
        </Box>
      )}
      {editing !== undefined && (
        <PromptEditorDialog
          // A fresh dialog per prompt: its draft state starts from the prompt it edits.
          key={editing?.id ?? 'new'}
          open
          promptLibraryId={library.id}
          prompt={editing}
          onClose={() => setEditing(undefined)}
        />
      )}
    </Box>
  )
}

/**
 * One prompt library, built like the knowledge library's page: the shared head with star
 * and „⋯", then the areas „Prompts", „Freigaben" and „Zuordnungen" - each a route of its own
 * (`/prompts/:id`, `/prompts/:id/freigaben`, `/prompts/:id/zuordnungen`) and open to every
 * reader, who finds them read-only.
 */
export default function PromptLibraryDetailPage() {
  const { promptLibraryId, tab } = useParams()
  const navigate = useNavigate()
  const library = usePromptLibraryStore((s) =>
    promptLibraryId ? s.details[promptLibraryId] : undefined,
  )
  const storeError = usePromptLibraryStore((s) => s.error)
  const loadLibrary = usePromptLibraryStore((s) => s.loadLibrary)
  const loadLibraries = usePromptLibraryStore((s) => s.loadLibraries)
  const updateLibrary = usePromptLibraryStore((s) => s.updateLibrary)
  const deleteLibrary = usePromptLibraryStore((s) => s.deleteLibrary)
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  // The list holds only what the caller may read, without the administrative bypass that makes
  // a system administrator OWNER of every single library.
  const listed = usePromptLibraryStore((s) => s.libraries.some((l) => l.id === promptLibraryId))
  const mayUseInSpace = !isSystemAdmin || listed
  const catalog = useAssetCatalogEntry('PROMPT_LIBRARY', promptLibraryId ?? '')
  const [associationsVersion, setAssociationsVersion] = useState(0)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  useEffect(() => {
    if (promptLibraryId) void loadLibrary(promptLibraryId)
  }, [promptLibraryId, loadLibrary])

  useEffect(() => {
    if (isSystemAdmin && !listed) void loadLibraries()
  }, [isSystemAdmin, listed, loadLibraries])

  if (!promptLibraryId) return <Navigate to={CATALOG_ROUTE} replace />

  const formerTab = tab !== undefined ? FORMER_PROMPT_LIBRARY_TABS[tab] : undefined
  if (formerTab) return <Navigate to={promptLibraryRoute(promptLibraryId, formerTab)} replace />
  if (tab !== undefined && (!isPromptLibraryTab(tab) || tab === PROMPT_LIBRARY_TABS[0])) {
    return <Navigate to={promptLibraryRoute(promptLibraryId)} replace />
  }

  if (!library) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
        {storeError ? (
          <Alert severity="error">{storeError}</Alert>
        ) : (
          <Typography sx={{ color: 'text.secondary' }}>Prompt-Bibliothek wird geladen …</Typography>
        )}
      </Box>
    )
  }

  const activeTab: PromptLibraryTab = tab ?? PROMPT_LIBRARY_TABS[0]
  const canManage = holds(library.myRole, 'MANAGER')
  const canEditPrompts = holds(library.myRole, 'EDITOR')
  const canDelete = library.myRole === 'OWNER'
  // The catalog holds the role of the formula, never raised for the system administration; a
  // shown role above it comes from the bypass.
  const administrative =
    isSystemAdmin &&
    catalog.entry !== undefined &&
    (catalog.entry === null || catalog.entry.myRole !== library.myRole)
  const libraryId = library.id

  /** The PUT replaces name and description as a whole; a rejection stays in the head. */
  async function saveHeadline(name: string, description: string) {
    try {
      await updateLibrary(libraryId, { name: name.trim(), description: description.trim() || null })
    } catch (err) {
      throw new Error(successionAwareMessage(err, 'Speichern fehlgeschlagen'), { cause: err })
    }
  }

  async function handleDelete() {
    const confirmed = await confirmAction({
      question: `Prompt-Bibliothek „${library?.name}“ löschen?`,
      consequence:
        'Das entfernt auch alle Prompts, Rechte und Space-Zuordnungen dieser Prompt-Bibliothek. Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    setDeleteError(null)
    try {
      await deleteLibrary(libraryId)
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
      <Box sx={{ maxWidth: 880 }}>
        <AssetDetailHeader
          assetType="PROMPT_LIBRARY"
          assetId={libraryId}
          name={library.name}
          description={library.description}
          isPublic={library.reach.allAccounts}
          badges={<MetaBadge accent>{assetRoleLabel(library.myRole)}</MetaBadge>}
          administrative={administrative}
          headline={{
            idPrefix: 'prompt-library-detail',
            nameLabel: 'Name der Prompt-Bibliothek',
            editLabel: 'Name und Beschreibung bearbeiten',
            canEdit: canManage,
            onSave: saveHeadline,
          }}
          extent={library.promptCount === 1 ? '1 Prompt' : `${library.promptCount} Prompts`}
          spaceCount={catalog.entry?.spaceCount}
          responsible={responsibleParty({
            ownerType: library.ownerType,
            ownerLabel: library.ownerName,
            succession: library.succession,
          })}
          updatedAt={library.updatedAt}
          favorite={catalog.entry?.favorite}
          onFavoriteChange={catalog.setFavorite}
          mayUseInSpace={mayUseInSpace}
          onAssociated={associationsChanged}
          onDelete={canDelete ? () => void handleDelete() : undefined}
          // ADR-0036, Entscheidung 6: state and addressee for every reader - no date, no former
          // owner, no reason.
          note={<SuccessionStateNote succession={library.succession} />}
        />

        {deleteError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setDeleteError(null)}>
            {deleteError}
          </Alert>
        )}

        <AreaTabs
          tabs={tabs}
          value={activeTab}
          href={(value) => promptLibraryRoute(libraryId, value)}
          label="Bereiche der Prompt-Bibliothek"
          idPrefix="prompt-library"
        >
          {(value) => {
            if (value === 'freigaben') {
              return (
                <Stack>
                  <AssetOwnerSection
                    assetType="PROMPT_LIBRARY"
                    assetId={libraryId}
                    ownerType={library.ownerType}
                    ownerName={library.ownerName}
                    canTransfer={library.myRole === 'OWNER'}
                    onTransferred={() => loadLibrary(libraryId)}
                  />
                  {canManage && (
                    <AssetGrantsSection assetType="PROMPT_LIBRARY" assetId={libraryId} />
                  )}
                  <AssetAccessDerivationSection assetType="PROMPT_LIBRARY" assetId={libraryId} />
                </Stack>
              )
            }
            if (value === 'zuordnungen') {
              return (
                <AssetSpacesSection
                  assetType="PROMPT_LIBRARY"
                  assetId={libraryId}
                  name={library.name}
                  canManage={canManage}
                  mayUseInSpace={mayUseInSpace}
                  refreshToken={associationsVersion}
                  onChanged={() => catalog.reload()}
                />
              )
            }
            return <PromptsArea library={library} canEditPrompts={canEditPrompts} />
          }}
        </AreaTabs>
      </Box>
    </Box>
  )
}
