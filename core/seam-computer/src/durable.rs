// SPDX-License-Identifier: AGPL-3.0-only
//! A file that survives power cuts: the sealed state and the outbox.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

/// One file, written whole or not at all.
#[derive(Debug, Clone)]
pub struct DurableFile {
    path: PathBuf,
}

impl DurableFile {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    fn tmp(&self) -> PathBuf {
        with_suffix(&self.path, ".tmp")
    }

    /// None only when the file doesn't exist (first launch). Any other failure is an error, so a
    /// temporary read error can never look like a fresh install and get overwritten (SR-2-05).
    pub fn read(&self) -> io::Result<Option<Vec<u8>>> {
        match fs::read(&self.path) {
            Ok(bytes) => Ok(Some(bytes)),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// Durable before it returns, power cuts included: a temp file, flushed, renamed over the old
    /// one, then the folder flushed. A failed write leaves the old file as it was; callers never
    /// send anything a failed write would forget (SR-2-11).
    pub fn write(&self, bytes: &[u8]) -> io::Result<()> {
        let tmp = self.tmp();
        let mut file = private_file(&tmp)?;
        file.write_all(bytes)?;
        // F_FULLFSYNC on Apple platforms, which also flushes the drive's cache (SR-2-68)
        // A filesystem that doesn't support it gets a plain fsync, as the Swift app did
        if file.sync_all().is_err() {
            plain_fsync(&file)?;
        }
        drop(file);
        fs::rename(&tmp, &self.path)?;
        sync_folder(&self.path)
    }

    /// For a reset the user chose: the file (and any half-written temp) is renamed aside, never
    /// deleted, so an unreadable state can still be recovered later (SR-2-06).
    pub fn move_aside(&self, stamp: u64) -> io::Result<()> {
        for file in [self.path.clone(), self.tmp()] {
            if file.exists() {
                fs::rename(&file, with_suffix(&file, &format!(".unreadable-{stamp}")))?;
            }
        }
        Ok(())
    }

    /// Gone, if it was there.
    pub fn delete(&self) {
        let _ = fs::remove_file(&self.path);
    }
}

#[cfg(unix)]
fn plain_fsync(file: &File) -> io::Result<()> {
    use std::os::fd::AsRawFd;
    if unsafe { libc::fsync(file.as_raw_fd()) } == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

#[cfg(not(unix))]
fn plain_fsync(file: &File) -> io::Result<()> {
    file.sync_all()
}

fn with_suffix(path: &Path, suffix: &str) -> PathBuf {
    let mut s = path.as_os_str().to_owned();
    s.push(suffix);
    PathBuf::from(s)
}

#[cfg(unix)]
fn private_file(path: &Path) -> io::Result<File> {
    use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
    let file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)?;
    // A temp left from before keeps its old mode otherwise
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    Ok(file)
}

#[cfg(not(unix))]
fn private_file(path: &Path) -> io::Result<File> {
    OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .open(path)
}

/// The rename itself survives a power cut.
#[cfg(unix)]
fn sync_folder(path: &Path) -> io::Result<()> {
    let Some(folder) = path.parent() else {
        return Ok(());
    };
    let folder = if folder.as_os_str().is_empty() {
        Path::new(".")
    } else {
        folder
    };
    match File::open(folder)?.sync_all() {
        // Some filesystems can't flush a folder
        Err(e) if e.raw_os_error() == Some(libc::EINVAL) => Ok(()),
        other => other,
    }
}

#[cfg(not(unix))]
fn sync_folder(_: &Path) -> io::Result<()> {
    Ok(())
}

#[cfg(all(test, unix))]
mod tests;
