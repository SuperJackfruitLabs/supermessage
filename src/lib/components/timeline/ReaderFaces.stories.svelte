<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import ReaderFaces from "./ReaderFaces.svelte";
  import type { ReaderFace } from "./rowMeta";

  const { Story } = defineMeta({ title: "Timeline/ReaderFaces", component: ReaderFaces });

  const people: ReaderFace[] = ["A", "B", "C", "D", "E"].map((initial) => ({
    userId: `@${initial.toLowerCase()}:example.org`,
    mxcUri: null,
    initial,
  }));
  const faceFor = () => null;
</script>

<!-- Initials only: the avatar fetch is the app's, not the component's. -->
<Story name="One reader" args={{ readers: people.slice(0, 1), label: null, faceFor }} />
<Story name="Three readers" args={{ readers: people.slice(0, 3), label: null, faceFor }} />
<Story name="Five readers (+2)" args={{ readers: people, label: null, faceFor }} />
<!-- The core has named three of the five people in the room. -->
<Story
  name="Read by three of five"
  args={{ readers: people.slice(0, 3), label: "Read by Ana, Ben and Cy", faceFor }}
  play={async ({ canvasElement }) => {
    const faces = canvasElement.querySelector("[role='img']");
    if (faces?.getAttribute("aria-label") !== "Read by Ana, Ben and Cy")
      throw new Error(`the label was ${faces?.getAttribute("aria-label")}`);
  }}
/>
