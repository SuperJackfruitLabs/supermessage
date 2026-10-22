<script lang="ts" module>
  import {
    ACCENTS,
    ACCENT_LABELS,
    DARK_STYLES,
    DARK_STYLE_LABELS,
    MODES,
    MODE_LABELS,
    TEXT_SIZES,
    TEXT_SIZE_LABELS,
    type AppearancePrefs,
  } from "$lib/appearance";
  import type { AccountDto, RoomMember } from "$lib/ipc";
  import {
    ACCOUNT_LINKS,
    APPEARANCE_FOOTER,
    BLOCKED_FOOTER,
    REPORT_FOOTER,
    SIGN_OUT_FOOTER,
    blockedSectionShown,
    versionLine,
  } from "./accountView";

  /**
   * Account (iOS `AccountPanel.swift`): who is signed in, appearance, text size, the blocked
   * list (only when someone is in it), links, encryption recovery and sign out.
   *
   * Callbacks rather than stores or IPC so a story can drive it with no Tauri
   * host. Sign out asks first (`SignOutConfirm`); the page's `onSignOut` does
   * the signing out.
   */
  export interface Props {
    version: string | null;
    prefs: AppearancePrefs;
    onChange: (patch: Partial<AppearancePrefs>) => void;
    onOpenRecovery: () => void;
    onSignOut: () => Promise<void>;
    onOpenLink: (url: string) => void;
    onClose: () => void;
    /** Who is signed in and where; `null` while unknown. */
    account: AccountDto | null;
    /** The core's name for this account (`people_label`); `null` while unknown. */
    accountName: string | null;
    /** Everyone this account has blocked; `null` when it could not be read. */
    blocked: RoomMember[] | null;
    /** Unblocks one person. Resolves to the refusal text, or `null` when it landed. */
    onUnblock: (userId: string) => Promise<string | null>;
  }
</script>

<script lang="ts">
  import { onMount } from "svelte";
  import { appearanceAttributes } from "$lib/appearance";
  import SignOutConfirm from "./SignOutConfirm.svelte";
  import MemberRow from "./roster/MemberRow.svelte";
  import { initial } from "./roomInfoView";

  let {
    version,
    prefs,
    onChange,
    onOpenRecovery,
    onSignOut,
    onOpenLink,
    onClose,
    account,
    accountName,
    blocked,
    onUnblock,
  }: Props = $props();

  /** A refused unblock, said in place of the footer. */
  let unblockFailure = $state<string | null>(null);
  let unblocking = $state<string | null>(null);

  async function unblockOne(userId: string): Promise<void> {
    if (unblocking !== null) return;
    unblocking = userId;
    unblockFailure = null;
    try {
      unblockFailure = await onUnblock(userId);
    } catch {
      unblockFailure = "That didn't work.";
    } finally {
      unblocking = null;
    }
  }

  let confirming = $state(false);
  let panel = $state<HTMLElement | null>(null);

  const systemDark = () =>
    typeof matchMedia === "function" && matchMedia("(prefers-color-scheme: dark)").matches;

  /**
   * What the swatches are drawn in: the appearance the chosen mode produces
   * now, so `bg-accent` on a swatch is that accent's own token in it.
   */
  const resolved = $derived.by(() => {
    const dark = systemDark();
    return appearanceAttributes(prefs, dark).appearance ?? (dark ? "dark" : "light");
  });

  onMount(() => {
    const opener = document.activeElement;
    panel?.focus();
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  });

  /** Arrow keys move a radio group's selection (and focus) like a native one. */
  function arrows<T>(values: readonly T[], current: T, choose: (v: T) => void) {
    return (e: KeyboardEvent) => {
      const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key];
      if (step === undefined) return;
      e.preventDefault();
      e.stopPropagation();
      const next = values[(values.indexOf(current) + step + values.length) % values.length];
      choose(next);
      const group = (e.currentTarget as HTMLElement).closest("[role='radiogroup']")!;
      queueMicrotask(() =>
        group.querySelectorAll<HTMLElement>("[role='radio']")[values.indexOf(next)]?.focus(),
      );
    };
  }

  const segment = (selected: boolean) =>
    `rounded-control border px-3 py-1 text-ui transition-colors ${
      selected
        ? "border-accent bg-surface-sunken text-content"
        : "border-border text-content-muted hover:bg-surface-sunken"
    }`;
