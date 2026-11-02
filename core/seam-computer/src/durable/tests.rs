// SPDX-License-Identifier: AGPL-3.0-only
use std::fs;
use std::os::unix::fs::PermissionsExt;

use super::DurableFile;

fn set_mode(path: &std::path::Path, mode: u32) {
    fs::set_permissions(path, fs::Permissions::from_mode(mode)).unwrap();
}

/// Root ignores permissions, so the permission checks can't run as root.
fn root() -> bool {
    unsafe { libc::geteuid() == 0 }
}

/// SR-2-05: only a missing file means first launch.
#[test]
fn only_a_missing_file_looks_like_first_launch() {
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(
        DurableFile::new(dir.path().join("state.bin"))
            .read()
            .unwrap(),
        None
    );

    if !root() {
        let locked = dir.path().join("locked.bin");
        fs::write(&locked, [1, 2, 3]).unwrap();
        set_mode(&locked, 0);
        assert!(DurableFile::new(&locked).read().is_err());
        set_mode(&locked, 0o600);
    }

    // A folder where the file should be is an error too, not a fresh install
    assert!(DurableFile::new(dir.path()).read().is_err());
}

/// SR-2-11: a write is complete when it returns, and a failed write keeps the old state.
#[test]
fn a_failed_write_keeps_the_old_state() {
    let dir = tempfile::tempdir().unwrap();
    let file = DurableFile::new(dir.path().join("state.bin"));
    file.write(&[1]).unwrap();
    file.write(&[2]).unwrap();
    assert_eq!(file.read().unwrap(), Some(vec![2]));
    assert!(!dir.path().join("state.bin.tmp").exists());
    let mode = fs::metadata(file.path()).unwrap().permissions().mode() & 0o777;
    assert_eq!(mode, 0o600, "private");

    if !root() {
        set_mode(dir.path(), 0o500);
        assert!(file.write(&[3]).is_err());
        set_mode(dir.path(), 0o700);
        assert_eq!(file.read().unwrap(), Some(vec![2]));
    }
}

/// SR-2-06: a reset moves the old state aside; it never deletes it.
#[test]
fn a_reset_keeps_the_old_files() {
    let dir = tempfile::tempdir().unwrap();
    let file = DurableFile::new(dir.path().join("state.bin"));
    file.write(&[9]).unwrap();
    fs::write(dir.path().join("state.bin.tmp"), [8]).unwrap();
    file.move_aside(42).unwrap();
    assert_eq!(file.read().unwrap(), None);
    assert_eq!(
        fs::read(dir.path().join("state.bin.unreadable-42")).unwrap(),
        [9]
    );
    assert_eq!(
        fs::read(dir.path().join("state.bin.tmp.unreadable-42")).unwrap(),
        [8]
    );
}
