import Stack from '@mui/material/Stack'
import MetaBadge from '@/shared/ui/MetaBadge'
import StateDot from '@/shared/ui/StateDot'
import { useAuthStore } from '@/shared/session/authStore'
import { assetRoleLabel } from '@/shared/utils/labels'
import {
  AssetAccessDerivationSection,
  AssetDetailHeader,
  AssetGrantsSection,
  AssetOwnerSection,
  responsibleParty,
  useAssetCatalogEntry,
} from '@/features/asset'
import type { ReactNode } from 'react'
import type { AgentSetup } from '../hooks/useAgentSetup'
import { countLabel } from '../agentTexts'

/** The asset head of an agent: type, name, description, extent, owner and favorite. */
export function AgentHeader({ setup, actions }: { setup: AgentSetup; actions?: ReactNode }) {
  const agent = setup.agent
  const catalog = useAssetCatalogEntry('AGENT', agent?.id ?? '')
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  if (!agent) return null
  const canEdit =
    agent.myRole === 'OWNER' || agent.myRole === 'MANAGER' || agent.myRole === 'EDITOR'
  return (
    <AssetDetailHeader
      assetType="AGENT"
      assetId={agent.id}
      name={agent.name}
      description={agent.description}
      isPublic={agent.allAccounts}
      badges={
        <>
          {agent.builtIn && <MetaBadge>Mitgeliefert</MetaBadge>}
          <MetaBadge>{assetRoleLabel(agent.myRole)}</MetaBadge>
        </>
      }
      headline={{
        idPrefix: 'agent-detail',
        nameLabel: 'Name des Agenten',
        editLabel: 'Name und Beschreibung ändern',
        canEdit,
        onSave: async (name, description) => {
          setup.patch({ name, description })
        },
      }}
      extent={`${countLabel(agent.libraryIds.length, 'Bibliothek', 'Bibliotheken')} · ${countLabel(agent.tools.length, 'Werkzeug', 'Werkzeuge')}`}
      responsible={responsibleParty({
        ownerType: 'GROUP',
        ownerLabel: agent.responsibleUnit || agent.ownerName,
      })}
      status={agent.locked ? <StateDot tone="error" label="Gesperrt" /> : undefined}
      menuItems={
        isSystemAdmin
          ? [
              {
                label: agent.locked ? 'Entsperren' : 'Sperren',
                onClick: () => void setup.toggleLock(),
              },
            ]
          : undefined
      }
      updatedAt={agent.updatedAt}
      favorite={catalog.entry?.favorite}
      onFavoriteChange={catalog.setFavorite}
      actions={actions}
    />
  )
}

/** Owner, grants and the own access, as on every asset. */
export function AgentGrants({ setup }: { setup: AgentSetup }) {
  const agent = setup.agent
  if (!agent) return null
  const canManage = agent.myRole === 'OWNER' || agent.myRole === 'MANAGER'
  return (
    <Stack>
      <AssetOwnerSection
        assetType="AGENT"
        assetId={agent.id}
        ownerType={agent.ownerType}
        ownerName={agent.ownerName}
        canTransfer={agent.myRole === 'OWNER'}
        onTransferred={() => undefined}
      />
      {canManage && <AssetGrantsSection assetType="AGENT" assetId={agent.id} />}
      <AssetAccessDerivationSection assetType="AGENT" assetId={agent.id} />
    </Stack>
  )
}
