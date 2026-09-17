// @ts-check
import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

export default defineConfig({
  site: 'https://supermessage.dev',
  // The sitemap lists what exists; robots.txt points at it. 404 is excluded by
  // the integration itself.
  integrations: [sitemap()],
});
