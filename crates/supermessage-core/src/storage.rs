//! Moving the stores to where a second process can reach them.
//!
//! On iOS the app kept its SQLite stores in its own container, which the
//! Notification Service Extension cannot see. The extension needs the same
//! crypto store to decrypt a message and the same state store to name its
//! room, so the stores move to the App Group container both processes share.
//! Moving, not copying: two copies of a crypto store diverge the first time
//! either one ratchets a session, and the older one then fails to decrypt.
//!
//! A person who updates must stay signed in, so this runs once, before any
//! client is built, and it has to survive being interrupted: iOS can kill an
//! app at launch for taking too long, and the move is the slow part.

use std::path::Path;

use crate::error::{CoreError, CoreResult};

/// What a move did, for the log and the tests.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct MoveOutcome {
    /// Entries now at the destination that were not before.
    pub moved: usize,
    /// Entries left behind because the destination already had one of that
    /// name — never overwritten, never deleted.
    pub kept: usize,
}

/// Move everything in `from` into `to`, then remove `from` if it is empty.
///
/// - `from` absent → nothing to do (every launch after the first).
/// - `to` absent → one `rename` of the whole directory, which is atomic: it
///   either happened or it did not.
/// - both present → a move that was interrupted, or one being retried. Each
///   entry is renamed on its own, and an entry `to` already has is left where
///   it is: SQLite's `-wal` beside a `.sqlite3` that already moved is exactly
///   the partial state this finishes, and a name present in both is data this
///   will not choose between.
///
/// A rename across volumes falls back to copy-then-delete, for a file; the
/// App Group and the app container are one volume on iOS, so that path is
/// insurance rather than the plan.
pub fn move_dir(from: &Path, to: &Path) -> CoreResult<MoveOutcome> {
    if !from.exists() {
        return Ok(MoveOutcome::default());
    }
    if !to.exists() {
        if let Some(parent) = to.parent() {
            std::fs::create_dir_all(parent).map_err(store_error)?;
        }
        if std::fs::rename(from, to).is_ok() {
            let moved = std::fs::read_dir(to).map_err(store_error)?.count();
            return Ok(MoveOutcome { moved, kept: 0 });
        }
        std::fs::create_dir_all(to).map_err(store_error)?;
    }

    let mut outcome = MoveOutcome::default();
    for entry in std::fs::read_dir(from).map_err(store_error)? {
        let entry = entry.map_err(store_error)?;
        let destination = to.join(entry.file_name());
        if destination.exists() {
            outcome.kept += 1;
            continue;
        }
        move_entry(&entry.path(), &destination)?;
        outcome.moved += 1;
    }
    if outcome.kept == 0 {
        // Empty now; a failure here only leaves an empty directory behind.
        let _ = std::fs::remove_dir(from);
    }
    Ok(outcome)
}

fn move_entry(from: &Path, to: &Path) -> CoreResult<()> {
    match std::fs::rename(from, to) {
        Ok(()) => Ok(()),
        Err(_) if from.is_file() => {
            // Copied under a temporary name and renamed into place, so an
            // interruption never leaves a truncated file under the real name
            // for the next run to mistake for a finished one.
            let partial = to.with_extension("moving");
            std::fs::copy(from, &partial).map_err(store_error)?;
            std::fs::rename(&partial, to).map_err(store_error)?;
            std::fs::remove_file(from).map_err(store_error)
        }
        Err(e) => Err(store_error(e)),
    }
}

fn store_error(e: std::io::Error) -> CoreError {
    CoreError::Store(e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("sm-storage-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write(dir: &Path, name: &str, contents: &str) {
        fs::create_dir_all(dir).unwrap();
        fs::write(dir.join(name), contents).unwrap();
    }

    fn read(dir: &Path, name: &str) -> Option<String> {
        fs::read_to_string(dir.join(name)).ok()
    }

    #[test]
    fn a_store_moves_whole_and_the_old_place_is_gone() {
        let root = scratch("whole");
        let (from, to) = (root.join("app/store"), root.join("group/store"));
        write(&from, "matrix-sdk-state.sqlite3", "state");
        write(&from, "matrix-sdk-crypto.sqlite3", "crypto");

        let outcome = move_dir(&from, &to).unwrap();
        assert_eq!(outcome, MoveOutcome { moved: 2, kept: 0 });
        assert_eq!(
            read(&to, "matrix-sdk-crypto.sqlite3").as_deref(),
            Some("crypto")
        );
        assert_eq!(
            read(&to, "matrix-sdk-state.sqlite3").as_deref(),
            Some("state")
        );
        assert!(!from.exists(), "moved, not copied");
    }

    #[test]
    fn nothing_to_move_is_not_an_error() {
        let root = scratch("absent");
        let outcome = move_dir(&root.join("app/store"), &root.join("group/store")).unwrap();
        assert_eq!(outcome, MoveOutcome::default());
        assert!(!root.join("group/store").exists());
    }

    #[test]
    fn a_second_run_changes_nothing() {
        let root = scratch("again");
        let (from, to) = (root.join("app/store"), root.join("group/store"));
        write(&from, "matrix-sdk-crypto.sqlite3", "crypto");
        move_dir(&from, &to).unwrap();
        assert_eq!(move_dir(&from, &to).unwrap(), MoveOutcome::default());
        assert_eq!(
            read(&to, "matrix-sdk-crypto.sqlite3").as_deref(),
            Some("crypto")
        );
    }

    #[test]
    fn an_interrupted_move_is_finished() {
        // The database moved; its write-ahead log had not yet.
        let root = scratch("partial");
        let (from, to) = (root.join("app/store"), root.join("group/store"));
        write(&to, "matrix-sdk-crypto.sqlite3", "crypto");
        write(&from, "matrix-sdk-crypto.sqlite3-wal", "wal");
        write(&from, "matrix-sdk-state.sqlite3", "state");

        let outcome = move_dir(&from, &to).unwrap();
        assert_eq!(outcome, MoveOutcome { moved: 2, kept: 0 });
        assert_eq!(
            read(&to, "matrix-sdk-crypto.sqlite3-wal").as_deref(),
            Some("wal")
        );
        assert_eq!(
            read(&to, "matrix-sdk-state.sqlite3").as_deref(),
            Some("state")
        );
        assert_eq!(
            read(&to, "matrix-sdk-crypto.sqlite3").as_deref(),
            Some("crypto")
        );
        assert!(!from.exists());
    }

    #[test]
    fn a_name_in_both_places_is_never_overwritten_or_deleted() {
        let root = scratch("conflict");
        let (from, to) = (root.join("app/store"), root.join("group/store"));
        write(&to, "matrix-sdk-state.sqlite3", "newer");
        write(&from, "matrix-sdk-state.sqlite3", "older");

        let outcome = move_dir(&from, &to).unwrap();
        assert_eq!(outcome, MoveOutcome { moved: 0, kept: 1 });
        assert_eq!(
            read(&to, "matrix-sdk-state.sqlite3").as_deref(),
            Some("newer")
        );
        assert_eq!(
            read(&from, "matrix-sdk-state.sqlite3").as_deref(),
            Some("older")
        );
    }
}
