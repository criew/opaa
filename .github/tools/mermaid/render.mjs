// Renders Mermaid diagrams to SVG in a headless Chromium, without network access:
//   node render.mjs <in.json> <out.json>
// <in.json> holds a list of {id, source}; <out.json> gets a list of {id, svg} or {id, error} in the
// same order. The browser is $CHROMIUM if set, otherwise the installed Google Chrome.
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const [input, output] = process.argv.slice(2);
if (!input || !output) {
  console.error('usage: node render.mjs <in.json> <out.json>');
  process.exit(2);
}
const diagrams = JSON.parse(await readFile(input, 'utf-8'));
const browser = await chromium.launch(
  process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : { channel: 'chrome' },
);
try {
  const page = await browser.newPage();
  await page.route('**/*', (route) => route.abort());
  await page.setContent('<!doctype html><html><body></body></html>');
  await page.addScriptTag({
    path: fileURLToPath(import.meta.resolve('mermaid/dist/mermaid.min.js')),
  });
  const results = await page.evaluate(async (list) => {
    // Plain SVG text instead of HTML in foreignObject, so the result also prints and shows in
    // viewers without HTML support; strict keeps scripts and links out of the labels.
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'default',
      htmlLabels: false,
      flowchart: { htmlLabels: false },
      deterministicIds: true,
    });
    const rendered = [];
    for (const { id, source } of list) {
      try {
        const { svg } = await mermaid.render(id, source);
        rendered.push({ id, svg });
      } catch (error) {
        rendered.push({ id, error: String(error?.message ?? error) });
      }
    }
    return rendered;
  }, diagrams);
  await writeFile(output, JSON.stringify(results), 'utf-8');
} finally {
  await browser.close();
}
