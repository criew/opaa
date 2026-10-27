import { useEffect, useState } from 'react'
import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import PsychologyOutlinedIcon from '@mui/icons-material/PsychologyOutlined'
import type { LlmModelResponse } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import { useLlmModelStore } from '../stores/llmModelStore'
import { notify } from '@/shared/stores/notificationStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import AreaTabs from '@/shared/ui/AreaTabs'
import EmbeddingChangeSection from '../components/EmbeddingChangeSection'
import EmbeddingInfoTable from '../components/EmbeddingInfoTable'
import LlmModelFormDialog from '../components/LlmModelFormDialog'
import ModelList from '../components/ModelList'
import ModelRoleSection from '../components/ModelRoleSection'
import { contentWidth } from '@/shared/theme/tokens'

export type LlmModelTab = 'chat' | 'embedding' | 'rerank' | 'transcription'

function isLlmModelTab(value: string | undefined): value is LlmModelTab {
  return tabs.some((tab) => tab.value === value)
}

// „Chat-Modelle" instead of „Chat": the application's main area has that name too, and what is
// meant here is not the conversation but the model that answers it.
const tabs: Array<{ value: LlmModelTab; label: string }> = [
  { value: 'chat', label: 'Chat-Modelle' },
  { value: 'embedding', label: 'Einbettung' },
  { value: 'rerank', label: 'Reranking' },
  { value: 'transcription', label: 'Spracheingabe' },
]

/**
 * The chat models as a list: one table row per model, the actions in the row menu,
 * creating and editing in the same dialog — the same pattern as the account list under „Benutzer".
 *
 * Count and „Neues Modell" are here and not in the page header: they apply only to this
 * area, and in the embedding tab both would be a statement about something not visible
 * there.
 */
function ChatModelsSection() {
  const models = useLlmModelStore((s) => s.models)
  const isLoading = useLlmModelStore((s) => s.isLoading)
  const error = useLlmModelStore((s) => s.error)
  const loadModels = useLlmModelStore((s) => s.loadModels)
  const [form, setForm] = useState<{ open: boolean; model: LlmModelResponse | null }>({
    open: false,
    model: null,
  })

  useEffect(() => {
    void loadModels()
  }, [loadModels])

  return (
    <>
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
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
          {models.length === 1 ? '1 Chat-Modell' : `${models.length} Chat-Modelle`}
        </Typography>
        <Button variant="contained" onClick={() => setForm({ open: true, model: null })}>
          Neues Modell
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {!isLoading && !error && !models.some((model) => model.active) && (
        <Alert severity="warning" sx={{ mb: 2, maxWidth: '80ch' }}>
          Es ist noch kein Chat-Modell aktiv. Bis eines angelegt und aktiviert ist, beantwortet der
          Chat keine Fragen; die Personen sehen dort einen Hinweis.
        </Alert>
      )}

      {isLoading ? (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Modelle werden geladen …
        </Typography>
      ) : models.length === 0 ? (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Es sind noch keine Modelle hinterlegt.
        </Typography>
      ) : (
        <ModelList models={models} onEdit={(model) => setForm({ open: true, model })} />
      )}

      {/* `key` per edited model: MUI only unmounts the dialog's children on close,
         not the component - without the key the draft of the last opened
         model would carry over into the next one. */}
      <LlmModelFormDialog
        key={form.model?.id ?? 'new'}
        open={form.open}
        model={form.model}
        onClose={() => setForm({ open: false, model: null })}
        onSaved={(saved) =>
          notify(
            saved ? `„${saved.displayName}“ wurde gespeichert.` : 'Das Modell wurde angelegt.',
            'success',
          )
        }
      />
    </>
  )
}

/** How often the tab reads the model and the index again while a change or a refill runs. */
export const EMBEDDING_POLL_INTERVAL_MS = 5000

function EmbeddingInfoSection() {
  const embeddingInfo = useLlmModelStore((s) => s.embeddingInfo)
  const loadEmbeddingInfo = useLlmModelStore((s) => s.loadEmbeddingInfo)
  const watching = Boolean(embeddingInfo?.index?.stale || embeddingInfo?.index?.running)

  useEffect(() => {
    void loadEmbeddingInfo()
  }, [loadEmbeddingInfo])

  useEffect(() => {
    if (!watching) return
    const timer = setInterval(() => void loadEmbeddingInfo(), EMBEDDING_POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [watching, loadEmbeddingInfo])

  return (
    <>
      {/* No heading of its own: the tab is already called „Einbettung", and „Einbettungsmodell"
         below it would be the same statement a second time. The area is named via the tab
         its panel points to. */}
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2.5, maxWidth: '80ch' }}>
        Ein Wechsel des Einbettungsmodells macht die bestehenden Vektoren unvergleichbar: OPAA
        bettet dann alle Abschnitte neu ein, und bis zum Abschluss sucht OPAA nur über die
        Volltextsuche. Ein anderer Endpunkt desselben Modells geht ohne Neueinbettung.
      </Typography>
      {embeddingInfo && !embeddingInfo.model ? (
        <>
          <Alert severity="warning" sx={{ mb: 3, maxWidth: '80ch' }}>
            Es ist noch kein Embedding-Modell eingerichtet. Ohne es gibt es keinen Suchindex:
            Indexierung, Upload und Suche stehen erst bereit, wenn das erste Modell festgelegt ist.
          </Alert>
          <EmbeddingChangeSection info={embeddingInfo} onChanged={() => void loadEmbeddingInfo()} />
        </>
      ) : embeddingInfo ? (
        <>
          <EmbeddingInfoTable info={embeddingInfo} />
          <Box sx={{ mt: 3 }}>
            <EmbeddingChangeSection
              info={embeddingInfo}
              onChanged={() => void loadEmbeddingInfo()}
            />
          </Box>
        </>
      ) : (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Einbettungskonfiguration wird geladen …
        </Typography>
      )}
    </>
  )
}

/**
 * The system administration's model management in four areas: „Chat-Modelle" lists the
 * models that answer questions; „Einbettung" names the model that carries the index - and that
 * deliberately cannot be changed; „Reranking" and „Spracheingabe" hold the two optional roles.
 *
 * The areas are routes of their own (`/admin/models/chat`, `/admin/models/embedding`) as on the
 * other area pages: a link should land in the right area, and a reload should keep
 * it. Every area loads only what it shows.
 */
export default function LlmModelManagementPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { tab } = useParams()

  // A typo in the path is not silently reinterpreted: the address bar says what is shown.
  if (!isLlmModelTab(tab)) {
    return <Navigate to="/admin/models/chat" replace />
  }
  const activeTab: LlmModelTab = tab

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Modelle" gutterBottom />
        <Alert severity="info">
          Die Modellverwaltung wird von der Systemverwaltung gepflegt. Für Ihr Konto ist diese Seite
          nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={PsychologyOutlinedIcon}
          title="Modelle"
          description="Gilt für die gesamte Anwendung. Änderungen wirken sich auf alle Spaces und Benutzer aus. Das aktive Chat-Modell beantwortet jede Frage dieser Installation."
        />

        <AreaTabs
          tabs={tabs}
          value={activeTab}
          href={(value) => `/admin/models/${value}`}
          label="Bereiche der Modellverwaltung"
          idPrefix="models"
        >
          {(value) =>
            value === 'chat' ? (
              <ChatModelsSection />
            ) : value === 'embedding' ? (
              <EmbeddingInfoSection />
            ) : (
              <ModelRoleSection key={value} kind={value} />
            )
          }
        </AreaTabs>
      </Box>
    </Box>
  )
}
