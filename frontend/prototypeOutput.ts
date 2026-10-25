import type { Plugin } from 'vite'

export const PROTOTYPE_TITLE = 'OPAA-Prototyp'

/**
 * Rewrites every non-ASCII UTF-16 code unit in JavaScript source as a `\uXXXX` escape, which
 * means the same inside strings, templates, regular expressions and comments. A backslash that
 * escaped the character itself (an identity escape) is dropped; the `\uXXXX` escape replaces it.
 */
export function escapeNonAsciiJs(code: string): string {
  return code.replace(/(\\*)([\u0080-\uffff])/g, (_, slashes: string, char: string) => {
    const kept = slashes.length % 2 === 1 ? slashes.slice(1) : slashes
    return `${kept}\\u${char.charCodeAt(0).toString(16).padStart(4, '0')}`
  })
}

/**
 * Output of the static prototype: pure-ASCII scripts and its own page title. Static hosts such as
 * claude.ai artifacts reject files with certain raw characters (U+FFFD among them).
 */
export function prototypeOutput(): Plugin {
  return {
    name: 'opaa-prototype-output',
    apply: 'build',
    transformIndexHtml: (html) =>
      html.replace(/<title>[^<]*<\/title>/, `<title>${PROTOTYPE_TITLE}</title>`),
    // After minification, which would otherwise write the characters back raw.
    enforce: 'post',
    generateBundle(_, bundle) {
      for (const file of Object.values(bundle)) {
        if (file.type === 'chunk') file.code = escapeNonAsciiJs(file.code)
      }
    },
  }
}
