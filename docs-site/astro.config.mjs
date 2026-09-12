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
