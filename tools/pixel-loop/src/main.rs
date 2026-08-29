use std::{env, fmt::Write as FmtWrite, io::{self, Cursor, Write}, time::Duration};

use crossterm::{
    cursor::{Hide, Show},
    event::{self, Event, KeyCode},
    execute,
    terminal::{self, EnterAlternateScreen, LeaveAlternateScreen},
};
use gif::{ColorOutput, DecodeOptions};

static EMBEDDED_GIF: &[u8] = include_bytes!("../assets/video-pixel-loop.gif");

#[derive(Clone, Copy, PartialEq, Eq)]
struct Pixel { r: u8, g: u8, b: u8 }

struct Frame {
    width: usize,
    height: usize,
    pixels: Vec<Pixel>,
    delay: Duration,
}

struct RenderedFrame {
    bytes: Vec<u8>,
    delay: Duration,
}

#[derive(Clone, Copy)]
enum Fit { Width, Height, Fill }

fn usage() -> ! {
    eprintln!("usage: pixel-loop [--fit width|height|fill] [--fps N]");
    eprintln!("       the pixel-video material is embedded in this binary");
    eprintln!("       --fit fill  : stretch the pixel grid to the whole terminal (cols x rows*2)");
    eprintln!("       --fps N     : override the embedded frame delay (default: GIF native ~12fps)");
    eprintln!("       default fit : height");
    std::process::exit(2);
}

fn load_frames() -> Result<Vec<Frame>, Box<dyn std::error::Error>> {
    let mut options = DecodeOptions::new();
    options.set_color_output(ColorOutput::Indexed);
    let mut decoder = options.read_info(Cursor::new(EMBEDDED_GIF))?;
    let global = decoder.global_palette().map(|p| p.to_vec());
    let source_width = usize::from(decoder.width());
    let source_height = usize::from(decoder.height());
    let mut frames = Vec::new();
    let mut canvas = vec![Pixel { r: 0, g: 0, b: 0 }; source_width * source_height];

    while let Some(frame) = decoder.read_next_frame()? {
        let palette = frame.palette.as_deref().or(global.as_deref()).ok_or("GIF has no palette")?;
        let previous = if matches!(frame.dispose, gif::DisposalMethod::Previous) {
            Some(canvas.clone())
        } else {
            None
        };
        let transparent = frame.transparent;
        for y in 0..usize::from(frame.height) {
            for x in 0..usize::from(frame.width) {
                let index = frame.buffer[y * usize::from(frame.width) + x];
                if transparent == Some(index) {
                    continue;
                }
                let palette_index = usize::from(index) * 3;
                if palette_index + 2 >= palette.len() {
                    return Err("GIF palette index out of range".into());
                }
                let canvas_x = usize::from(frame.left) + x;
                let canvas_y = usize::from(frame.top) + y;
                if canvas_x >= source_width || canvas_y >= source_height {
                    continue;
                }
                canvas[canvas_y * source_width + canvas_x] = Pixel {
                    r: palette[palette_index],
                    g: palette[palette_index + 1],
                    b: palette[palette_index + 2],
                };
            }
        }
        let delay_cs = u64::from(frame.delay).max(1);
        frames.push(Frame {
            width: source_width,
            height: source_height,
            pixels: canvas.clone(),
            delay: Duration::from_millis(delay_cs * 10),
        });
        match frame.dispose {
            gif::DisposalMethod::Background => {
                for y in 0..usize::from(frame.height) {
                    for x in 0..usize::from(frame.width) {
                        let canvas_x = usize::from(frame.left) + x;
                        let canvas_y = usize::from(frame.top) + y;
                        if canvas_x < source_width && canvas_y < source_height {
                            canvas[canvas_y * source_width + canvas_x] = Pixel { r: 0, g: 0, b: 0 };
                        }
                    }
                }
            }
            gif::DisposalMethod::Previous => {
                if let Some(previous) = previous {
                    canvas = previous;
                }
            }
            gif::DisposalMethod::Keep | gif::DisposalMethod::Any => {}
        }
    }
    if frames.is_empty() { return Err("GIF contains no frames".into()); }
    Ok(frames)
}

fn dimensions(src_w: usize, src_h: usize, cols: usize, rows: usize, fit: Fit) -> (usize, usize) {
    let max_w = cols.max(1);
    let max_h = rows.saturating_mul(2).max(2);
    // Fill: ignore the source aspect ratio and cover the whole terminal pixel grid.
    let (mut w, mut h) = match fit {
        Fit::Width => (max_w, ((max_w * src_h) + src_w / 2) / src_w),
        Fit::Height => ( ((max_h * src_w) + src_h / 2) / src_h, max_h ),
        Fit::Fill => (max_w, max_h),
    };
    if w > max_w { w = max_w; h = ((w * src_h) + src_w / 2) / src_w; }
    if h > max_h { h = max_h; w = ((h * src_w) + src_h / 2) / src_h; }
    (w.max(1), h.max(1))
}

fn sample(frame: &Frame, x: usize, y: usize, out_w: usize, out_h: usize) -> Pixel {
    let sx = (x * frame.width / out_w).min(frame.width - 1);
    let sy = (y * frame.height / out_h).min(frame.height - 1);
    frame.pixels[sy * frame.width + sx]
}

