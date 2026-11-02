// SPDX-License-Identifier: AGPL-3.0-only
//! One link's life: accepted or adopted, deadlines, reading and writing, and closing.

use super::*;

impl Shared {
    pub(super) async fn accept_loop(self: Arc<Self>, listener: TcpListener) {
        let mut attempt = 0;
        loop {
            match listener.accept().await {
                Ok((stream, peer)) => {
                    attempt = 0;
                    self.accept(stream, peer);
                }
                // Out of descriptors and the like: wait, then go on listening
                Err(_) => {
                    attempt += 1;
                    tokio::time::sleep(crate::backoff::delay(attempt, rand::random())).await;
                }
            }
        }
    }

    pub(super) fn accept(self: &Arc<Self>, stream: TcpStream, peer: SocketAddr) {
        let ip = canonical(peer.ip());
        let loopback = self.config.allow_loopback && ip.is_loopback();
        if !(is_local(ip) || loopback) {
            return;
        }
        if !loopback {
            let local = stream.local_addr().map(|a| canonical(a.ip()));
            let on_lan = match (&self.lan_interfaces, local) {
                (Some(check), Ok(local)) => check(local),
                (None, _) => true,
                _ => false,
            };
            if !on_lan {
                return;
            }
        }
        // No paired phone: the core refuses to make a responder, so nobody gets in
        let Ok(channel) = LanChannel::responder(&lock(&self.device)) else {
            return;
        };
        tune(&stream);
        self.take(Box::new(stream), channel, address_key(ip));
    }

    /// A new link, which has to authenticate within the deadlines.
    pub(super) fn take(self: &Arc<Self>, stream: Stream, channel: LanChannel, address: String) {
        let (writes, queued) = mpsc::unbounded_channel();
        let link = Arc::new(Link {
            id: self.ids.fetch_add(1, Ordering::Relaxed),
            address,
            channel: Mutex::new(channel),
            peer: Mutex::new(None),
            heard: AtomicBool::new(false),
            closed: AtomicBool::new(false),
            writes,
            kill: Notify::new(),
        });
        {
            let mut state = lock(&self.state);
            if state.stopped {
                return;
            }
            // Room for the newcomer: first within its own address, then overall
            let same: Vec<_> = state
                .pending
                .iter()
                .filter(|l| l.address == link.address)
                .cloned()
                .collect();
            if same.len() >= MAX_PENDING_PER_ADDRESS
                && let Some(v) = victim(&same, |l| l.heard.load(Ordering::SeqCst))
            {
                self.drop_link(&mut state, &v, false);
            }
            if state.pending.len() >= MAX_PENDING
                && let Some(v) = victim(&state.pending.clone(), |l| l.heard.load(Ordering::SeqCst))
            {
                self.drop_link(&mut state, &v, false);
            }
            state.pending.push(link.clone());
        }

        let (read, write) = tokio::io::split(stream);
        let reader = tokio::spawn(self.clone().read(link.clone(), read));
        tokio::spawn(
            self.clone()
                .write(link.clone(), write, queued, reader.abort_handle()),
        );

        let (shared, l) = (self.clone(), link.clone());
        tokio::spawn(async move {
            tokio::time::sleep(shared.config.first_bytes_deadline).await;
            if !l.heard.load(Ordering::SeqCst) {
                shared.drop_now(&l);
            }
        });
        let (shared, l) = (self.clone(), link);
        tokio::spawn(async move {
            tokio::time::sleep(shared.config.auth_deadline).await;
            if l.peer().is_none() {
                shared.drop_now(&l);
            }
        });
    }

    async fn read(self: Arc<Self>, link: Arc<Link>, mut read: tokio::io::ReadHalf<Stream>) {
        let mut buf = vec![0u8; 64 * 1024];
        loop {
            match read.read(&mut buf).await {
                Ok(n) if n > 0 => {
                    if link.closed.load(Ordering::SeqCst) {
                        return;
                    }
                    link.heard.store(true, Ordering::SeqCst);
                    let mut state = lock(&self.state);
                    // Checked under the lock drop_link runs under: a link closed meanwhile (a
                    // deadline, stop) must never authenticate after it went
                    if link.closed.load(Ordering::SeqCst) {
                        return;
                    }
                    if !self.handle(&mut state, &link, &buf[..n]) {
                        self.drop_link(&mut state, &link, false);
                        return;
                    }
                }
                _ => {
                    self.drop_now(&link);
                    return;
                }
            }
        }
    }

