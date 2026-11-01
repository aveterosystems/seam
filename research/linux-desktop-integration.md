# Linux computer: status icon, notifications, background running (GNOME and KDE Plasma, Wayland)

Research for #61 (part of map #58). Checked 2026-11-01 against the source at each project's main/master branch on that date, unless a release is named. Latest releases then: xdg-desktop-portal 1.22.0 (2026-06-08), Plasma 6.8.x, GNOME Shell main (GNOME 49/50 era). Anything marked **[unverified]** comes from memory or second-hand sources, not a primary source I read.

## Answer in brief

| | GNOME (Wayland) | KDE Plasma 6 (Wayland) | Inside Flatpak |
|---|---|---|---|
| Status icon | **None in stock GNOME.** Needs the AppIndicator/KStatusNotifierItem extension (Ubuntu ships it on by default **[unverified]**, Fedora does not). Icon + DBusMenu menu only; no popover anchored to the icon. | **Native** StatusNotifierItem (SNI). Click gives `Activate(x, y)`; the app shows its own window. | Works with `--talk-name=org.kde.StatusNotifierWatcher` (plus icon-path care). No tray portal exists. Flathub rejects tray-only apps. |
| Notifications: actions | Yes (`actions`) | Yes (`actions`) | Yes via Notification portal (`buttons`) |
| Close from code | Yes (`CloseNotification`) | Yes (`CloseNotification`) | Yes (`RemoveNotification`) |
| Inline reply | **No**, any route | **Yes** via freedesktop API (`inline-reply` capability, KDE extension) | **No on either desktop**: portal v2 defines `im.reply-with-text`, but neither backend implements v2 yet, so the frontend strips it |
| Start at login | XDG autostart file in `~/.config/autostart` | Same | Background portal `RequestBackground(autostart=true)` writes the file for you |
| Keep running with no window | Allowed. Sandboxed apps listed in Quick Settings "Background Apps" | Allowed. Sandboxed apps get a "Background Activity" Allow/Deny notification | Background portal grants it |

What this means for Seam: the Mac's "menu-bar icon opens a popover" works on KDE (but as a window we place ourselves, not a true popover) and **does not exist** on stock GNOME. On GNOME, a native panel UI needs a GNOME Shell extension (what GSConnect does). Inline reply works only on KDE, through the freedesktop interface, not the portal. On GNOME, a reply has to open a fallback reply window, or Seam says reply is "Not available on this computer" (the open question on #58).

## 1. Status icon with click-to-open

