import type { Preview } from "@storybook/sveltekit";

/**
 * The REAL generated stylesheet, not a copy.
 *
 * `app.css` imports `src/lib/tokens.css`, which is generated from
 * `design/tokens.toml`. So a palette change changes this catalogue, and
 * there is no second set of values that could drift from the app's. That
 * matters more than it sounds: a catalogue showing colours the app does not
 * use is worse than no catalogue, because it is convincing.
 */
import "../src/app.css";

const preview: Preview = {
  parameters: {
    layout: "centered",
  },

  /**
   * The appearance control.
   *
   * This works only because the token emitter carries an explicit
   * `[data-appearance]` selector for each of the three appearances. Before
   * that, dark was reachable only through `@media (prefers-color-scheme:
   * dark)` — so this toolbar could have switched to paper and nothing else,
   * and on a dark-set machine light would have been unreachable.
   *
   * The alternative was for Storybook to fake the theme with its own CSS,
   * which would mean the catalogue showing something the application cannot
   * produce.
   */
  globalTypes: {
    appearance: {
      description: "Which appearance to render",
      toolbar: {
        title: "Appearance",
        icon: "paintbrush",
        items: [
          { value: "light", title: "Light — desktop" },
          { value: "dark", title: "Dark" },
          { value: "paper", title: "Paper — mobile" },
        ],
        dynamicTitle: true,
      },
    },
  },

  initialGlobals: {
    appearance: "light",
  },

  decorators: [
    (story, context) => {
      const appearance = context.globals.appearance ?? "light";
      const root = document.documentElement;
      root.setAttribute("data-appearance", appearance);
      // The preview iframe's own ground. Without this the story floats on
      // the browser default and every surface token is judged against the
      // wrong backdrop — which is how a contrast problem hides.
      document.body.style.background = "var(--color-surface-sunken)";
      document.body.style.color = "var(--color-content)";
      return story();
    },
  ],
};

export default preview;
