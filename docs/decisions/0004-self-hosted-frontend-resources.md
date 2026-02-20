# 4. Self-Hosted Frontend Resources

Date: 2026-02-19

## Status

Accepted

## Context

The OPAA frontend needs fonts (Inter), icons (Material Icons), and potentially other static
assets. These can be loaded from external CDNs (e.g., Google Fonts, cdnjs) or bundled as
npm packages and self-hosted.

Enterprise environments often restrict outbound network access. External CDN dependencies
also raise privacy concerns (e.g., Google Fonts transmitting user IP addresses) and create
a runtime dependency on third-party infrastructure.

## Decision

All fonts, icons, and static assets will be installed as npm packages and bundled with the
application. No external CDN links will be used at runtime.

Specifically:
- **Fonts:** `@fontsource/inter` (npm package, self-hosted)
- **Icons:** `@mui/icons-material` (npm package, bundled)
- **No `<link>` tags** to external services in `index.html`

## Consequences

- **Privacy:** No user data (IP addresses, referrer headers) is sent to third-party CDNs
- **Offline:** The application works fully offline after initial load
- **Enterprise:** Compatible with restrictive network policies and air-gapped environments
- **Bundle size:** Slightly larger initial bundle (fonts included), mitigated by tree-shaking
  for icons and subsetting for fonts
- **Updates:** Font/icon updates require an npm package update rather than being automatic
