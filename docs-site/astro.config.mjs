// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  site: 'https://docs.supermessage.dev',
  integrations: [
    starlight({
      title: 'supermessage',
      description:
        'A Matrix client where the agents you work with are participants, not a panel. ' +
        'Docs for using it across five platforms.',
      // Two-tone wordmark, matching the landing page's brand exactly.
      components: { SiteTitle: './src/components/SiteTitle.astro' },
      // Shared with the landing page — see src/styles/theme.css.
      customCss: ['./src/styles/theme.css'],
      head: [
        // The link preview. Starlight already declares `twitter:card` as
        // summary_large_image and then names no image, so a pasted link showed
        // a bare title card. og.png is rendered from landing/og/og.html — see
        // the README there — and copied here so the docs host serves its own.
        // Absolute URLs: several unfurlers ignore a relative og:image.
        {
          tag: 'meta',
          attrs: { property: 'og:image', content: 'https://docs.supermessage.dev/og.png' },
        },
        { tag: 'meta', attrs: { property: 'og:image:width', content: '1200' } },
        { tag: 'meta', attrs: { property: 'og:image:height', content: '630' } },
        {
          tag: 'meta',
          attrs: {
            property: 'og:image:alt',
            content: 'supermessage — chat where your agents are in the room',
          },
        },
        {
          tag: 'meta',
          attrs: { name: 'twitter:image', content: 'https://docs.supermessage.dev/og.png' },
        },
        {
          tag: 'link',
          attrs: { rel: 'preconnect', href: 'https://fonts.googleapis.com' },
        },
        {
          tag: 'link',
          attrs: { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: true },
        },
        {
          tag: 'link',
          attrs: {
            rel: 'stylesheet',
            href:
              'https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,400;12..96,600;12..96,800' +
              '&family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans:wght@400;500;600&display=swap',
          },
        },
      ],
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/SuperJackfruitLabs/supermessage',
        },
      ],
      sidebar: [
        {
          label: 'Start',
          items: [
            { label: 'What supermessage is', slug: 'start/what-it-is' },
            { label: 'Concepts', slug: 'start/concepts' },
          ],
        },
        {
          label: 'Use it',
          items: [
            { label: 'Rooms and spaces', slug: 'use/rooms' },
            { label: 'Messages', slug: 'use/messages' },
            { label: 'Working with agents', slug: 'use/agents' },
            { label: 'Encryption', slug: 'use/encryption' },
          ],
        },
      ],
    }),
  ],
});
