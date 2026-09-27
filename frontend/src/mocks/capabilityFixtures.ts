import type { MyCapabilitiesResponse } from '../types/api'

/**
 * The capabilities of the mock account: every one, as for a system administrator - the four
 * delivered to all accounts plus CREATE_INTERNAL_GROUP, which is delivered to nobody (#1814).
 */
export const mockMyCapabilities: MyCapabilitiesResponse = {
  capabilities: [
    'CREATE_SPACE',
    'CREATE_LIBRARY',
    'CREATE_CONNECTOR_LIBRARY',
    'CREATE_INTERNAL_GROUP',
    'CREATE_PROMPT_LIBRARY',
  ],
}
