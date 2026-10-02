import SpaceAssetAssociationSection from './SpaceAssetAssociationSection'

interface SpacePromptsSectionProps {
  spaceId: string
  /** Ein Kurator darf Prompt-Bibliotheken zuordnen und lösen, ein Mitglied nur zusehen. */
  canManage: boolean
}

const texts = {
  heading: 'Zugeordnete Prompt-Bibliotheken',
  intro:
    'Im Chat stehen nur die Prompts der hier zugeordneten Prompt-Bibliotheken zur Auswahl. Eine Zuordnung gewährt niemandem zusätzlichen Zugriff — nur Mitglieder mit eigenem Leserecht auf die Prompt-Bibliothek sehen ihre Prompts.',
  loading: 'Prompt-Bibliotheken werden geladen …',
  empty: 'Diesem Space sind keine Prompt-Bibliotheken zugeordnet.',
  pickerHeading: 'Weitere Prompt-Bibliotheken zuordnen',
  associated: 'Prompt-Bibliothek zugeordnet',
}

/**
 * Der Reiter „Prompts" der Space-Einstellungen: die dem Space zugeordneten Prompt-Bibliotheken,
 * und für Kuratoren die Zuordnung weiterer lesbarer.
 */
export default function SpacePromptsSection({ spaceId, canManage }: SpacePromptsSectionProps) {
  return (
    <SpaceAssetAssociationSection
      spaceId={spaceId}
      canManage={canManage}
      assetType="PROMPT_LIBRARY"
      texts={texts}
    />
  )
}
