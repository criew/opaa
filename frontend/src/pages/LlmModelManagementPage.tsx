import { useEffect, useState } from 'react'
import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import PsychologyOutlinedIcon from '@mui/icons-material/PsychologyOutlined'
import type { LlmModelResponse } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { useLlmModelStore } from '../stores/llmModelStore'
import { notify } from '../stores/notificationStore'
import PageHeading from '../components/a11y/PageHeading'
import AreaPageHeader from '../components/AreaPageHeader'
import AreaTabs from '../components/AreaTabs'
import EmbeddingInfoTable from '../components/admin/models/EmbeddingInfoTable'
import LlmModelFormDialog from '../components/admin/models/LlmModelFormDialog'
import ModelList from '../components/admin/models/ModelList'
import { contentWidth } from '../theme/tokens'

export type LlmModelTab = 'chat' | 'embedding'

function isLlmModelTab(value: string | undefined): value is LlmModelTab {
  return value === 'chat' || value === 'embedding'
}

// „Chat-Modelle" statt „Chat": Der Hauptbereich der Anwendung heißt ebenso, und gemeint ist hier
// nicht das Gespräch, sondern das Modell, das es beantwortet.
const tabs: Array<{ value: LlmModelTab; label: string }> = [
  { value: 'chat', label: 'Chat-Modelle' },
  { value: 'embedding', label: 'Einbettung' },
]

/**
 * Die Chat-Modelle als Liste (#1621): eine Tabellenzeile je Modell, die Handlungen im Zeilenmenü,
 * Anlegen und Bearbeiten im selben Dialog — dasselbe Muster wie die Kontenliste unter „Benutzer".
 *
 * Zählung und „Neues Modell" stehen hier und nicht im Seitenkopf: Sie gelten nur für diesen
 * Bereich, und im Einbettungs-Reiter wäre beides eine Aussage über etwas, das dort nicht zu sehen
 * ist.
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

      {/* `key` je bearbeitetem Modell: MUI unmountet beim Schließen nur die Kinder des Dialogs,
          nicht die Komponente - ohne den Schlüssel trüge der Entwurf des zuletzt geöffneten
          Modells in das nächste. */}
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

function EmbeddingInfoSection() {
  const embeddingInfo = useLlmModelStore((s) => s.embeddingInfo)
  const loadEmbeddingInfo = useLlmModelStore((s) => s.loadEmbeddingInfo)

  useEffect(() => {
    void loadEmbeddingInfo()
  }, [loadEmbeddingInfo])

  return (
    <>
      {/* Keine eigene Überschrift: Der Reiter heißt bereits „Einbettung", und „Einbettungsmodell"
          darunter wäre dieselbe Aussage ein zweites Mal. Benannt ist der Bereich über den Reiter,
          auf den sein Panel zeigt. */}
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2.5, maxWidth: '80ch' }}>
        Anders als das Chat-Modell lässt sich das Einbettungsmodell hier nicht ändern: Ein Wechsel
        macht bestehende Vektoren unvergleichbar und würde eine vollständige Neuindizierung aller
        Wissensbibliotheken erfordern.
      </Typography>
      {embeddingInfo ? (
        <EmbeddingInfoTable info={embeddingInfo} />
      ) : (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Einbettungskonfiguration wird geladen …
        </Typography>
      )}
    </>
  )
}

/**
 * Die Modellverwaltung der Systemverwaltung in zwei Bereichen (#1619): „Chat-Modelle" führt die
 * Modelle, die Fragen beantworten; „Einbettung" nennt das Modell, das den Index trägt - und das
 * sich bewusst nicht ändern lässt.
 *
 * Die Bereiche sind eigene Routen (`/admin/models/chat`, `/admin/models/embedding`) wie auf den
 * übrigen Bereichsseiten: Ein Verweis soll im richtigen Bereich landen, und ein Neuladen ihn
 * behalten. Jeder Bereich lädt nur, was er zeigt.
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
          {(value) => (value === 'chat' ? <ChatModelsSection /> : <EmbeddingInfoSection />)}
        </AreaTabs>
      </Box>
    </Box>
  )
}
