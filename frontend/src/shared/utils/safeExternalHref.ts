export function safeExternalHref(url: string | null | undefined): string | undefined {
  return url ?? undefined
}
