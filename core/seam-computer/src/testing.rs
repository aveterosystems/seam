// SPDX-License-Identifier: AGPL-3.0-only
//! Test helpers shared by the modules' tests.

use seam_core::Device;

/// A computer and a phone, paired as the real exchange leaves them.
pub fn paired_devices() -> (Device, Device) {
    let mut mac = Device::generate().unwrap();
    let mut phone = Device::generate().unwrap();
    let offer = mac.start_pairing();
    let bundle = mac.prekey_bundle().unwrap();
    let (_, request) = phone
        .pair_with(
            &mac.identity_key(),
            mac.relay_key(),
            "Mac",
            &bundle,
            &offer.token,
            "Phone",
            "Test",
        )
        .unwrap();
    let r = mac.accept_pairing(&request).unwrap();
    mac.confirm_pairing(&r.peer_id).unwrap();
    // Payload{control{pair_accept{}}}
    let accept = mac.seal(&r.peer_id, &[0x0A, 0x02, 0x32, 0x00], 0).unwrap();
    phone.open(&accept).unwrap();
    (mac, phone)
}
