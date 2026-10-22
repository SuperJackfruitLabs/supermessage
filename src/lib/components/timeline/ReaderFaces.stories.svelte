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
<Story name="One reader" args={{ readers: people.slice(0, 1), faceFor }} />
<Story name="Three readers" args={{ readers: people.slice(0, 3), faceFor }} />
<Story name="Five readers (+2)" args={{ readers: people, faceFor }} />
