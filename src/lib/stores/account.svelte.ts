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
      try {
        const loaded = await deps.account();
        const label = await deps.peopleLabel([loaded.userId]);
        account = loaded;
        name = label;
      } catch {
        account = null;
        name = null;
      }
    },
    clear(): void {
      account = null;
      name = null;
    },
  };
}

export const accountStore = createAccountStore();
