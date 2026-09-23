<script lang="ts">
  /**
   * The quiet machine log.
   *
   * Membership changes (grouped or not), room creation, encryption enabled,
   * room replaced, and every placeholder for something this build cannot
   * render yet. All of these are the same row, and keeping them **literally
   * identical is the point**: a collapsed membership run must read no
   * differently from an ungrouped one, and a placeholder must read as part
   * of the same log rather than as a failed message.
   *
   * Sans like everything else said (docs/design-language.md §1) — a log
   * line is prose about the room, not machine output — and never italic.
   *
   * `text` is NOT an app-authored constant. A system line is built from the
   * sender's own unbounded display name, and a placeholder interpolates a
   * sender-controlled msgtype. Before the three-part guard below
   * (`min-w-0` + `max-w` + `break-words`) a single 5000-character display
   * name pushed the scroller's `scrollWidth` to 16515px against a 1563px
   * column. `break-words` alone is not enough: `overflow-wrap: break-word`
   * does not reduce an element's min-content size, so a flex item's
   * automatic minimum still holds the row open until `min-w-0` lets it
   * shrink.
   */
  export interface Props {
    text: string;
  }

  let { text }: Props = $props();
</script>

<!--
  The quiet machine log: membership changes (grouped or not), room
  creation, encryption enabled, room replaced, and every placeholder for
  something this build cannot render yet. All of these are the same row —
  centred, sans `--text-meta`, `--color-content-faint` — and they were
  three verbatim copies of this markup before this snippet existed.
  Keeping them literally identical is the point, not an accident: a
  collapsed membership run must read no differently from an ungrouped
  one, and a placeholder must read as part of the same log rather than as
  a failed message. Sans, one voice (docs/design-language.md §1), and
  never italic.

  `min-w-0` + `max-w` + `break-words`, the same three-part guard every
  other sender-controlled string in this file carries. These strings are
  not app-authored constants: a system line is built from
  `attributedName`, which is the sender's own *unbounded* display name,
  and a placeholder interpolates a sender-controlled `msgtype`/`detail`.
  Before the guard, a single 5000-character display name pushed the
  scroller's own `scrollWidth` to 16515px against a 1563px column.
  `break-words` alone is not enough — `overflow-wrap: break-word` does
  not reduce an element's min-content size, so a flex item's automatic
  minimum size still holds the row open until `min-w-0` lets it shrink.
-->
<div class="flex justify-center py-2">
  <span
    class="min-w-0 max-w-[68ch] text-center font-sans text-meta break-words text-content-faint"
    >{text}</span
  >
</div>
