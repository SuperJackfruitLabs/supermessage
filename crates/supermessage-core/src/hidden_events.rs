//! The events the reader hid on this device (reported messages and the like).
//!
//! A host keeps the ids on the device and pushes the whole set whenever it
//! changes ([`HiddenEvents::replace`]). The core marks those rows
//! `hidden` and groups the list as if they were absent, so the run, quote and
//! membership fields a host reads are right for the rows it actually draws.
//!
//! Rows stay in the diff stream (positions are the SDK's, and an unhidden row
//! must come back in place); a host skips a row whose `hidden` is set.
//!
//! The set belongs to the account: it is cleared on login, restore and logout
//! like [`crate::sent_rooms::SentRooms`].

use std::collections::HashSet;
use std::sync::Mutex;

use tokio::sync::Notify;

#[derive(Default)]
pub struct HiddenEvents {
    ids: Mutex<HashSet<String>>,
    /// Wakes the focused timeline's task so it re-settles after a change.
    changed: Notify,
}

impl HiddenEvents {
    /// Replace the whole set and wake the focused timeline.
    pub fn replace(&self, ids: Vec<String>) {
        *self.ids.lock().expect("hidden events lock poisoned") = ids.into_iter().collect();
        self.changed.notify_one();
    }

    /// Forget every id (the account changed).
    pub fn clear(&self) {
        self.ids
            .lock()
            .expect("hidden events lock poisoned")
            .clear();
        self.changed.notify_one();
    }

    pub fn contains(&self, event_id: &str) -> bool {
        self.ids
            .lock()
            .expect("hidden events lock poisoned")
            .contains(event_id)
    }

    /// Resolves once the set changed since the last wait.
    pub(crate) async fn changed(&self) {
        self.changed.notified().await;
    }

    /// Set `hidden` on every row from the set; the indices that flipped.
    pub(crate) fn apply(&self, rows: &mut [crate::dto::TimelineRow]) -> Vec<usize> {
        let ids = self.ids.lock().expect("hidden events lock poisoned");
        let mut flipped = Vec::new();
        for (index, row) in rows.iter_mut().enumerate() {
            let hidden = row
                .item
                .event_id
                .as_deref()
                .is_some_and(|id| ids.contains(id));
            if row.hidden != hidden {
                row.hidden = hidden;
                flipped.push(index);
            }
        }
        flipped
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Mutation seen failing: `replace` merging into the set instead of
    /// replacing it leaves "$old" contained.
    #[test]
    fn replace_is_the_whole_set() {
        let hidden = HiddenEvents::default();
        hidden.replace(vec!["$old".into()]);
        hidden.replace(vec!["$new".into()]);
        assert!(!hidden.contains("$old"));
        assert!(hidden.contains("$new"));
    }

    /// Mutation seen failing: dropping `notify_one` from `replace` makes
    /// `changed` time out.
    #[tokio::test]
    async fn a_change_wakes_the_timeline_task_even_before_it_waits() {
        let hidden = HiddenEvents::default();
        hidden.replace(vec!["$a".into()]);
        tokio::time::timeout(std::time::Duration::from_secs(1), hidden.changed())
            .await
            .expect("the stored permit wakes the next wait");
    }
}
