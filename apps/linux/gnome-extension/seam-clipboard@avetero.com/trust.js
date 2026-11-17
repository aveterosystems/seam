// SPDX-License-Identifier: AGPL-3.0-only
// Who may use the clipboard through this extension: only the Seam app (decision from "How does
// Seam trust its GNOME clipboard extension?"). Pure functions, so they're tested without a shell.

import GLib from 'gi://GLib';

export const APP_ID = 'com.avetero.Seam';

/** The Flatpak app ID in a process's /.flatpak-info, or null when it isn't a Flatpak app. */
export function flatpakAppId(info) {
    if (info === null || info === undefined)
        return null;
    const kf = new GLib.KeyFile();
    try {
        kf.load_from_bytes(new GLib.Bytes(new TextEncoder().encode(info)), GLib.KeyFileFlags.NONE);
        return kf.get_string('Application', 'name');
    } catch {
        return null;
    }
}

/**
 * Whether a caller may read and write the clipboard. `info` is its /.flatpak-info (null when it
 * has none), `exe` its executable's path, `devOverride` whether the user turned on the
 * development override (a file only they can create). A sandboxed app can't forge its app ID.
 */
export function allowed(info, exe, devOverride) {
    if (flatpakAppId(info) === APP_ID)
        return true;
    // Development builds of Seam run outside Flatpak; only when the user asked for it
    return devOverride && info === null && typeof exe === 'string' && exe.endsWith('/seam');
}
