// Watch Reborn's modular-style digital face for round 480 x 480 Zepp OS watches (Amazfit T-Rex 3).
// Our own design, drawn from Zepp OS's documented widgets only (TEXT, ARC, CIRCLE, FILL_RECT):
// a ring of sixty ticks lit up to the current second, battery / date / heart rate on top, a large
// time with the seconds in the accent colour, a timeline of the coming hours, and steps /
// calories / distance at the bottom. It ticks once a second only while it's on screen.

import { createWidget, widget, align, prop, text_style } from '@zos/ui'
import * as sensors from '@zos/sensor'
import { TICKS } from './ticks'

const W = 480
const C = 240
const ACCENT = 0xa6e36e
const WHITE = 0xffffff
const GREY = 0xaaaaaa
const DISC = 0x1c1e1c
const TRACK = 0x5a5a5a

const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
const DAYS_LONG = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']

// Layout, in screen pixels: rows sit inside the tick ring (inner radius 212).
const TOP_Y = 112
const TOP_R = 38
const TOP_X = [150, 240, 330]
const TIME_Y = 150
const TIME_H = 130
const TIME_SIZE = 100
/** Rough advance widths of the system font, as fractions of the text size (it can't be measured). */
const DIGIT_W = 0.56
const COLON_W = 0.27
/** Width the time block may take: the screen's chord at the time's row, less a margin. */
const TIME_MAX_W = 400
const DATE_Y = 282
const LINE_LEFT = 96
const LINE_RIGHT = 384
const LABEL_Y = 326
const BAR_Y = 360
const BOTTOM_Y = 398
const BOTTOM_R = 34
const BOTTOM_X = [168, 240, 312]

/** A sensor by class name from @zos/sensor, or null where this watch doesn't have it. */
function sensor(name) {
  try {
    const Cls = sensors[name]
    return Cls ? new Cls() : null
  } catch (e) {
    return null
  }
}

/** Calls [method] on [s] if it has it; null otherwise. */
function read(s, method) {
  try {
    return s && typeof s[method] === 'function' ? num(s[method]()) : null
  } catch (e) {
    return null
  }
}

function num(v) {
  return typeof v === 'number' && !isNaN(v) ? v : null
}

function hourText(h, h24) {
  if (h24) return (h < 10 ? '0' : '') + h
  const hh = h % 12 === 0 ? 12 : h % 12
  return hh + (h < 12 ? 'A' : 'P')
}