### The protocol: StatusNotifierItem
- Spec: [Status Notifier Item Specification](https://specifications.freedesktop.org/status-notifier-item/latest/), version 0.1, publication date "TBD" (it has been a draft forever, and it is the de facto standard). Authors: Marco Martin and Aaron Seigo (KDE).
- [`Activate(x, y)`](https://specifications.freedesktop.org/status-notifier-item/latest/status-notifier-item.html): "The x and y parameters are in screen coordinates". They are only a hint for where to show a window. `Menu` is an object path that implements `com.canonical.dbusmenu`. `ItemIsMenu` means "only a context menu": the host shows the menu instead of calling `Activate`.
- On Wayland, an ordinary client can't place a top-level window at screen coordinates, so "a popover under the icon" is not something a normal app can do. **[unverified as a per-compositor test; this follows from xdg-shell, which has no global positioning]** On KWin, an app can use the layer-shell protocol (LayerShellQt) to anchor a surface to a screen edge, near the panel. That is the closest thing to a popover. GNOME's Mutter has no layer-shell for apps. **[unverified]**

### GNOME
- Stock GNOME Shell has no tray. SNI and AppIndicator icons appear only with [ubuntu/gnome-shell-extension-appindicator](https://github.com/ubuntu/gnome-shell-extension-appindicator). Its README says it shows the icon, opens its menu on click, double-click activates a window "if the indicator implements that", and middle-click sends `SecondaryActivate`. **Tooltips are not supported.**
- So on GNOME, even with the extension, the dependable thing is **icon + DBusMenu menu**. A custom popover is not on offer.
- The native alternative is a **GNOME Shell extension**. GSConnect ([README](https://github.com/GSConnect/gnome-shell-extension-gsconnect)) is "a complete implementation of KDE Connect especially for GNOME Shell", shipped as an extension, so it gets real Shell UI (panel menus, Quick Settings). The price: extensions are JavaScript that runs inside the Shell, have to be ported to every GNOME release, are installed through extensions.gnome.org, and can't be shipped in a Flatpak.

### KDE Plasma
- SNI is native to Plasma's system tray. KDE apps use [KStatusNotifierItem](https://api.kde.org/kstatusnotifieritem.html). Its docs say that inside a Flatpak the manifest must add `--talk-name=org.kde.StatusNotifierWatcher`.
- KDE Connect's own tray presence is a KStatusNotifierItem ("kdeconnect-indicator" for non-Plasma desktops). **[unverified: I did not read KDE Connect source for this]**

### Flatpak
- There is no tray or StatusNotifier portal in xdg-desktop-portal: none appears in [NEWS](https://github.com/flatpak/xdg-desktop-portal/blob/main/NEWS.md) up to 1.22.0. Sandboxed apps talk SNI directly through `--talk-name=org.kde.StatusNotifierWatcher`.
- Gotchas (second-hand, from [Flathub Discourse](https://discourse.flathub.org/t/permissions-for-showing-tray-icon/10785) and an [Electron PR](https://releases.electronjs.org/pr/53641)): an icon passed as a file path has to be readable from outside the sandbox, or pass pixmaps instead. Sandboxes can't own arbitrary well-known names, so register the item under the unique connection name.
- **Flathub policy:** "Applications that operate exclusively as tray applets will not be accepted" ([requirements](https://docs.flathub.org/docs/for-app-authors/requirements)). A Flathub Seam needs a real main window. Flathub also says to use portals wherever one exists.

## 2. Notifications: actions, inline reply, closing from code

### Two APIs
1. **freedesktop Notifications** (`org.freedesktop.Notifications`) ([spec](https://specifications.freedesktop.org/notification/latest/protocol.html)). Methods `GetCapabilities`, `Notify`, `CloseNotification`, `GetServerInformation`; signals `NotificationClosed`, `ActionInvoked`, `ActivationToken`. Standard capabilities: `action-icons actions body body-hyperlinks body-images body-markup icon-multi icon-static persistence sound`, plus vendor `x-…` capabilities. **The spec has no inline reply.**
2. **XDG Notification portal** (`org.freedesktop.portal.Notification`) ([docs](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Notification.html)). `AddNotification(id, dict)` (reusing the same id replaces the notification) and `RemoveNotification(id)`. **Version 2** shipped in xdg-desktop-portal **1.19.1 (2024-12-21)**. It adds `markup-body`, file-descriptor icons and sounds, `display-hint`, `category` (including `im.received`), button `purpose` (including **`im.reply-with-text`**), a `response` (the reply text) and `platform-data` (activation token) in `ActionInvoked`, and a `SupportedOptions` property.
   - **Catch:** the frontend passes v2 fields through only when the desktop's backend reports version > 1 ([desktop-portal/notification.c](https://github.com/flatpak/xdg-desktop-portal/blob/main/desktop-portal/notification.c): `purpose` is parsed only `if impl_version > 1`). Both backends below report version 1, so **portal inline reply works nowhere today**.
   - Non-sandboxed apps can use portals too. The host app registry (`org.freedesktop.host.portal.Registry`, since **1.19.4**) lets a host app register its app ID.

### GNOME Shell
- freedesktop daemon ([notificationDaemon.js](https://gitlab.gnome.org/GNOME/gnome-shell/-/blob/main/js/ui/notificationDaemon.js)): `GetCapabilities` returns `actions body body-markup icon-static persistence sound`. **No `inline-reply`.** `CloseNotification` works: it destroys the notification and emits `NotificationClosed`.
- Portal route: [xdg-desktop-portal-gnome `notification.c`](https://gitlab.gnome.org/GNOME/xdg-desktop-portal-gnome/-/blob/main/src/notification.c) forwards to GNOME Shell's `org.gtk.Notifications`. It reports no v2 version, and Shell's GTK-notification handler adds each button with only its label and callback, so it never reads `purpose`. `RemoveNotification` is forwarded. When an action is clicked, it calls `org.freedesktop.Application.ActivateAction` on a bus name equal to the app ID, so the app has to own its app ID on the bus and have a matching `.desktop` file.
- **Result on GNOME: actions yes, close yes, inline reply no, whichever route.**

### KDE Plasma
- freedesktop daemon ([plasma-workspace `libnotificationmanager/server_p.cpp`](https://invent.kde.org/plasma/plasma-workspace/-/blob/master/libnotificationmanager/server_p.cpp)): capabilities include `actions persistence inline-reply sound body-markup body-hyperlinks body-images icon-static` and `x-kde-urls x-kde-origin-name x-kde-display-appname inhibitions`. `CloseNotification` works.
- **Inline reply (KDE extension):** add an action whose key is `inline-reply` (its label becomes the reply button). Optional hints `x-kde-reply-placeholder-text`, `x-kde-reply-submit-button-text` and `x-kde-reply-submit-button-icon-name`. The text comes back in a `NotificationReplied(id, text)` signal ([notification.cpp](https://invent.kde.org/plasma/plasma-workspace/-/blob/master/libnotificationmanager/notification.cpp)). Added 2019-12-22 ("[Notifications] Add quick reply feature"), so **Plasma 5.18 and later [the release mapping is unverified; the commit date is verified]**. KF6's `KNotificationReplyAction` wraps this. **[unverified]**
- Portal route: in March 2026 the portal backend moved from xdg-desktop-portal-kde ("Remove notifications portal implementation", 2026-03-25) into plasma-workspace (`plasmanotify.portal`, `UseIn=kde`). It first shipped in **Plasma 6.7** (verified: the commit is in v6.7.0, not v6.6.0). [`Portal::version()` returns **1**](https://invent.kde.org/plasma/plasma-workspace/-/blob/master/libnotificationmanager/portal_p.cpp), and buttons are read only as `action/label/target`, so **no portal inline reply on KDE** either. `RemoveNotification` works.
- **Result on KDE: actions yes, close yes, inline reply yes, but only through `org.freedesktop.Notifications` directly.**

### Flatpak
- Use the portal: no static permission needed, and it's what Flathub expects. The cost is no inline reply on either desktop until the backends ship v2. The alternative is `--talk-name=org.freedesktop.Notifications`, which gets KDE inline reply inside Flatpak. That is a static permission Flathub reviewers may question, since a portal exists. **[unverified how Flathub reviews this name]**

### How KDE Connect and GSConnect handle reply
- KDE Connect uses KNotifications, so it gets inline reply on Plasma. **[unverified: not read in source]**
- GSConnect is a Shell extension, so it builds its own notification UI with a reply that opens its messaging window. **[unverified: not read in source]**

## 3. Starting at login and running in the background

### XDG autostart (both desktops, outside Flatpak)
- Put a `.desktop` file in `$XDG_CONFIG_HOME/autostart/` ([Desktop Application Autostart spec](https://specifications.freedesktop.org/autostart-spec/latest/)). GNOME and Plasma both honour it. Both now start the session with systemd, and [`systemd-xdg-autostart-generator`](https://www.freedesktop.org/software/systemd/man/latest/systemd-xdg-autostart-generator.html) (systemd 246+ **[version unverified]**) turns these files into `app-*@autostart.service` user units. So an autostart file is the portable answer.
- **systemd user unit** (`graphical-session.target`, `Restart=on-failure`) is an option for a host-installed app (deb/rpm) that wants restarts on crash. Not usable from Flatpak. **[I did not verify it per desktop]**

### Background portal
- [`org.freedesktop.portal.Background`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Background.html) v2: `RequestBackground(parent, {reason, autostart, commandline, dbus-activatable})` returns `background` and `autostart` booleans. `SetStatus({message})` sets a one-line status of 96 characters or fewer, **sandboxed apps only** ([background.c](https://github.com/flatpak/xdg-desktop-portal/blob/main/desktop-portal/background.c) returns "Only sandboxed applications can set background status").
- Host apps automatically get permission YES. Since **1.21.0**, autostart requires a valid app ID ("Require a valid AppID from apps in `RequestBackground` to enable autostart").
- The backends write the autostart file: [GNOME](https://gitlab.gnome.org/GNOME/xdg-desktop-portal-gnome/-/blob/main/src/background.c) and [KDE](https://invent.kde.org/plasma/xdg-desktop-portal-kde/-/blob/master/src/background.cpp) both write to `~/.config/autostart/`.
- The background monitor (since **1.16.0**) watches running Flatpak instances that have no window:
  - **GNOME:** lists them in Quick Settings "Background Apps", with the `SetStatus` message as a subtitle and a close button ([backgroundApps.js](https://gitlab.gnome.org/GNOME/gnome-shell/-/blob/main/js/ui/status/backgroundApps.js); first shipped in GNOME 44 **[version unverified]**).
  - **KDE:** shows a persistent "Background Activity" notification with **Allow / Deny**. Deny asks for confirmation and then kills the app.

### Per packaging
- **deb/rpm/AppImage (host):** write `~/.config/autostart/<app-id>.desktop` yourself, or call the portal (it auto-allows host apps). Running with no window is unrestricted.
- **Flatpak:** must use the Background portal (it can't write `~/.config/autostart` without a filesystem hole). Expect GNOME to list Seam under Background Apps (use `SetStatus`, e.g. "Linked to Pixel 8") and KDE to ask the user once.

## Gaps I couldn't close
- Whether any GNOME release after main as of 2026-11-01 plans Notification v2 or inline reply in Shell.
- Exact distro defaults for the AppIndicator extension (Ubuntu yes, others vary).
- The "popover via layer-shell on KWin" idea needs a prototype on a real Plasma Wayland session (#58 has no Linux test machine yet).
