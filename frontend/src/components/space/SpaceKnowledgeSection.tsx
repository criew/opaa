import { getLibraries } from '../../services/api'
import SpaceAssetAssociationSection from './SpaceAssetAssociationSection'

interface SpaceKnowledgeSectionProps {
  spaceId: string
  /** #203: ein Kurator darf Bibliotheken zuordnen und lösen, ein Mitglied nur zusehen. */
  canManage: boolean
}

const texts = {
  heading: 'Zugeordnete Bibliotheken',
  intro:
    'Eine Zuordnung stellt eine Bibliothek in diesem Space bereit, gewährt aber niemandem zusätzlichen Zugriff — nur Mitglieder mit eigenem Leserecht auf die Bibliothek sehen ihre Treffer.',
  loading: 'Bibliotheken werden geladen …',
  empty: 'Diesem Space sind keine Bibliotheken zugeordnet.',
  pickerLabel: 'Bibliothek',
  pickerPlaceholder: 'Bibliothek suchen …',
  associated: 'Bibliothek zugeordnet',
}

/**
 * Der Reiter „Wissen" der Space-Einstellungen (#1917): die dem Space zugeordneten
 * Wissensbibliotheken, und für Kuratoren die Zuordnung weiterer lesbarer.
 */
export default function SpaceKnowledgeSection({ spaceId, canManage }: SpaceKnowledgeSectionProps) {
  return (
    <SpaceAssetAssociationSection
      spaceId={spaceId}
      canManage={canManage}
      assetType="KNOWLEDGE_LIBRARY"
      texts={texts}
      loadReadable={getLibraries}
    />
  )
}