    /// Writes in order; a graceful close lets what was queued before it go first.
    async fn write(
        self: Arc<Self>,
        link: Arc<Link>,
        mut write: tokio::io::WriteHalf<Stream>,
        mut queued: mpsc::UnboundedReceiver<Write>,
        reader: AbortHandle,
    ) {
        loop {
            tokio::select! {
                biased;
                () = link.kill.notified() => break,
                next = queued.recv() => match next {
                    Some(Write::Bytes(bytes)) => {
                        // A peer that stops reading can't hold a close open past its grace
                        let written = tokio::select! {
                            biased;
                            () = link.kill.notified() => break,
                            r = async {
                                write.write_all(&bytes).await?;
                                write.flush().await
                            } => r,
                        };
                        if written.is_err() {
                            self.drop_now(&link);
                            break;
                        }
                    }
                    Some(Write::Close) | None => break,
                },
            }
        }
        reader.abort();
        let _ = write.shutdown().await;
    }

    pub(super) fn drop_now(&self, link: &Arc<Link>) {
        let mut state = lock(&self.state);
        self.drop_link(&mut state, link, false);
    }

    /// `graceful`: closed on purpose, so frames already sent are written first (up to
    /// `CLOSE_GRACE`); it's gone for routing at once. Errors and strangers go abruptly.
    pub(super) fn drop_link(&self, state: &mut State, link: &Arc<Link>, graceful: bool) {
        if link.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        // Outside a runtime (the app quitting) there's nothing left to wait for
        if let (true, Ok(rt)) = (graceful, tokio::runtime::Handle::try_current()) {
            let _ = link.writes.send(Write::Close);
            let l = link.clone();
            rt.spawn(async move {
                tokio::time::sleep(CLOSE_GRACE).await;
                l.kill.notify_one();
            });
        } else {
            link.kill.notify_one();
        }
        state.pending.retain(|l| l.id != link.id);
        if let Some(peer) = link.peer()
            && state.authed.get(&peer).is_some_and(|l| l.id == link.id)
        {
            state.authed.remove(&peer);
            (self.on_event)(LanEvent::Closed(peer));
        }
    }

    /// False closes the link: the channel refused the bytes (a stranger, tampering, garbage).
    fn handle(&self, state: &mut State, link: &Arc<Link>, data: &[u8]) -> bool {
        let (out, pairing) = {
            let mut channel = lock(&link.channel);
            match channel.receive(data) {
                Ok(out) => (out, channel.is_pairing()),
                Err(_) => return false,
            }
        };
        if !out.send.is_empty() {
            let _ = link.writes.send(Write::Bytes(out.send));
        }
        if let Some(peer) = out.authenticated {
            state.pending.retain(|l| l.id != link.id);
            *lock(&link.peer) = Some(peer.clone());
            // First, so the old link's close isn't reported
            if let Some(old) = state.authed.insert(peer.clone(), link.clone()) {
                self.drop_link(state, &old, true);
            }
            (self.on_event)(LanEvent::Authenticated(peer));
        }
        let Some(peer) = link.peer() else {
            return true;
        };
        for bytes in out.frames {
            // From the authenticated phone, but still only the frames this link carries
            let Ok(frame) = ClientFrame::decode(bytes.as_slice()) else {
                if pairing {
                    return false;
                }
                continue;
            };
            match frame.frame {
                Some(client_frame::Frame::Send(e)) => {
                    (self.on_event)(LanEvent::Envelope(peer.clone(), e))
                }
                Some(client_frame::Frame::Delivered(d)) => {
                    (self.on_event)(LanEvent::Delivered(peer.clone(), d.msg_id))
                }
                // Only a pairing phone needs the bundle, and only this computer's
                Some(client_frame::Frame::GetBundle(g))
                    if pairing && g.device_key == lock(&self.device).relay_key() =>
                {
                    (self.on_event)(LanEvent::BundleRequest(peer.clone()))
                }
                // A pairing channel carries the pairing exchange and nothing else
                _ if pairing => return false,
                _ => {}
            }
        }
        true
    }
}
