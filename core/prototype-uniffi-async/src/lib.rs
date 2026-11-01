//! PROTOTYPE, throwaway. The computer-session Pasteboard interface (specs/computer-session/01-crate.md)
//! as an async foreign trait, driven from a tokio runtime the crate owns.
use std::sync::Arc;
use std::time::{Duration, Instant};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum PasteboardError {
    #[error("unavailable: {reason}")]
    Unavailable { reason: String },
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct PasteboardItem {
    pub types: Vec<String>,
    pub text: Option<String>,
}

/// The OS hook, implemented in Swift. Async, as the Mac's `Pasteboard` protocol is today.
#[uniffi::export(with_foreign)]
#[async_trait::async_trait]
pub trait Pasteboard: Send + Sync {
    async fn change_count(&self) -> i64;
    async fn first_item(&self, if_still: i64) -> Option<PasteboardItem>;
    async fn write(&self, text: String) -> Result<i64, PasteboardError>;
    /// For the cancellation check: sleeps on the Swift side, then returns.
    async fn slow(&self, ms: u64) -> String;
}

/// Reports back to the app, synchronous, like TorStateListener.
#[uniffi::export(with_foreign)]
pub trait Report: Send + Sync {
    fn line(&self, text: String);
}

#[derive(uniffi::Object)]
pub struct Spike {
    rt: tokio::runtime::Runtime,
    pb: Arc<dyn Pasteboard>,
    report: Arc<dyn Report>,
}

#[uniffi::export]
impl Spike {
    #[uniffi::constructor]
    pub fn new(pb: Arc<dyn Pasteboard>, report: Arc<dyn Report>) -> Arc<Self> {
        let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
        Arc::new(Self { rt, pb, report })
    }

    /// Runs every check on the crate's own runtime (not Swift's), reporting each; calls `done` at the end.
    pub fn run(&self) {
        let pb = self.pb.clone();
        let r = self.report.clone();
        self.rt.spawn(async move {
            let say = |s: String| r.line(s);

            // 1. Plain async calls, Rust → Swift → Rust
            let c0 = pb.change_count().await;
            let written = pb.write("seam spike".into()).await;
            let c1 = pb.change_count().await;
            let item = pb.first_item(c1).await;
            say(format!("1 basic: count {c0} → write {written:?} → count {c1}, item {item:?}"));

            // 2. A Swift error arrives as a Rust Err
            let err = pb.write("FAIL".into()).await;
            say(format!("2 error: {err:?}"));

            // 3. Stale change count: Swift returns None
            let stale = pb.first_item(c1 - 1).await;
            say(format!("3 stale read: {stale:?}"));

            // 4. Many at once from several tokio tasks
            let t = Instant::now();
            let mut set = tokio::task::JoinSet::new();
            for _ in 0..1000 {
                let pb = pb.clone();
                set.spawn(async move { pb.change_count().await });
            }
            let mut n = 0;
            while let Some(res) = set.join_next().await { res.unwrap(); n += 1; }
            say(format!("4 concurrency: {n} calls in {:?}", t.elapsed()));

            // 5. Latency of one round trip
            let t = Instant::now();
            for _ in 0..1000 { pb.change_count().await; }
            say(format!("5 latency: {:?} per call", t.elapsed() / 1000));

            // 6. Cancel a call that's waiting on Swift (timeout drops the future)
            let t = Instant::now();
            let cut = tokio::time::timeout(Duration::from_millis(100), pb.slow(2000)).await;
            say(format!("6 cancel: {:?} after {:?}", cut.map(|_| ()), t.elapsed()));
            // and the trait still works afterwards
            let after = pb.slow(10).await;
            say(format!("6b after cancel: {after}"));

            say("done".into());
        });
    }
}
