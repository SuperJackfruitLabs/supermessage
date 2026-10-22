// Who is signed in, for the Account panel.
import { account as defaultAccount, peopleLabel as defaultPeopleLabel, type AccountDto } from "$lib/ipc";

export interface AccountStoreDeps {
  account: typeof defaultAccount;
  peopleLabel: typeof defaultPeopleLabel;
}

export function createAccountStore(
  deps: AccountStoreDeps = { account: defaultAccount, peopleLabel: defaultPeopleLabel },
) {
  let account = $state<AccountDto | null>(null);
  let name = $state<string | null>(null);
  /** Bumped by `clear`, so a load that was in flight when it ran cannot repopulate the store. */
  let generation = 0;

  return {
    get account(): AccountDto | null {
      return account;
    },
    /** The name the core gives this account (`people_label`), never split from the id here. */
    get name(): string | null {
      return name;
    },
    /** Asks the core who is signed in. A failure leaves both `null`. */
    async load(): Promise<void> {
      const started = generation;
      try {
        const loaded = await deps.account();
        const label = await deps.peopleLabel([loaded.userId]);
        if (generation !== started) return;
        account = loaded;
        name = label;
      } catch {
        if (generation !== started) return;
        account = null;
        name = null;
      }
    },
    clear(): void {
      generation += 1;
      account = null;
      name = null;
    },
  };
}

export const accountStore = createAccountStore();
