// SPDX-License-Identifier: GPL-3.0-or-later
// Seam's clipboard door on GNOME (decision 0012). Wayland lets only the focused app see the
// clipboard; the shell sees it always. This exports a small D-Bus service that no other app may
// call (the Seam Flatpak only; unsandboxed programs running as the user are out of scope, see
// trust.js): how many times the clipboard changed, the current copy, and a write. Nothing copied
// is kept or logged.

import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Meta from 'gi://Meta';
import St from 'gi://St';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

import {allowed} from './trust.js';

/** Raised only when the interface changes in a way older apps can't use. */
const API = 1;
const NAME = 'com.avetero.Seam.Clipboard';
const PATH = '/com/avetero/Seam/Clipboard';
const IFACE = `<node>
  <interface name="com.avetero.Seam.Clipboard1">
    <property name="Version" type="u" access="read"/>
    <method name="ChangeCount"><arg name="count" type="x" direction="out"/></method>
    <method name="Read">
      <arg name="if_still" type="x" direction="in"/>
      <arg name="ok" type="b" direction="out"/>
      <arg name="types" type="as" direction="out"/>
      <arg name="text" type="s" direction="out"/>
    </method>
    <method name="Write">
      <arg name="text" type="s" direction="in"/>
      <arg name="count" type="x" direction="out"/>
    </method>
  </interface>
</node>`;
const DENIED = 'com.avetero.Seam.Clipboard.Error.AccessDenied';
/** Unique bus names are never reused, so a verdict holds for the caller's whole connection. */
const MAX_VERDICTS = 64;

export default class SeamClipboardExtension extends Extension {
    enable() {
        this._count = 0;
        // Set while Seam's own write is landing: that change is counted once, here, so Write
        // returns the count that includes it and Seam never reads its own write as a new copy
        this._writing = false;
        this._verdicts = new Map();
        this._selection = global.display.get_selection();
        this._ownerChanged = this._selection.connect('owner-changed', (_s, type) => {
            if (type !== Meta.SelectionType.SELECTION_CLIPBOARD)
                return;
            if (this._writing)
                this._writing = false;
            else
                this._count++;
        });
        this._object = Gio.DBusExportedObject.wrapJSObject(IFACE, this);
        this._object.export(Gio.DBus.session, PATH);
        this._nameId = Gio.bus_own_name_on_connection(Gio.DBus.session, NAME, Gio.BusNameOwnerFlags.NONE, null, null);
    }

    disable() {
        Gio.bus_unown_name(this._nameId);
        this._object.unexport();
        this._selection.disconnect(this._ownerChanged);
        this._object = null;
        this._selection = null;
        this._verdicts = null;
    }

    get Version() {
        return API;
    }

    ChangeCountAsync(_params, invocation) {
        if (this._refuse(invocation))
            return;
        invocation.return_value(new GLib.Variant('(x)', [this._count]));
    }

    ReadAsync([ifStill], invocation) {
        if (this._refuse(invocation))
            return;
        if (ifStill !== this._count) {
            invocation.return_value(new GLib.Variant('(bass)', [false, [], '']));
            return;
        }
        const types = this._selection.get_mimetypes(Meta.SelectionType.SELECTION_CLIPBOARD);
        St.Clipboard.get_default().get_text(St.ClipboardType.CLIPBOARD, (_c, text) => {
            // Types and text from the same copy: a newer copy since means look again (SR-4-05)
            const same = ifStill === this._count;
            invocation.return_value(new GLib.Variant('(bass)', [same, same ? types : [], same ? text ?? '' : '']));
        });
    }

    WriteAsync([text], invocation) {
        if (this._refuse(invocation))
            return;
        // Counted now, whether the shell reports the change during set_text or after it
        this._writing = true;
        this._count++;
        St.Clipboard.get_default().set_text(St.ClipboardType.CLIPBOARD, text);
        invocation.return_value(new GLib.Variant('(x)', [this._count]));
    }

    /** Answers anyone but the Seam app with an error, and says nothing else about them. */
    _refuse(invocation) {
        const sender = invocation.get_sender();
        let ok = this._verdicts.get(sender);
        if (ok === undefined) {
            ok = this._check(sender);
            if (this._verdicts.size >= MAX_VERDICTS)
                this._verdicts.clear();
            this._verdicts.set(sender, ok);
        }
        if (!ok)
            invocation.return_dbus_error(DENIED, 'Only Seam can use this service');
        return !ok;
    }

    _check(sender) {
        try {
            const [pid] = Gio.DBus.session.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus',
                'org.freedesktop.DBus', 'GetConnectionUnixProcessID', new GLib.Variant('(s)', [sender]),
                new GLib.VariantType('(u)'), Gio.DBusCallFlags.NONE, 1000, null).deepUnpack();
            const read = path => {
                try {
                    const [, bytes] = GLib.file_get_contents(path);
                    return new TextDecoder().decode(bytes);
                } catch {
                    return null;
                }
            };
            const info = read(`/proc/${pid}/root/.flatpak-info`);
            let exe = null;
            try {
                exe = GLib.file_read_link(`/proc/${pid}/exe`);
            } catch {}
            const dev = GLib.file_test(GLib.build_filenamev([GLib.get_user_config_dir(), 'seam', 'allow-development-build']),
                GLib.FileTest.EXISTS);
            return allowed(info, exe, dev);
        } catch {
            return false;
        }
    }
}
