// Run after `astro build`: the suite frame must be in the static HTML, the old masthead gone.
import { readFileSync } from 'node:fs';
const html = readFileSync(new URL('../dist/index.html', import.meta.url), 'utf8');
const fails = [];
for (const tag of ['<sjl-suite-bar', '<sjl-product-header', '<sjl-claims', '<sjl-street']) {
  if (!html.includes(tag)) fails.push(`missing ${tag}`);
}
if (/<header class="?mast/.test(html)) fails.push('old masthead <header class="mast"> still present');
if (html.includes('x-sjl-preview')) fails.push('x-sjl-preview present');
if (fails.length) { console.error('check-frame FAIL:\n  ' + fails.join('\n  ')); process.exit(1); }
console.log('check-frame OK');
