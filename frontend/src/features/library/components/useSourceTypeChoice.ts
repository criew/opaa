import type { Capability, SourceTypeKey } from '@/shared/api/types'
import { useSourceTypes } from '@/features/connection'
import { capabilityMissingMessage } from '@/shared/utils/labels'
import { registeredSourceTypes, sourceRegistration } from './sources/registry'
import { documentSourceTypeLabel } from './sources/sourceLabels'

/**
 * The source types the creation wizard offers, which of them the person may choose (right,
 * release, lock) and the type actually in effect for `chosenType`.
 */
export function useSourceTypeChoice(
  chosenType: SourceTypeKey,
  isMissing: (capability: Capability) => boolean,
) {
  // The tiles are the source types the backend has a connector for (ADR-0038), in the
  // order of the input forms; a type without a form is at the end and cannot be selected.
  const {
    sourceTypes,
    error: sourceTypesError,
    loaded: sourceTypesLoaded,
    reload: reloadSourceTypes,
  } = useSourceTypes()
  const offeredTypes: SourceTypeKey[] = [
    ...registeredSourceTypes.filter((type) => sourceTypes.some((d) => d.type === type)),
    ...sourceTypes.map((d) => d.type).filter((type) => sourceRegistration(type) === undefined),
  ]
  const displayNameOf = (type: SourceTypeKey) =>
    sourceRegistration(type) !== undefined
      ? documentSourceTypeLabel(type)
      : (sourceTypes.find((d) => d.type === type)?.displayName ?? type)
  const acceptsUploads = (type: SourceTypeKey) =>
    sourceTypes.find((d) => d.type === type)?.uploads ?? type === 'UPLOAD'
  const capabilityFor = (type: SourceTypeKey): Capability =>
    acceptsUploads(type) ? 'CREATE_LIBRARY' : 'CREATE_CONNECTOR_LIBRARY'

  /** The reason why this type cannot be chosen here - or `null` when it can. */
  function hasForm(type: SourceTypeKey): boolean {
    const registration = sourceRegistration(type)
    return (
      registration !== undefined && (acceptsUploads(type) || Boolean(registration.configuration))
    )
  }

  function missingFor(type: SourceTypeKey): string | null {
    if (!hasForm(type)) {
      return 'Für diese Quellart gibt es in dieser Oberfläche keine Eingabemaske.'
    }
    const capability = capabilityFor(type)
    if (isMissing(capability)) return capabilityMissingMessage(capability)
    return releaseMissingFor(type)
  }

  /**
   * The connector release applies per type and per profile (ADR-0036): a type the person may create
   * neither with an own address nor through a profile, or a locked one, has no usable way and gets
   * no tile; the backend's notice only explains a choice left without a tile. Which way is open is
   * chosen in the step „Quelle einrichten“.
   */
  function releaseMissingFor(type: SourceTypeKey): string | null {
    const descriptor = sourceTypes.find((d) => d.type === type)
    if (descriptor === undefined || descriptor.uploads) return null
    if (descriptor.creatableWithOwnAddress) return null
    if (descriptor.creatable && descriptor.profileSupport !== 'FORBIDDEN') return null
    return (
      descriptor.creationNotice ??
      `Die Quellart „${descriptor.displayName}“ ist für Sie nicht freigegeben. Freigaben erteilt die Systemverwaltung.`
    )
  }

  const selectableTypes = offeredTypes.filter((type) => missingFor(type) === null)
  /**
   * The tiles: a type with a form shows only while the person may use it - right, release and lock
   * included. A type without a form in this client stays as a locked tile with its reason.
   */
  const shownTypes = offeredTypes.filter((type) => !hasForm(type) || missingFor(type) === null)
  /** Nothing the person may create at all: the step shows no tile, only the hint above it. */
  const nothingUsable =
    sourceTypesLoaded && sourceTypesError == null && selectableTypes.length === 0

  /**
   * The type actually chosen. The creation rights arrive only after the first render; whatever
   * is then disabled must not stay selected, otherwise „Weiter" would lead into a path that can
   * never create. The choice moves to the first permitted tile - derived, not caught up in
   * an effect. If none is permitted at all, it stays, and the hint above the
   * stepper explains why nothing will be created in the end.
   */
  const sourceType =
    (missingFor(chosenType) !== null || !selectableTypes.includes(chosenType)) &&
    selectableTypes.length > 0
      ? selectableTypes[0]
      : chosenType

  return {
    sourceTypes,
    sourceTypesError,
    sourceTypesLoaded,
    reloadSourceTypes,
    displayNameOf,
    acceptsUploads,
    capabilityFor,
    missingFor,
    releaseMissingFor,
    shownTypes,
    nothingUsable,
    sourceType,
  }
}
