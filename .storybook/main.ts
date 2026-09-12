import type { StorybookConfig } from "@storybook/sveltekit";

const config: StorybookConfig = {
  framework: "@storybook/sveltekit",

  /**
   * No telemetry.
   *
   * Consistent with the rest of this repository's posture rather than a
   * preference: the app's CSP is `default-src 'self'`, and
   * `pnpm-workspace.yaml` refuses install scripts that reach the network on
   * the grounds that a build step which phones home is not worth running
   * for a capability nobody uses. A catalogue of an unreleased product's UI
   * is not something to report on by default.
   */
  core: { disableTelemetry: true },

  /**
   * Co-located with their component, not gathered into a `stories/`
   * directory. A story that has drifted from its component is then a
   * one-directory problem rather than a search, and the pair moves together
   * when a component moves — which, during an extraction project, is often.
   */
  stories: ["../src/**/*.stories.svelte"],

  addons: [
    "@storybook/addon-svelte-csf",
    /**
     * Runs axe per story. Contrast is already derived and asserted by the
     * token generator, so this is expected to pass on colour — which makes
     * it useful for precisely what it will catch instead: ARIA and
     * focus-order problems in components that have just been pulled apart.
     */
    "@storybook/addon-a11y",
  ],
};

export default config;
