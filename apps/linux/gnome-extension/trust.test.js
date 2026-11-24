// SPDX-License-Identifier: GPL-3.0-or-later
// gjs -m trust.test.js: the extension's access check, without a shell.
import {allowed, flatpakAppId} from './seam-clipboard@avetero.com/trust.js';

let failed = 0;
const check = (name, cond) => {
    if (!cond) {
        failed++;
        print(`FAIL ${name}`);
    }
};
const info = id => `[Application]\nname=${id}\nruntime=runtime/org.gnome.Platform/aarch64/51\n\n[Instance]\ninstance-id=1\n`;

check('reads the app id', flatpakAppId(info('com.avetero.Seam')) === 'com.avetero.Seam');
check('no info, no id', flatpakAppId(null) === null);
check('garbage, no id', flatpakAppId('not an ini [[') === null);
check('Seam Flatpak is allowed', allowed(info('com.avetero.Seam'), '/app/bin/seam', false));
check('another Flatpak app is refused', !allowed(info('org.example.Spy'), '/app/bin/seam', false));
check('a lookalike id is refused', !allowed(info('com.avetero.Seam.Evil'), '/app/bin/seam', false));
check('a host process is refused', !allowed(null, '/usr/bin/python3', false));
check('a host "seam" is refused without the override', !allowed(null, '/home/u/seam', false));
check('a host "seam" is allowed with the override', allowed(null, '/home/u/seam', true));
check('the override never admits another sandboxed app', !allowed(info('org.example.Spy'), '/app/bin/seam', true));
check('the override needs the name', !allowed(null, '/usr/bin/seamless', true));

// The limits, written down: a same-user program that fakes a Flatpak root passes (out of scope);
// a sandboxed app never passes through the override, whatever its executable is called
check('a forged Seam flatpak-info passes (same-user programs are out of scope)', allowed(info('com.avetero.Seam'), '/tmp/x', false));
check('the override never admits a sandboxed app named seam', !allowed(info('org.example.Spy'), '/app/bin/seam', true));
check('the override admits only a host executable named seam', allowed(null, '/home/u/dev/seam', true) && !allowed(null, '/home/u/dev/seam.sh', true));

if (failed)
    imports.system.exit(1);
print('trust: all passed');