fn prepare_frame(frame: &Frame, fit: Fit, cols: usize, rows: usize) -> RenderedFrame {
    let (w, h) = dimensions(frame.width, frame.height, cols, rows, fit);
    let cell_rows = (h + 1) / 2;
    let mut text = String::with_capacity(cell_rows * (w * 36 + 8) + 24);
    // DEC private mode 2026 makes the many PTY/parser chunks below one atomic
    // visual frame. TerminalParserWorker holds model-frame publication until
    // the reset marker arrives.
    text.push_str("\x1b[?2026h\x1b[H\x1b[2J");
    let mut current_foreground: Option<Pixel> = None;
    let mut current_background: Option<Pixel> = None;
    for cell_y in 0..cell_rows {
        let top_y = cell_y * 2;
        let bottom_y = (top_y + 1).min(h - 1);
        for x in 0..w {
            let top = sample(frame, x, top_y, w, h);
            let bottom = sample(frame, x, bottom_y, w, h);
            if current_foreground != Some(top) {
                let _ = write!(text, "\x1b[38;2;{};{};{}m", top.r, top.g, top.b);
                current_foreground = Some(top);
            }
            if current_background != Some(bottom) {
                let _ = write!(text, "\x1b[48;2;{};{};{}m", bottom.r, bottom.g, bottom.b);
                current_background = Some(bottom);
            }
            text.push('▀');
        }
        text.push_str("\x1b[0m");
        // A CRLF after the bottom terminal row scrolls the alternate screen by
        // one line on every video frame. Only move between rows, never past the
        // final row.
        if cell_y + 1 < cell_rows {
            text.push_str("\r\n");
        }
    }
    text.push_str("\x1b[?2026l");
    RenderedFrame { bytes: text.into_bytes(), delay: frame.delay }
}

fn prepare_frames(frames: &[Frame], fit: Fit, cols: usize, rows: usize) -> Vec<RenderedFrame> {
    frames.iter().map(|frame| prepare_frame(frame, fit, cols, rows)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame() -> Frame {
        Frame {
            width: 1,
            height: 2,
            pixels: vec![Pixel { r: 1, g: 2, b: 3 }, Pixel { r: 4, g: 5, b: 6 }],
            delay: Duration::from_millis(16),
        }
    }

    #[test]
    fn rendered_frame_is_wrapped_in_synchronized_output() {
        let rendered = prepare_frame(&frame(), Fit::Fill, 2, 2);
        assert!(rendered.bytes.starts_with(b"\x1b[?2026h\x1b[H\x1b[2J"));
        assert!(rendered.bytes.ends_with(b"\x1b[?2026l"));
    }

    #[test]
    fn repeated_cell_colors_reuse_sgr_state() {
        let rendered = prepare_frame(&Frame {
            width: 1,
            height: 2,
            pixels: vec![Pixel { r: 1, g: 2, b: 3 }, Pixel { r: 1, g: 2, b: 3 }],
            delay: Duration::from_millis(16),
        }, Fit::Fill, 4, 1);
        let text = String::from_utf8(rendered.bytes).unwrap();
        assert_eq!(text.matches("38;2;1;2;3m").count(), 1);
        assert_eq!(text.matches("48;2;1;2;3m").count(), 1);
        assert_eq!(text.matches('▀').count(), 4);
    }

    #[test]
    fn rendered_frame_does_not_scroll_after_last_row() {
        let rendered = prepare_frame(&frame(), Fit::Fill, 2, 2);
        let commit = b"\x1b[?2026l";
        let before_commit = rendered.bytes.strip_suffix(commit).unwrap_or(&rendered.bytes);
        assert!(!before_commit.ends_with(b"\r\n"));
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args().skip(1);
    let mut fit = Fit::Height;
    let mut fps_override: Option<u64> = None;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--fit" => {
                fit = match args.next().as_deref() {
                    Some("width") => Fit::Width,
                    Some("height") => Fit::Height,
                    Some("fill") => Fit::Fill,
                    _ => usage(),
                };
            }
            "--fps" => {
                fps_override = match args.next().as_deref().and_then(|s| s.parse::<u64>().ok()) {
                    Some(n) if n >= 1 && n <= 1000 => Some(n),
                    _ => usage(),
                };
            }
            _ => usage(),
        }
    }

    let frames = load_frames()?;
    let mut stdout = io::stdout();
    terminal::enable_raw_mode()?;
    execute!(stdout, EnterAlternateScreen, Hide)?;
    let result = (|| -> io::Result<()> {
        let (mut cols, mut rows) = terminal::size()?;
        let mut rendered = prepare_frames(&frames, fit, usize::from(cols), usize::from(rows));
        'animation: loop {
            for index in 0..rendered.len() {
                let delay = fps_override.map_or(rendered[index].delay, |fps| Duration::from_millis(1000 / fps));
                stdout.write_all(&rendered[index].bytes)?;
                stdout.flush()?;
                if event::poll(delay)? {
                    match event::read()? {
                        Event::Key(key) if matches!(key.code, KeyCode::Char('q') | KeyCode::Esc) => break 'animation,
                        Event::Resize(new_cols, new_rows) => {
                            cols = new_cols;
                            rows = new_rows;
                            rendered = prepare_frames(&frames, fit, usize::from(cols), usize::from(rows));
                            break;
                        }
                        _ => {}
                    }
                }
            }
        }
        Ok(())
    })();
    execute!(stdout, Show, LeaveAlternateScreen)?;
    terminal::disable_raw_mode()?;
    result?;
    Ok(())
}
