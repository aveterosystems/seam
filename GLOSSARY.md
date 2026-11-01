# Seam

Seam links one computer and one phone so notifications, messages, calls, files and controls move between them privately, at home or away.

## Devices

**Computer**:
The desktop side of a pairing: a Mac today, Linux and Windows later. Each app's own screens use the platform's name.
_Avoid_: desktop, PC, host, Mac (outside Mac-only text)

**Phone**:
The Android side of a pairing.
_Avoid_: mobile, device, handset

## Connections

**Same Wi‑Fi**:
The computer and phone reach each other directly on one local network.
_Avoid_: LAN, local, direct link

**Away**:
The phone reaches the computer from another network, through Tor.
_Avoid_: remote, internet, relay mode

**Wi‑Fi lock**:
Same Wi‑Fi allowed only on networks you chose; elsewhere the computer stays hidden and the phone uses Away.
_Avoid_: trusted networks, network allowlist

## Features

**Feature**:
One thing Seam does between the two devices, such as clipboard or Find phone, with its own minimum bar for counting as done.
_Avoid_: plugin, module

**Capability**:
One part of a feature that a device says it can do when it connects, such as sharing its Do Not Disturb or following the other's. A feature runs in whichever parts both sides support; a platform may lack some.
_Avoid_: permission, support flag

**Live control**:
A feature that sends a stream of input as you act (touchpad, presentation remote, volume dragging). Works on Same Wi‑Fi only.
_Avoid_: remote input, realtime feature

**Find phone**:
The computer makes the phone ring at full volume, even on silent.
_Avoid_: ping, locate

**Ring computer**:
The phone makes the computer play a loud sound until it's dismissed.
_Avoid_: Ring Mac, find my Mac, ping

**Photo request**:
The computer asks for a photo, the phone opens its camera, you take the shot on the phone, and the photo arrives on the computer. Seam never captures without you.
_Avoid_: remote camera, take photo

**Harmless feature**:
A feature that can only make noise, reduce access or change sound on the other device (Ring computer, lock, media play/pause, volume).
_Avoid_: safe feature

**Powerful feature**:
A feature that can act as you on the computer (touchpad and keyboard, presentation remote, Computer actions). Off until turned on from the computer itself.
_Avoid_: remote control, privileged feature

**Computer action**:
One of a fixed set of things the phone can tell the computer to do, such as lock, sleep or mute. Never user-defined.
_Avoid_: run command, script, shortcut
