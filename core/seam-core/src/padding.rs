//! Pads plaintext to a fixed bucket so the relay can't tell messages apart by size.
//! Layout: 4-byte big-endian length, the data, then zeros up to the bucket size.

use crate::{Error, Result};

pub const BUCKETS: [usize; 4] = [1024, 16 * 1024, 256 * 1024, 1024 * 1024];
const LEN: usize = 4;

pub fn pad(data: &[u8]) -> Result<Vec<u8>> {
    let need = data.len() + LEN;
    let size = *BUCKETS
        .iter()
        .find(|&&b| b >= need)
        .ok_or(Error::TooLarge(data.len()))?;
    let mut out = Vec::with_capacity(size);
    out.extend_from_slice(&(data.len() as u32).to_be_bytes());
    out.extend_from_slice(data);
    out.resize(size, 0);
    Ok(out)
}

pub fn unpad(padded: &[u8]) -> Result<Vec<u8>> {
    if !BUCKETS.contains(&padded.len()) {
        return Err(Error::BadPadding);
    }
    let len = u32::from_be_bytes(padded[..LEN].try_into().unwrap()) as usize;
    let body = padded.get(LEN..LEN + len).ok_or(Error::BadPadding)?;
    // Non-zero filler means someone built this by hand; reject rather than guess
    if padded[LEN + len..].iter().any(|&b| b != 0) {
        return Err(Error::BadPadding);
    }
    Ok(body.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_and_hides_size() {
        let short = pad(b"hi").unwrap();
        let longer = pad(&[7u8; 900]).unwrap();
        assert_eq!(short.len(), 1024);
        assert_eq!(longer.len(), 1024);
        assert_eq!(unpad(&short).unwrap(), b"hi");
        assert_eq!(unpad(&longer).unwrap(), vec![7u8; 900]);
    }

    #[test]
    fn picks_the_next_bucket_at_the_edge() {
        assert_eq!(pad(&[0u8; 1020]).unwrap().len(), 1024);
        assert_eq!(pad(&[0u8; 1021]).unwrap().len(), 16 * 1024);
        assert!(matches!(
            pad(&vec![0u8; 1024 * 1024]),
            Err(Error::TooLarge(_))
        ));
    }

    #[test]
    fn rejects_malformed_input() {
        assert!(unpad(&[0u8; 100]).is_err()); // not a bucket size
        let mut lying = pad(b"hi").unwrap();
        lying[..4].copy_from_slice(&5000u32.to_be_bytes()); // length past the end
        assert!(unpad(&lying).is_err());
        let mut dirty = pad(b"hi").unwrap();
        dirty[500] = 1; // junk in the filler
        assert!(unpad(&dirty).is_err());
    }
}
