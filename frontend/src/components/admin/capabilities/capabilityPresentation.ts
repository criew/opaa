import type { ElementType } from 'react'
import AutoStoriesOutlinedIcon from '@mui/icons-material/AutoStoriesOutlined'
import CableOutlinedIcon from '@mui/icons-material/CableOutlined'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import LightbulbOutlinedIcon from '@mui/icons-material/LightbulbOutlined'
import WorkspacesOutlinedIcon from '@mui/icons-material/WorkspacesOutlined'
import type { Capability } from '../../../types/api'

export interface CapabilityPresentation {
  /** The short name in the overview, without the verb: the page heading already asks it. */
  title: string
  /** What the created thing is, for someone who has never seen it. */
  description: string
  icon: ElementType
}

const PRESENTATION: Record<Capability, CapabilityPresentation> = {
  CREATE_SPACE: {
    title: 'Spaces',
    description: 'Gemeinsame Arbeitsbereiche, in denen ein Team Chats und Bibliotheken bündelt.',
    icon: WorkspacesOutlinedIcon,
  },
  CREATE_LIBRARY: {
    title: 'Bibliotheken für Uploads',
    description: 'Sammlungen, in die man Dokumente selbst hochlädt.',
    icon: AutoStoriesOutlinedIcon,
  },
  CREATE_CONNECTOR_LIBRARY: {
    title: 'Bibliotheken mit Anbindung',
    description:
      'Bibliotheken, die Inhalte aus anderen Systemen übernehmen, etwa aus Confluence oder Nextcloud.',
    icon: CableOutlinedIcon,
  },
  CREATE_INTERNAL_GROUP: {
    title: 'Interne Gruppen',
    description: 'Eigene Gruppen, die nicht aus einem Verzeichnis kommen.',
    icon: GroupsOutlinedIcon,
  },
  CREATE_PROMPT_LIBRARY: {
    title: 'Prompt-Bibliotheken',
    description: 'Sammlungen von Frage-Vorlagen, die andere übernehmen können.',
    icon: LightbulbOutlinedIcon,
  },
}

export function presentationOf(
  capability: Capability,
  fallbackLabel: string,
): CapabilityPresentation {
  return (
    PRESENTATION[capability] ?? {
      title: fallbackLabel,
      description: '',
      icon: WorkspacesOutlinedIcon,
    }
  )
}
