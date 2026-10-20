// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLlmsTxt from 'starlight-llms-txt';

export default defineConfig({
  site: 'https://docs.supermessage.dev',
  // Two settings, one cause. The repo root's tsconfig.json extends
  // `.svelte-kit/tsconfig.json`, a file `svelte-kit sync` generates and git does
  // not have. On a machine that has built the desktop app it is there; on a
  // fresh CI checkout it is not, and Vite 8 goes looking for it twice:
  //
  //   - resolving tsconfig paths, where `astro sync` fails with
  //     "Tsconfig not found .svelte-kit/tsconfig.json". This site defines no
  //     path aliases, so turning it off loses nothing — AgentPod's docs needed
  //     only this (SuperJackfruitLabs/agentpod#450).
  //   - rolldown's own per-file tsconfig discovery while building the static
  //     entrypoints, "Failed to load tsconfig '../.svelte-kit/tsconfig.json'".
  //     Pinning it to this site's tsconfig stops the walk at this directory.
  //
  // Both reproduce only in a clean checkout, which is why the site built on every
  // laptop and not in CI.
  vite: {
    resolve: { tsconfigPaths: false },
    build: { rolldownOptions: { tsconfig: './tsconfig.json' } },
  },
  integrations: [
    starlight({
      title: 'SuperMessage',
      description:
        'A Matrix client where the agents you work with are participants, not a panel. ' +
        'Docs for using it across five platforms.',
      // /llms.txt, /llms-full.txt and /llms-small.txt, for agents reading these docs. Every
      // page goes into each file; the small one drops asides and <details> only.
      plugins: [
        starlightLlmsTxt({
          projectName: 'SuperMessage',
          description:
            'SuperMessage is a Matrix client where the agents you work with are participants in ' +
            'the room, not a side panel. It runs on iOS, Android, Windows, macOS and Linux: one Rust ' +
            'core does the Matrix work, with SwiftUI, Compose and a Svelte desktop app rendering it. ' +
            'Agent permission requests and board gates arrive as cards answerable in the room, from a ' +
            'notification or from a widget; a gate is resolved only when the board sends its receipt. ' +
            'Rooms are end-to-end encrypted through the Matrix Rust SDK, and every custom event carries ' +
            'a plain-text fallback so other Matrix clients stay usable.',
          optionalLinks: [
            { label: 'SuperMessage', url: 'https://supermessage.dev', description: 'The product site.' },
            { label: 'Source', url: 'https://github.com/SuperJackfruitLabs/supermessage', description: 'The SuperMessage repository on GitHub.' },
          ],
        }),
      ],
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
            content: 'SuperMessage — chat where your agents are in the room',
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
            { label: 'What SuperMessage is', slug: 'start/what-it-is' },
            { label: 'Concepts', slug: 'start/concepts' },
          ],
        },
        {
          label: 'Use it',
          items: [
            { label: 'Rooms and spaces', slug: 'use/rooms' },
            { label: 'Messages', slug: 'use/messages' },
            { label: 'Working with agents', slug: 'use/agents' },
            { label: 'Notifications', slug: 'use/notifications' },
            { label: 'Widgets and the Lock Screen', slug: 'use/widgets' },
            { label: 'Encryption', slug: 'use/encryption' },
          ],
        },
      ],
    }),
  ],
});