WatchFace({
  onInit() {
    this.ticks = []
    this.litTo = -1
    this.lastMinute = -1
    this.lastDay = -1
    this.loop = null
  },

  build() {
    const time = sensor('Time')
    const battery = sensor('Battery')
    const step = sensor('Step')
    const heart = sensor('HeartRate')
    const calorie = sensor('Calorie')
    const distance = sensor('Distance')
    let h24 = true
    try {
      h24 = time.getHourFormat() !== sensors.TIME_HOUR_FORMAT_12
    } catch (e) {}

    // Sixty ticks round the edge (every fifth longer): a dim ring image, with each second's lit
    // tick laid over it and shown as the seconds pass (tools/make_ticks.py draws them).
    createWidget(widget.IMG, { x: 0, y: 0, src: 'ring_dim.png' })
    for (let i = 0; i < 60; i++) {
      const t = createWidget(widget.IMG, { x: TICKS[i][0], y: TICKS[i][1], src: 'tick/t' + (i < 10 ? '0' : '') + i + '.png' })
      t.setProperty(prop.VISIBLE, false)
      this.ticks.push(t)
    }

    // Top row: battery ring, date, heart rate.
    for (let i = 0; i < 3; i++) {
      createWidget(widget.CIRCLE, { center_x: TOP_X[i], center_y: TOP_Y, radius: TOP_R, color: DISC })
    }
    const batteryTrackBox = { x: TOP_X[0] - 31, y: TOP_Y - 31, w: 62, h: 62 }
    createWidget(widget.ARC, Object.assign({}, batteryTrackBox, {
      radius: 31, start_angle: -90, end_angle: 270, line_width: 6, color: 0x32402d,
    }))
    const batteryArc = createWidget(widget.ARC, Object.assign({}, batteryTrackBox, {
      radius: 31, start_angle: -90, end_angle: -90, line_width: 6, color: ACCENT,
    }))
    const batteryText = text(TOP_X[0] - 30, TOP_Y - 18, 60, 30, 24, WHITE, '--')
    text(TOP_X[0] - 30, TOP_Y + 8, 60, 18, 14, ACCENT, '%')

    const dayText = text(TOP_X[1] - 34, TOP_Y - 28, 68, 20, 17, ACCENT, '')
    const dateText = text(TOP_X[1] - 34, TOP_Y - 8, 68, 36, 32, WHITE, '')

    text(TOP_X[2] - 34, TOP_Y - 28, 68, 20, 17, 0xff5a5a, '♥')
    const heartText = text(TOP_X[2] - 34, TOP_Y - 8, 68, 36, 28, WHITE, '--')

    // The time: hours and minutes right-aligned to the split, seconds left-aligned after it.
    // Hours:minutes in white and :seconds in the accent colour, centred together as one block
    // (re-laid out when the hour's digit count changes, e.g. 9:59 to 10:00).
    const hmText = createWidget(widget.TEXT, {
      x: 0, y: TIME_Y, w: C, h: TIME_H, text_size: TIME_SIZE, color: WHITE,
      align_h: align.RIGHT, align_v: align.CENTER_V, text: '',
    })
    const secText = createWidget(widget.TEXT, {
      x: C, y: TIME_Y, w: C, h: TIME_H, text_size: TIME_SIZE, color: ACCENT,
      align_h: align.LEFT, align_v: align.CENTER_V, text: '',
    })
    let laidOutFor = ''
    function layoutTime(hm) {
      const digits = hm.replace(':', '').length + 2
      let size = TIME_SIZE
      let width = (digits * DIGIT_W + 2 * COLON_W) * size
      if (width > TIME_MAX_W) {
        size = Math.floor(size * TIME_MAX_W / width)
        width = (digits * DIGIT_W + 2 * COLON_W) * size
      }
      const hmW = Math.ceil(((digits - 2) * DIGIT_W + COLON_W) * size)
      const left = Math.round(C - width / 2)
      const pad = 8 // so a slightly wide glyph isn't clipped
      hmText.setProperty(prop.MORE, { x: left - pad, w: hmW + pad, text_size: size })
      secText.setProperty(prop.MORE, { x: left + hmW, w: W - (left + hmW), text_size: size })
    }
    const dateLine = text(60, DATE_Y, W - 120, 40, 30, WHITE, '')

    // Timeline: the next six hours, the current one filled.
    const hourW = (LINE_RIGHT - LINE_LEFT) / 6
    const labels = []
    for (let i = 1; i <= 5; i++) {
      labels.push(text(LINE_LEFT + i * hourW - 30, LABEL_Y, 60, 26, 20, GREY, ''))
    }
    for (let i = 0; i <= 6; i++) {
      createWidget(widget.FILL_RECT, {
        x: Math.round(LINE_LEFT + i * hourW) - 1, y: BAR_Y - 12, w: 2, h: 24, color: TRACK,
      })
    }
    createWidget(widget.FILL_RECT, {
      x: LINE_LEFT, y: BAR_Y - 3, w: LINE_RIGHT - LINE_LEFT, h: 6, radius: 3, color: TRACK,
    })
    const nowBar = createWidget(widget.FILL_RECT, {
      x: LINE_LEFT, y: BAR_Y - 5, w: 2, h: 10, radius: 5, color: WHITE,
    })

    // Bottom row: steps (ring toward the day's goal), calories, distance.
    for (let i = 0; i < 3; i++) {
      createWidget(widget.CIRCLE, { center_x: BOTTOM_X[i], center_y: BOTTOM_Y, radius: BOTTOM_R, color: DISC })
    }
    const stepBox = { x: BOTTOM_X[0] - 28, y: BOTTOM_Y - 28, w: 56, h: 56 }
    createWidget(widget.ARC, Object.assign({}, stepBox, {
      radius: 28, start_angle: -90, end_angle: 270, line_width: 5, color: 0x2d3a40,
    }))
    const stepArc = createWidget(widget.ARC, Object.assign({}, stepBox, {
      radius: 28, start_angle: -90, end_angle: -90, line_width: 5, color: 0x64d2ff,
    }))
    const stepText = text(BOTTOM_X[0] - 30, BOTTOM_Y - 12, 60, 24, 18, WHITE, '--')
    text(BOTTOM_X[1] - 30, BOTTOM_Y - 24, 60, 18, 14, 0xff9f0a, 'KCAL')
    const calText = text(BOTTOM_X[1] - 32, BOTTOM_Y - 6, 64, 28, 22, WHITE, '--')
    text(BOTTOM_X[2] - 30, BOTTOM_Y - 24, 60, 18, 14, ACCENT, 'KM')
    const distText = text(BOTTOM_X[2] - 32, BOTTOM_Y - 6, 64, 28, 22, WHITE, '--')

    const self = this

    function refresh() {
      if (!time) return
      const h = time.getHours()
      const m = time.getMinutes()
      const s = time.getSeconds()
      const day = time.getDate()

      // Ticks: light up to the current second; start over each minute.
      if (s < self.litTo) {
        for (let i = 0; i < 60; i++) self.ticks[i].setProperty(prop.VISIBLE, false)
        self.litTo = -1
      }
      for (let i = self.litTo + 1; i <= s; i++) self.ticks[i].setProperty(prop.VISIBLE, true)
      self.litTo = s
      secText.setProperty(prop.MORE, { text: ':' + (s < 10 ? '0' : '') + s })

      if (m !== self.lastMinute) {
        self.lastMinute = m
        const hh = h24 ? (h < 10 ? '0' : '') + h : String(h % 12 === 0 ? 12 : h % 12)
        const hm = hh + ':' + (m < 10 ? '0' : '') + m
        if (hm.length !== laidOutFor.length) { layoutTime(hm); laidOutFor = hm }
        hmText.setProperty(prop.MORE, { text: hm })
        nowBar.setProperty(prop.MORE, { w: Math.max(4, Math.round(hourW * m / 60)) })
        for (let i = 0; i < labels.length; i++) {
          labels[i].setProperty(prop.MORE, { text: hourText((h + i + 1) % 24, h24) })
        }
      }
      if (day !== self.lastDay) {
        self.lastDay = day
        const wd = time.getDay() - 1 // 1 = Monday
        dayText.setProperty(prop.MORE, { text: DAYS[wd] || '' })
        dateText.setProperty(prop.MORE, { text: String(day) })
        dateLine.setProperty(prop.MORE, { text: (DAYS_LONG[wd] || '') + ', ' + (MONTHS[time.getMonth() - 1] || '') + ' ' + day })
      }

      // Sensors: cheap reads, once a second while visible.
      const b = read(battery, 'getCurrent')
      batteryText.setProperty(prop.MORE, { text: b === null ? '--' : String(b) })
      batteryArc.setProperty(prop.MORE, { end_angle: -90 + 3.6 * (b || 0) })
      const hr = read(heart, 'getLast')
      heartText.setProperty(prop.MORE, { text: hr ? String(hr) : '--' })
      const st = read(step, 'getCurrent')
      const goal = read(step, 'getTarget')
      stepText.setProperty(prop.MORE, { text: st === null ? '--' : (st >= 10000 ? (st / 1000).toFixed(1) + 'k' : String(st)) })
      stepArc.setProperty(prop.MORE, { end_angle: -90 + 360 * Math.min(1, goal ? (st || 0) / goal : 0) })
      const kc = read(calorie, 'getCurrent')
      calText.setProperty(prop.MORE, { text: kc === null ? '--' : String(kc) })
      const dm = read(distance, 'getCurrent')
      distText.setProperty(prop.MORE, { text: dm === null ? '--' : (dm / 1000).toFixed(1) })
    }

    function start() {
      if (self.loop !== null) return
      self.litTo = 60 // forces the tick ring to redraw from scratch
      self.lastMinute = -1
      self.lastDay = -1
      refresh()
      self.loop = setInterval(refresh, 1000)
    }

    function stop() {
      if (self.loop === null) return
      clearInterval(self.loop)
      self.loop = null
    }

    // Only tick while the face is on screen (where the runtime offers the lifecycle widget).
    if (widget.WIDGET_DELEGATE !== undefined) {
      createWidget(widget.WIDGET_DELEGATE, { resume_call: start, pause_call: stop })
    }
    start()
  },

  onDestroy() {
    if (this.loop !== null) {
      clearInterval(this.loop)
      this.loop = null
    }
  },
})

function text(x, y, w, h, size, color, value) {
  return createWidget(widget.TEXT, {
    x: x, y: y, w: w, h: h, text_size: size, color: color,
    align_h: align.CENTER_H, align_v: align.CENTER_V,
    text_style: text_style.NONE, text: value,
  })
}
