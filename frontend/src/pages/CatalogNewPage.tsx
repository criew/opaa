import { useState } from 'react'
import { useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import PageHeading from '../components/a11y/PageHeading'
import ChoiceTileGroup from '../components/choice/ChoiceTileGroup'
import { creatableAssetTypes } from '../components/assets/assetTypeRegistry'
import { useMyCapabilities } from '../hooks/useMyCapabilities'
import { CATALOG_ROUTE } from '../routes'
import type { AssetType } from '../types/api'

const QUESTION = 'Was möchten Sie anlegen?'

/**
 * "Neu" in the catalog (ADR-0039, Entscheidung 1): the type choice as tiles, offering only the
 * types the person may create; "Weiter" hands over to the type's own wizard.
 */
export default function CatalogNewPage() {
  const navigate = useNavigate()
  const { isMissing } = useMyCapabilities()
  const offered = creatableAssetTypes(isMissing)
  const [chosen, setChosen] = useState<AssetType | null>(null)
  // Derived, not stored: a type that turns out not to be offered once the rights are in must not
  // stay chosen, or "Weiter" would lead into a wizard that can never create.
  const selected = offered.find((definition) => definition.type === chosen) ?? offered[0]

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ maxWidth: 720 }}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>Neu</Typography>
        <PageHeading title={QUESTION} sx={{ fontSize: 26, fontWeight: 600, mb: 3 }} />

        {offered.length === 0 ? (
          <Alert severity="info">
            Ihnen fehlt ein Anlegerecht für jede der Arten. Wenden Sie sich an die Systemverwaltung,
            wenn Sie eines benötigen.
          </Alert>
        ) : (
          <ChoiceTileGroup<AssetType>
            aria-label={QUESTION}
            value={selected?.type ?? null}
            onChange={setChosen}
            tiles={offered.map((definition) => {
              const Icon = definition.Icon
              return {
                value: definition.type,
                label: definition.label,
                description: definition.description,
                icon: <Icon sx={{ fontSize: 22 }} />,
              }
            })}
          />
        )}

        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1.5,
            mt: 4,
            pt: 2,
            borderTop: 1,
            borderColor: 'divider',
          }}
        >
          <Button variant="text" onClick={() => navigate(CATALOG_ROUTE)}>
            Abbrechen
          </Button>
          <Box sx={{ flex: 1 }} />
          {selected && (
            <Button variant="contained" onClick={() => navigate(selected.createRoute)}>
              Weiter
            </Button>
          )}
        </Box>
      </Box>
    </Box>
  )
}
