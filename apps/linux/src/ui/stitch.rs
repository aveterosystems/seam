// SPDX-License-Identifier: AGPL-3.0-only
//! The stitch: the line under the phone's name that shows the connection (design-system.md, "The
//! stitch"; motion.md, catalogue 10). Solid teal on the same Wi‑Fi, drawn left to right; dashed
//! teal through Tor, its dashes flowing; grey dots travelling while connecting; broken grey and
//! still when offline. Styles crossfade over 400 ms. With animations off, nothing moves.

use std::cell::{Cell, RefCell};
use std::f64::consts::PI;
use std::rc::Rc;

use gtk::prelude::*;
use gtk::{cairo, glib};
use seam_looks::Line;

/// Tor's dashes flow at 11 px a second; the connecting dots travel at the same pace.
const FLOW: f64 = 11.0;
const DASH: f64 = 7.0;
const GAP: f64 = 5.0;
const DRAW_MS: f64 = 700.0;
const FADE_MS: f64 = 400.0;

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Solid,
    Dashed,
    Travelling,
    Broken,
    Paused,
}

struct State {
    kind: Kind,
    previous: Option<Kind>,
    /// When the current style arrived (frame clock µs), for the draw-in and the crossfade.
    since: Option<i64>,
    now: i64,
    tick: Option<gtk::TickCallbackId>,
}

#[derive(Clone)]
pub struct Stitch {
    pub widget: gtk::DrawingArea,
    state: Rc<RefCell<State>>,
    drawn: Rc<Cell<bool>>,
}

impl Stitch {
    pub fn new() -> Self {
        let widget = gtk::DrawingArea::builder().content_height(6).hexpand(true).build();
        widget.set_accessible_role(gtk::AccessibleRole::Presentation); // the words beside it say the same
        let state = Rc::new(RefCell::new(State {
            kind: Kind::Broken,
            previous: None,
            since: None,
            now: 0,
            tick: None,
        }));
        let s = state.clone();
        widget.set_draw_func(move |w, cr, width, height| draw(&s.borrow(), w, cr, width as f64, height as f64));
        let st = Self { widget, state, drawn: Rc::new(Cell::new(false)) };
        let me = st.clone();
        // Drawn in again each time it's shown: a surface opening redraws the line (catalogue 1)
        st.widget.connect_map(move |_| {
            me.drawn.set(false);
            me.restart();
        });
        st
    }

    /// The line for this state. `paused`: the calm paused blue.
    pub fn set(&self, line: Line, connecting: bool, paused: bool) {
        let kind = match (line, connecting, paused) {
            (_, _, true) => Kind::Paused,
            (_, true, _) => Kind::Travelling,
            (Line::Solid, ..) => Kind::Solid,
            (Line::Dashed, ..) => Kind::Dashed,
            (Line::Dotted, ..) => Kind::Travelling,
            (Line::Broken, ..) => Kind::Broken,
        };
        {
            let mut s = self.state.borrow_mut();
            if s.kind == kind {
                return;
            }
            s.previous = Some(s.kind);
            s.kind = kind;
            s.since = None;
        }
        self.restart();
    }

    fn restart(&self) {
        if self.state.borrow().tick.is_some() {
            return;
        }
        let state = self.state.clone();
        let id = self.widget.add_tick_callback(move |w, clock| {
            let now = clock.frame_time();
            let mut s = state.borrow_mut();
            s.now = now;
            let since = *s.since.get_or_insert(now);
            let elapsed = (now - since) as f64 / 1000.0;
            let moving = matches!(s.kind, Kind::Dashed | Kind::Travelling);
            let settling = elapsed < DRAW_MS.max(FADE_MS);
            if !settling {
                s.previous = None;
            }
            w.queue_draw();
            if (moving && crate::ui::animations()) || (settling && crate::ui::animations()) {
                glib::ControlFlow::Continue
            } else {
                s.since = Some(since - (DRAW_MS * 1000.0) as i64); // fully drawn
                s.previous = None;
                s.tick = None;
                glib::ControlFlow::Break
            }
        });
        self.state.borrow_mut().tick = Some(id);
    }
}

impl Default for Stitch {
    fn default() -> Self {
        Self::new()
    }
}

fn draw(s: &State, w: &gtk::DrawingArea, cr: &cairo::Context, width: f64, height: f64) {
    let p = crate::ui::palette();
    let animate = crate::ui::animations();
    let elapsed = s.since.map_or(f64::MAX, |since| (s.now - since) as f64 / 1000.0);
    let fade = if animate { (elapsed / FADE_MS).clamp(0.0, 1.0) } else { 1.0 };
    let flow = if animate { (s.now as f64 / 1_000_000.0) * FLOW } else { 0.0 };
    let y = height / 2.0;
    if let Some(prev) = s.previous {
        line(cr, prev, width, y, 1.0, flow, 1.0 - fade, &p);
    }
    // The settle curve (0.2, 0.8, 0.2, 1) for the draw-in, approximated as a strong ease-out
    let t = if animate { (elapsed / DRAW_MS).clamp(0.0, 1.0) } else { 1.0 };
    let drawn = if s.kind == Kind::Solid { 1.0 - (1.0 - t).powi(4) } else { 1.0 };
    line(cr, s.kind, width, y, drawn, flow, fade, &p);
    let _ = w;
}

#[allow(clippy::too_many_arguments)]
fn line(cr: &cairo::Context, kind: Kind, width: f64, y: f64, drawn: f64, flow: f64, alpha: f64, p: &crate::ui::Palette) {
    let colour = match kind {
        Kind::Solid | Kind::Dashed => p.teal,
        Kind::Paused => p.paused,
        Kind::Travelling | Kind::Broken => p.grey,
    };
    cr.set_source_rgba(colour.red() as f64, colour.green() as f64, colour.blue() as f64, colour.alpha() as f64 * alpha);
    cr.set_line_cap(cairo::LineCap::Round);
    match kind {
        Kind::Solid | Kind::Paused => {
            cr.set_line_width(2.0);
            cr.move_to(1.0, y);
            cr.line_to(1.0 + (width - 2.0) * drawn, y);
            let _ = cr.stroke();
        }
        Kind::Dashed => {
            cr.set_line_width(2.0);
            cr.set_dash(&[DASH, GAP], -(flow % (DASH + GAP)));
            cr.move_to(1.0, y);
            cr.line_to(width - 1.0, y);
            let _ = cr.stroke();
            cr.set_dash(&[], 0.0);
        }
        Kind::Travelling => {
            let step = 8.0;
            let mut x = (flow % step) + 1.0;
            while x < width - 1.0 {
                cr.arc(x, y, 1.2, 0.0, 2.0 * PI);
                let _ = cr.fill();
                x += step;
            }
        }
        Kind::Broken => {
            // Two still halves with a gap in the middle
            cr.set_line_width(1.5);
            let gap = 14.0_f64.min(width / 4.0);
            let mid = width / 2.0;
            cr.set_dash(&[3.0, 4.0], 0.0);
            cr.move_to(1.0, y);
            cr.line_to(mid - gap / 2.0, y);
            cr.move_to(mid + gap / 2.0, y);
            cr.line_to(width - 1.0, y);
            let _ = cr.stroke();
            cr.set_dash(&[], 0.0);
        }
    }
}
