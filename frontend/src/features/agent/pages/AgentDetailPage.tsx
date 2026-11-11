import { useEffect, useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import type { AgentUpdateRequest } from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { assetRoleLabel } from '@/shared/utils/labels'
import { AGENT_TABS, CATALOG_ROUTE, agentRoute, type AgentTab } from '@/shared/routes'
import AreaTabs from '@/shared/ui/AreaTabs'
import MetaBadge from '@/shared/ui/MetaBadge'
import { useAuthStore } from '@/shared/session/authStore'
import { SuccessionStateNote, successionAwareMessage } from '@/features/succession'
import {
  AssetAccessDerivationSection,
  AssetDetailHeader,
  AssetGrantsSection,
  AssetOwnerSection,
  AssetResponsibilitySection,
  AssetSpacesSection,
  responsibilityNote,
  responsibleParty,
  useAssetCatalogEntry,
} from '@/features/asset'
import { useMyGroups } from '@/features/principal'
import { useAgentStore } from '../stores/agentStore'
import {
  InstructionArea,
  KnowledgeAndToolsArea,
  ProcedureArea,
} from '../components/AgentConfigAreas'
import { updateRequest } from '../components/updateRequest'
import { countLabel } from '../components/agentTexts'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'
import { contentWidth } from '@/shared/theme/tokens'

const tabs: Array<{ value: AgentTab; label: string }> = [
  { value: 'auftrag', label: 'Auftrag' },
  { value: 'wissen', label: 'Wissen und Werkzeuge' },
  { value: 'ablauf', label: 'Ablauf' },
  { value: 'freigaben', label: 'Freigaben' },
  { value: 'zuordnungen', label: 'Zuordnungen' },
]

function isAgentTab(value: string | undefined): value is AgentTab {
  return AGENT_TABS.some((tab) => tab === value)
}

/**
 * One agent, built like the other asset pages: the shared head, then the areas „Auftrag“,
 * „Wissen und Werkzeuge“, „Ablauf“, „Freigaben“ and „Zuordnungen“, each a route of its own. The
 * setup areas read as a raster - title, content, „Ändern“ - and each row saves on its own; a
 * reader finds them without the action.
 */
export default function AgentDetailPage() {
  const { agentId, tab } = useParams()
  const navigate = useNavigate()
  const agent = useAgentStore((s) => (agentId ? s.details[agentId] : undefined))
  const storeError = useAgentStore((s) => s.error)
  const loadAgent = useAgentStore((s) => s.loadAgent)
  const loadAgents = useAgentStore((s) => s.loadAgents)
  const update = useAgentStore((s) => s.updateAgent)
  const remove = useAgentStore((s) => s.deleteAgent)
  const lockAgent = useAgentStore((s) => s.lockAgent)
  const unlockAgent = useAgentStore((s) => s.unlockAgent)
  const myGroups = useMyGroups()
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  // The list holds only what the caller may read, without the administrative bypass.
  const listed = useAgentStore((s) => s.agents.some((a) => a.id === agentId))
  const mayUseInSpace = !isSystemAdmin || listed
  const catalog = useAssetCatalogEntry('AGENT', agentId ?? '')
  const [associationsVersion, setAssociationsVersion] = useState(0)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  useEffect(() => {
    if (agentId) void loadAgent(agentId)
  }, [agentId, loadAgent])

  useEffect(() => {
    if (isSystemAdmin && !listed) void loadAgents()
  }, [isSystemAdmin, listed, loadAgents])

  if (!agentId) return <Navigate to={CATALOG_ROUTE} replace />
  if (tab !== undefined && (!isAgentTab(tab) || tab === AGENT_TABS[0])) {
    return <Navigate to={agentRoute(agentId)} replace />
  }

  if (!agent) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
        {storeError ? (
          <LoadError
            message="Der Agent konnte nicht geladen werden."
            detail={storeError}
            onRetry={() => void loadAgent(agentId)}
          />
        ) : (
          <LoadingState shape="detail" count={4} />
        )}
      </Box>
    )
  }

  const activeTab: AgentTab = tab ?? AGENT_TABS[0]
  const canManage = agent.myRole === 'MANAGER' || agent.myRole === 'OWNER'
  const canDelete = agent.myRole === 'OWNER'
  const administrative =
    isSystemAdmin &&
    catalog.entry !== undefined &&
    (catalog.entry === null || catalog.entry.myRole !== agent.myRole)
  const id = agent.id
  const current = agent

  async function save(change: Partial<AgentUpdateRequest>) {
    await update(id, updateRequest(current, change))
  }

  async function saveHeadline(name: string, purpose: string) {
    try {
      await save({ name: name.trim(), description: purpose.trim() || null })
    } catch (err) {
      throw new Error(successionAwareMessage(err, 'Speichern fehlgeschlagen'), { cause: err })
    }
  }

  async function handleDelete() {
    const confirmed = await confirmAction({
      question: `„${current.name}“ löschen?`,
      consequence: 'Der Agent, seine Rechte und seine Space-Zuordnungen werden gelöscht.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    setDeleteError(null)
    try {
      await remove(id)
      navigate(CATALOG_ROUTE)
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    }
  }

  const note = responsibilityNote(agent.responsibility)

  function associationsChanged() {
    catalog.reload()
    setAssociationsVersion((version) => version + 1)
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.detail }}>
        <AssetDetailHeader
          assetType="AGENT"
          assetId={id}
          name={agent.name}
          description={agent.description}
          isPublic={agent.reach.allAccounts}
          badges={
            <>
              <MetaBadge>{assetRoleLabel(agent.myRole)}</MetaBadge>
              {agent.delivered && <MetaBadge>Mitgeliefert</MetaBadge>}
              <MetaBadge>{`Fassung ${agent.revision}`}</MetaBadge>
              {agent.responsibility.lock && <MetaBadge>Gesperrt</MetaBadge>}
            </>
          }
          administrative={administrative}
          headline={{
            idPrefix: 'agent-detail',
            nameLabel: 'Name des Agenten',
            editLabel: 'Name und Zweck ändern',
            canEdit: canManage,
            onSave: saveHeadline,
          }}
          extent={countLabel(agent.toolNames.length, 'Werkzeug', 'Werkzeuge')}
          spaceCount={catalog.entry?.spaceCount}
          responsible={responsibleParty({
            ownerType: agent.ownerType,
            ownerLabel: agent.ownerName,
            succession: agent.succession,
          })}
          updatedAt={agent.updatedAt}
          favorite={catalog.entry?.favorite}
          onFavoriteChange={catalog.setFavorite}
          onDelete={canDelete ? () => void handleDelete() : undefined}
          note={
            <>
              <SuccessionStateNote succession={agent.succession} />
              {note && (
                <Alert severity={note.severity} sx={{ mt: 1 }}>
                  {note.text}
                </Alert>
              )}
            </>
          }
        />

        {deleteError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setDeleteError(null)}>
            {deleteError}
          </Alert>
        )}

        <AreaTabs
          tabs={tabs}
          value={activeTab}
          href={(value) => agentRoute(id, value)}
          label="Bereiche des Agenten"
          idPrefix="agent"
        >
          {(value) => {
            if (value === 'auftrag') {
              return <InstructionArea agent={agent} canEdit={canManage} save={save} />
            }
            if (value === 'wissen') {
              return <KnowledgeAndToolsArea agent={agent} canEdit={canManage} save={save} />
            }
            if (value === 'ablauf') {
              return <ProcedureArea agent={agent} canEdit={canManage} save={save} />
            }
            if (value === 'freigaben') {
              return (
                <Stack>
                  <AssetResponsibilitySection
                    idPrefix="agent"
                    name={agent.name}
                    responsibility={agent.responsibility}
                    canEdit={canManage}
                    canLock={isSystemAdmin}
                    groups={myGroups.groups}
                    lockConsequence="Der Agent erscheint nicht mehr in der Auswahl, und jeder Aufruf wird abgewiesen, auf allen Instanzen ab dem nächsten Aufruf. Er bleibt erhalten und lässt sich wieder entsperren."
                    onSave={(responsible) => save({ responsible })}
                    onLock={(reason) => lockAgent(id, reason)}
                    onUnlock={() => unlockAgent(id)}
                  />
                  <AssetOwnerSection
                    assetType="AGENT"
                    assetId={id}
                    ownerType={agent.ownerType}
                    ownerName={agent.ownerName}
                    canTransfer={agent.myRole === 'OWNER'}
                    onTransferred={() => loadAgent(id)}
                  />
                  {canManage && <AssetGrantsSection assetType="AGENT" assetId={id} />}
                  <AssetAccessDerivationSection assetType="AGENT" assetId={id} />
                </Stack>
              )
            }
            return (
              <AssetSpacesSection
                assetType="AGENT"
                assetId={id}
                name={agent.name}
                canManage={canManage}
                mayUseInSpace={mayUseInSpace}
                reachFrozen={Boolean(agent.succession)}
                refreshToken={associationsVersion}
                onChanged={associationsChanged}
              />
            )
          }}
        </AreaTabs>
      </Box>
    </Box>
  )
}
