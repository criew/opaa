// node shoot.js OUTDIR uid:theme:file[:height] ...
const { chromium } = require('playwright');
const FROM = 1794144600000, TO = 1794231000000; // 08.11. 14:30 – 09.11. 14:30 Europe/Berlin
(async () => {
  const out = process.argv[2];
  const browser = await chromium.launch();
  for (const spec of process.argv.slice(3)) {
    const [uid, theme, file, h] = spec.split(':');
    const ctx = await browser.newContext({ viewport: { width: 1600, height: Number(h || 1000) }, deviceScaleFactor: 2,
      locale: 'de-DE', timezoneId: 'Europe/Berlin', colorScheme: theme === 'dark' ? 'dark' : 'light' });
    const page = await ctx.newPage();
    await page.goto(`http://localhost:3000/d/${uid}?orgId=1&from=${FROM}&to=${TO}&theme=${theme}&kiosk`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(4000);
    await page.mouse.move(1599, 999);
    await page.screenshot({ path: `${out}/${file}` });
    console.log('shot', file);
    await ctx.close();
  }
  await browser.close();
})();
