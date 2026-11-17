// SPDX-License-Identifier: AGPL-3.0-only
// Who may use the clipboard through this extension (decision from "How does Seam trust its GNOME
// clipboard extension?"): the Seam Flatpak, never another sandboxed app. A program running
// unsandboxed as the same user is outside what this can stop (it could fake a Flatpak's root with
// a user namespace, or install a shell extension of its own); such a program can already read the
// user's keyring and files. Pure functions, so they're tested without a shell.

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
 * has none), `exe` its executable's path, `devOverride` whether the development override file
 * exists. A sandboxed app can't forge its app ID or create the file (it can't write the host's
 * config folder); unsandboxed same-user programs are out of scope (see above).
 */
export function allowed(info, exe, devOverride) {
    if (flatpakAppId(info) === APP_ID)
        return true;
    // Development builds of Seam run outside Flatpak; only when the user asked for it
    return devOverride && info === null && typeof exe === 'string' && exe.endsWith('/seam');
}