</script>

<div class="fixed inset-0 z-50 flex justify-end">
  <button
    type="button"
    aria-label="Close account"
    class="absolute inset-0 bg-scrim"
    onclick={onClose}
  ></button>

  <div
    bind:this={panel}
    role="dialog"
    tabindex="-1"
    aria-label="Account"
    class="relative z-10 flex h-full w-full max-w-sm flex-col gap-5 overflow-y-auto border-l border-border bg-surface p-4 shadow-overlay"
    onkeydown={(e: KeyboardEvent) => {
      if (e.key === "Escape") {
        // Consumed here: the page's window handler must not also close the
        // room-info panel underneath on this same keypress.
        e.preventDefault();
        onClose();
      }
    }}
  >
    <header class="flex items-center justify-between">
      <h2 class="text-ui font-medium text-content">Account</h2>
      <button
        type="button"
        aria-label="Close"
        onclick={onClose}
        class="rounded-control px-2 py-1 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
      >
        ✕
      </button>
    </header>

    <section class="flex items-center gap-3" aria-label="Signed in as">
      <span
        aria-hidden="true"
        class="flex size-11 shrink-0 items-center justify-center rounded-pill bg-surface-raised text-body font-medium text-content"
      >
        {initial(accountName ?? "?")}
      </span>
      <span class="min-w-0 flex-1">
        <span class="selectable block truncate text-body font-medium text-content">
          {accountName ?? "Signed in"}
        </span>
        {#if account}
          <span class="selectable block truncate font-mono text-meta text-content-muted">
            {account.userId}
          </span>
        {/if}
      </span>
    </section>
    {#if account}
      <section class="-mt-3 flex flex-col gap-0.5 px-1" aria-label="Homeserver">
        <h3 class="text-meta font-medium text-content-muted">Homeserver</h3>
        <p class="selectable break-all text-ui text-content">{account.homeserver}</p>
      </section>
    {/if}

    <section class="flex flex-col gap-2" aria-labelledby="account-appearance">
      <h3 id="account-appearance" class="text-meta font-medium text-content-muted">Appearance</h3>
      <div role="radiogroup" aria-label="Appearance" class="flex flex-wrap gap-1">
        {#each MODES as mode (mode)}
          <button
            type="button"
            role="radio"
            aria-checked={prefs.mode === mode}
            tabindex={prefs.mode === mode ? 0 : -1}
            onclick={() => onChange({ mode })}
            onkeydown={arrows(MODES, prefs.mode, (v) => onChange({ mode: v }))}
            class={segment(prefs.mode === mode)}
          >
            {MODE_LABELS[mode]}
          </button>
        {/each}
      </div>
      <div role="radiogroup" aria-label="Dark style" class="flex flex-wrap gap-1">
        {#each DARK_STYLES as style (style)}
          <button
            type="button"
            role="radio"
            aria-checked={prefs.darkStyle === style}
            tabindex={prefs.darkStyle === style ? 0 : -1}
            onclick={() => onChange({ darkStyle: style })}
            onkeydown={arrows(DARK_STYLES, prefs.darkStyle, (v) => onChange({ darkStyle: v }))}
            class={segment(prefs.darkStyle === style)}
          >
            {DARK_STYLE_LABELS[style]}
          </button>
        {/each}
      </div>
      <div role="radiogroup" aria-label="Accent" class="flex flex-wrap gap-2 py-1">
        {#each ACCENTS as accent (accent)}
          <!--
            The ring is the page's, outside the swatch: inside it, `content` and
            `border` would be the previewed palette's, and a Black swatch's
            selection ring would vanish on a light panel.
          -->
          <span
            class="rounded-full p-0.5 ring-2 {prefs.accent === accent
              ? 'ring-content'
              : 'ring-transparent'}"
          >
            <button
              type="button"
              role="radio"
              aria-checked={prefs.accent === accent}
              aria-label={ACCENT_LABELS[accent]}
              title={ACCENT_LABELS[accent]}
              tabindex={prefs.accent === accent ? 0 : -1}
              data-appearance={resolved}
              data-accent={accent || undefined}
              onclick={() => onChange({ accent })}
              onkeydown={arrows(ACCENTS, prefs.accent, (v) => onChange({ accent: v }))}
              class="block size-6 rounded-full bg-accent"
            ></button>
          </span>
        {/each}
      </div>
      <p class="text-meta text-content-faint">{APPEARANCE_FOOTER}</p>
    </section>

    <section class="flex flex-col gap-2" aria-labelledby="account-text-size">
      <h3 id="account-text-size" class="text-meta font-medium text-content-muted">Text size</h3>
      <div role="radiogroup" aria-label="Text size" class="flex flex-wrap gap-1">
        {#each TEXT_SIZES as size (size)}
          <button
            type="button"
            role="radio"
            aria-checked={prefs.textSize === size}
            tabindex={prefs.textSize === size ? 0 : -1}
            onclick={() => onChange({ textSize: size })}
            onkeydown={arrows(TEXT_SIZES, prefs.textSize, (v) => onChange({ textSize: v }))}
            class={segment(prefs.textSize === size)}
          >
            {TEXT_SIZE_LABELS[size]}
          </button>
        {/each}
      </div>
    </section>

    {#if blockedSectionShown(blocked)}
      <section class="flex flex-col gap-2" aria-labelledby="account-blocked">
        <h3 id="account-blocked" class="text-meta font-medium text-content-muted">Blocked</h3>
        <ul class="flex flex-col gap-2">
          {#each blocked ?? [] as member (member.userId)}
            <MemberRow
              {member}
              avatarUrl={null}
              onAvatarFailed={() => {}}
              onUnblock={() => void unblockOne(member.userId)}
              unblocking={unblocking === member.userId}
            />
          {/each}
        </ul>
        {#if unblockFailure !== null}
          <p role="alert" class="text-meta text-danger">{unblockFailure}</p>
        {:else}
          <p class="text-meta text-content-faint">{BLOCKED_FOOTER}</p>
        {/if}
      </section>
    {/if}

    <section class="flex flex-col gap-1" aria-label="Links">
      {#each ACCOUNT_LINKS as link (link.url)}
        <button
          type="button"
          onclick={() => onOpenLink(link.url)}
          class="rounded-control px-3 py-2 text-left text-ui text-content transition-colors hover:bg-surface-sunken"
        >
          {link.label}
        </button>
      {/each}
      <p class="px-3 pt-1 text-meta text-content-faint">{REPORT_FOOTER}</p>
    </section>

    <section class="flex flex-col">
      <button
        type="button"
        onclick={onOpenRecovery}
        class="rounded-control px-3 py-2 text-left text-ui text-content transition-colors hover:bg-surface-sunken"
      >
        Encryption recovery
      </button>
    </section>

    <section class="flex flex-col gap-1">
      <button
        type="button"
        onclick={() => (confirming = true)}
        class="rounded-control px-3 py-2 text-left text-ui text-danger transition-colors hover:bg-surface-sunken"
      >
        Sign out
      </button>
      <p class="px-3 text-meta text-content-faint">{SIGN_OUT_FOOTER}</p>
    </section>

    {#if versionLine(version) !== null}
      <p class="mt-auto px-3 text-meta text-content-faint">{versionLine(version)}</p>
    {/if}
  </div>
</div>

{#if confirming}
  <SignOutConfirm accountName={accountName} onConfirm={onSignOut} onCancel={() => (confirming = false)} />
{/if}
