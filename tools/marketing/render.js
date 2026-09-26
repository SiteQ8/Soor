// Renders every marketing image from tools/marketing/promo.html into marketing/.
//   node tools/marketing/render.js
// Each image is a real screenshot of the app inside the identity: exact sizes,
// square 1080x1080 for posts, story 1080x1920, wide 1920x1080 for covers.
const { chromium } = require('/home/claude/.npm-global/lib/node_modules/playwright');
const path = require('path');
const fs = require('fs');
const sizes = { square: [1080, 1080], story: [1080, 1920], wide: [1920, 1080] };
const plan = [
  ['hero', ['square', 'story', 'wide']], ['month', ['square', 'story', 'wide']],
  ['devices', ['square', 'story']], ['network', ['square', 'story']], ['exposed', ['square', 'story']], ['fix', ['square']], ['privacy', ['square']], ['both', ['square', 'wide']],
];
(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell', args: ['--no-sandbox'] });
  const tpl = 'file://' + path.resolve(__dirname, 'promo.html');
  let n = 0;
  for (const lang of ['ar', 'en']) {
    const dir = path.resolve(__dirname, '../../marketing', lang);
    fs.mkdirSync(dir, { recursive: true });
    for (const [scene, list] of plan) for (const size of list) {
      const [w, h] = sizes[size];
      const p = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
      await p.goto(`${tpl}?scene=${scene}&lang=${lang}&size=${size}`);
      await p.evaluate(() => document.fonts.ready);
      await p.evaluate(() => Promise.all([...document.images].map(i => i.complete ? null : new Promise(r => { i.onload = i.onerror = r; }))));
      await p.waitForTimeout(250);
      await p.screenshot({ path: path.join(dir, `${scene}-${size}.png`) });
      await p.close(); n++;
    }
  }
  await b.close();
  console.log('rendered', n, 'images');
})();
