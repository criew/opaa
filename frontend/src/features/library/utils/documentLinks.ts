import { safeExternalHref } from '@/shared/utils/safeExternalHref'

/**
 * Makes the links of a rendered original safe: an absolute http(s) target stays and opens apart
 * from the app, without opener or referrer; every other target - `javascript:`, `data:`, relative
 * and in-document ones - loses its `href` and stays plain text. Also removes `target` and other
 * navigation attributes a document may carry, and SVG links (`xlink:href` on `<a>`).
 */
export function neutralizeLinks(root: ParentNode) {
  root.querySelectorAll('a').forEach((link) => {
    const safe = safeExternalHref(link.getAttribute('href'))
    for (const name of ['href', 'xlink:href', 'target', 'rel', 'ping', 'download']) {
      link.removeAttribute(name)
    }
    link.removeAttributeNS('http://www.w3.org/1999/xlink', 'href')
    if (!safe) return
    link.setAttribute('href', safe)
    link.setAttribute('target', '_blank')
    link.setAttribute('rel', 'noopener noreferrer')
  })
}
