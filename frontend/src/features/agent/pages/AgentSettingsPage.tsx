import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import Typography from '@mui/material/Typography'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import type { AgentSettingsResponse } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import { useAgentAvailability } from '@/shared/session/agentAvailability'
import { notify } from '@/shared/stores/notificationStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import PageSection from '@/shared/ui/PageSection'
import SettingsForm from '@/shared/ui/settings/SettingsForm'
import { contentWidth, fontSize } from '@/shared/theme/tokens'
import { getAgentSettings, updateAgentSettings } from '../api/agentApi'
import { AGENT_TOOLS } from '../components/agentTexts'

const muted = { color: 'text.secondary' } as const

/**
 * The installation's agents in the administration (system admin only): whether there are agents,
 * which takes effect with „Speichern“ on every instance without a restart, and the tools a run may
 * be offered at all.
 */
export default function AgentSettingsPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const adopt = useAgentAvailability((s) => s.adopt)
  const [current, setCurrent] = useState<AgentSettingsResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [enabled, setEnabled] = useState(false)
  const [tools, setTools] = useState<string[]>([])
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const take = useCallback((settings: AgentSettingsResponse) => {
    setCurrent(settings)
    setEnabled(settings.enabled)
    setTools(settings.enabledTools)
  }, [])

  useEffect(() => {
    if (!isSystemAdmin) return
    getAgentSettings()
      .then(take)
      .catch(() => setLoadError('Die Einstellungen der Agenten konnten nicht geladen werden.'))
  }, [isSystemAdmin, take])

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Agenten" gutterBottom />
        <Alert severity="info">
          Agenten schaltet die Systemverwaltung frei. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  async function handleSave() {
    setSaving(true)
    setSaveError(null)
    try {
      const saved = await updateAgentSettings({
        enabled,
        enabledTools: AGENT_TOOLS.map((tool) => tool.name).filter((name) => tools.includes(name)),
      })
      take(saved)
      adopt(saved.enabled)
      notify('Einstellungen der Agenten gespeichert.', 'success')
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : 'Speichern fehlgeschlagen.')
    } finally {
      setSaving(false)
    }
  }

  const available = current?.availableTools ?? []
  const dirty =
    current !== null &&
    (enabled !== current.enabled ||
      tools.length !== current.enabledTools.length ||
      tools.some((name) => !current.enabledTools.includes(name)))

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={SmartToyOutlinedIcon}
          title="Agenten"
          description="Gilt für die gesamte Installation: ob es Agenten gibt und welche Werkzeuge ein Lauf überhaupt nutzen darf. Änderungen wirken sofort auf allen Instanzen, ohne Neustart."
        />
        {loadError && <Alert severity="error">{loadError}</Alert>}
        {current && (
          <SettingsForm
            aria-label="Einstellungen der Agenten"
            onSave={handleSave}
            busy={saving}
            dirty={dirty}
            onDiscard={() => {
              take(current)
              setSaveError(null)
            }}
            result={saveError && <Alert severity="error">{saveError}</Alert>}
          >
            <PageSection title="Agenten">
              <FormControlLabel
                control={
                  <Checkbox checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                }
                label="Agenten in dieser Installation anbieten"
              />
              <Typography sx={{ fontSize: fontSize.meta, mt: 0.5, maxWidth: '80ch', ...muted }}>
                Ohne Haken sind Agenten, ihr Anlegerecht und ihre Seiten nirgends sichtbar und über
                die Schnittstelle nicht erreichbar. Beim ersten Einschalten legt OPAA den Agenten
                „Recherche“ an, sichtbar für alle Konten und danach frei änderbar. Wer Agenten
                anlegen darf, regelt das Anlegerecht „Agenten anlegen“; ab Werk niemand.
              </Typography>
            </PageSection>
            <PageSection title="Werkzeuge">
              <Typography sx={{ fontSize: fontSize.meta, mb: 1, maxWidth: '80ch', ...muted }}>
                Ein abgeschaltetes Werkzeug bietet kein Lauf an, auch wenn ein Agent es nennt.
              </Typography>
              <Box role="group" aria-label="Freigeschaltete Werkzeuge">
                {AGENT_TOOLS.filter((tool) => available.includes(tool.name)).map((tool) => (
                  <FormControlLabel
                    key={tool.name}
                    sx={{ display: 'flex', alignItems: 'flex-start', mb: 0.5 }}
                    control={
                      <Checkbox
                        size="small"
                        checked={tools.includes(tool.name)}
                        onChange={() =>
                          setTools((list) =>
                            list.includes(tool.name)
                              ? list.filter((name) => name !== tool.name)
                              : [...list, tool.name],
                          )
                        }
                        sx={{ pt: 0.25 }}
                      />
                    }
                    label={
                      <Box>
                        <Typography sx={{ fontSize: fontSize.text }}>{tool.label}</Typography>
                        <Typography sx={{ fontSize: fontSize.meta, ...muted }}>
                          {tool.description}
                        </Typography>
                      </Box>
                    }
                  />
                ))}
              </Box>
            </PageSection>
          </SettingsForm>
        )}
      </Box>
    </Box>
  )
}
