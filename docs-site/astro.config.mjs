// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import sjlDocs from '@superjackfruit/docs-theme';

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
      // The suite's docs theme: vibekit's violet, the faces, the street, status badges, the footers,
      // the 404, the edit link and llms.txt. See @superjackfruit/docs-theme's README.
      plugins: [
        sjlDocs({
          product: 'supermessage',
          // "Early preview", as the home and "What SuperMessage is" say: unsigned desktop
          // previews and TestFlight by invitation, no app-store release.
          status: 'wet paint',
          // /llms.txt, /llms-full.txt and /llms-small.txt, for agents reading these docs. Every
          // page goes into each file; the small one drops asides and <details> only.
          llms: {
            description:
              'SuperMessage is a Matrix client where the agents you work with are participants in ' +
              'the room, not a side panel. It runs on iOS, Android, Windows, macOS and Linux: one Rust ' +
              'core does the Matrix work, with SwiftUI, Compose and a Svelte desktop app rendering it. ' +
              'Agent permission requests and board gates arrive as cards answerable in the room, from a ' +
              'notification or from a widget; a gate is resolved only when the board sends its receipt. ' +
              'Rooms are end-to-end encrypted through the Matrix Rust SDK, and every custom event carries ' +
              'a plain-text fallback so other Matrix clients stay usable.',
          },
        }),
      ],
      favicon: '/favicon.svg',
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
      ],
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/SuperJackfruitLabs/supermessage',
        },
      ],
      // The suite's groups, in the order a reader needs them. This site has pages for two of the
      // five (Start, Use it); the theme lets a site leave the others out.
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
