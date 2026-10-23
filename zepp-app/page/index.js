// Hex: Watch Reborn's honeycomb on a round Zepp OS watch (Amazfit T-Rex 3), filled with live
// readings instead of apps. Drag it around: bubbles grow toward the middle and shrink toward the
// edge (the fisheye measured from watchOS 27 footage, docs/RE_NOTES.md), and on release it glides
// to rest with the nearest bubble in the middle. The up/down buttons step through the bubbles like
// a crown; select (or a tap) opens the bubble's own watch app. Our own design and code.

import { createWidget, widget, align, prop, event, text_style } from '@zos/ui'
import * as sensors from '@zos/sensor'
import * as router from '@zos/router'
import { setScrollLock } from '@zos/page'
import { onKey, onGesture, KEY_UP, KEY_DOWN, KEY_SELECT, KEY_EVENT_CLICK } from '@zos/interaction'

const W = 480
const C = 240

// The full grid's measured proportions (screen widths), at this screen's width.
const PITCH = 0.274 * W
const CENTER_D = 0.242 * W * 1.18 // a touch larger: the middle bubble carries text
const SQRT3_2 = Math.sqrt(3) / 2

// Fisheye (Watch Reborn's Fisheye.kt): (distance in screen widths, size factor) and
// (lattice distance, drawn distance / lattice distance).
const SIZE = [0, 1, 0.27, 0.87, 0.43, 0.75, 0.49, 0.68, 0.62, 0.45, 0.78, 0]
const PULL = [0, 1, 0.27, 1, 0.47, 0.915, 0.8, 0.82, 1.5, 0.75]

const WHITE = 0xffffff

function lerpTable(t, x) {
  if (x <= t[0]) return t[1]
  for (let i = 0; i + 2 < t.length; i += 2) {
    if (x <= t[i + 2]) return t[i + 1] + (t[i + 3] - t[i + 1]) * (x - t[i]) / (t[i + 2] - t[i])
  }
  return t[t.length - 1]
}

/** The first [n] cells of a hex lattice, ring by ring from the middle. */
function spiral(n) {
  const dirs = [[1, 0], [1, -1], [0, -1], [-1, 0], [-1, 1], [0, 1]]
  const out = [[0, 0]]
  for (let ring = 1; out.length < n; ring++) {
    let q = dirs[4][0] * ring
    let r = dirs[4][1] * ring
    for (let side = 0; side < 6; side++) {
      for (let step = 0; step < ring; step++) {
        if (out.length === n) return out
        out.push([q, r])
        q += dirs[side][0]
        r += dirs[side][1]
      }
    }
  }
  return out
}

function sensor(name) {
  try {
    const Cls = sensors[name]
    return Cls ? new Cls() : null
  } catch (e) {
    return null
  }
}

/** The first of [methods] [s] has, called; objects with a value/score field give that. */
function read(s, ...methods) {
  if (!s) return null
  for (const m of methods) {
    try {
      if (typeof s[m] !== 'function') continue
      let v = s[m]()
      if (v && typeof v === 'object') v = 'value' in v ? v.value : 'score' in v ? v.score : null
      if (typeof v === 'number' && !isNaN(v)) return v
    } catch (e) {}
  }
  return null
}

const fmt = (v, suffix) => (v === null ? '--' : String(Math.round(v)) + (suffix || ''))
const pad2 = (n) => (n < 10 ? '0' : '') + n

Page({
  build() {
    setScrollLock({ lock: true })
    const time = sensor('Time')
    const s = {
      battery: sensor('Battery'), step: sensor('Step'), heart: sensor('HeartRate'),
      calorie: sensor('Calorie'), distance: sensor('Distance'), spo2: sensor('BloodOxygen'),
      stress: sensor('Stress'), stand: sensor('Stand'), pai: sensor('Pai'), weather: sensor('Weather'),
      fat: sensor('FatBurning'), sleep: sensor('Sleep'),
    }
    let h24 = true
    try {
      h24 = time.getHourFormat() !== sensors.TIME_HOUR_FORMAT_12
    } catch (e) {}

    function forecast() {
      try {
        const w = s.weather
        return w && (typeof w.getForecastWeather === 'function' ? w.getForecastWeather() : w.getForecast())
      } catch (e) {
        return null
      }
    }

    // What each bubble shows, and which watch app it opens. Most glanceable in the middle. Each
    // bubble's disc and label are a pre-drawn image (assets/default.r/b/<key>.png).
    const app = (name) => router[name]
    const items = [
      { key: 'time', open: app('SYSTEM_APP_WORLD_CLOCK'), value: () => {
        const h = time.getHours()
        const hh = h24 ? pad2(h) : String(h % 12 === 0 ? 12 : h % 12)
        return hh + ':' + pad2(time.getMinutes())
      } },
      { key: 'heart', open: app('SYSTEM_APP_HR'), value: () => fmt(read(s.heart, 'getLast', 'getCurrent')) },
      { key: 'steps', open: app('SYSTEM_APP_STATUS'), value: () => {
        const v = read(s.step, 'getCurrent')
        return v === null ? '--' : v >= 10000 ? (v / 1000).toFixed(1) + 'k' : String(v)
      } },
      { key: 'battery', open: app('SYSTEM_APP_SETTING'), value: () => fmt(read(s.battery, 'getCurrent'), '%') },
      { key: 'weather', open: app('SYSTEM_APP_WEATHER'), value: () => {
        const f = forecast()
        const d = f && f.forecastData && f.forecastData.count > 0 ? f.forecastData.data[0] : null
        return d && !(d.high === 0 && d.low === 0) ? d.high + '°/' + d.low + '°' : '--'
      } },
      { key: 'kcal', open: app('SYSTEM_APP_STATUS'), value: () => fmt(read(s.calorie, 'getCurrent')) },
      { key: 'km', open: app('SYSTEM_APP_STATUS'), value: () => {
        const v = read(s.distance, 'getCurrent')
        return v === null ? '--' : (v / 1000).toFixed(1)
      } },
      { key: 'spo2', open: app('SYSTEM_APP_SPO2'), value: () => fmt(read(s.spo2, 'getCurrent', 'getLastDay'), '%') },
      { key: 'stress', open: app('SYSTEM_APP_PRESSURE'), value: () => fmt(read(s.stress, 'getCurrent')) },
      { key: 'stand', open: app('SYSTEM_APP_STATUS'), value: () => fmt(read(s.stand, 'getCurrent')) },
      { key: 'pai', open: app('SYSTEM_APP_PAI'), value: () => fmt(read(s.pai, 'getTotal', 'getToday', 'getCurrent')) },
      { key: 'sun', open: app('SYSTEM_APP_SUN_AND_MOON'), value: () => {
        const f = forecast()
        const t = f && f.tideData && f.tideData.count > 0 ? f.tideData.data[0] : null
        if (!t || !t.sunrise || !t.sunset) return '--'
        const now = time.getHours() * 60 + time.getMinutes()
        const next = now < t.sunrise.hour * 60 + t.sunrise.minute || now >= t.sunset.hour * 60 + t.sunset.minute ? t.sunrise : t.sunset
        return (h24 ? next.hour : next.hour % 12 === 0 ? 12 : next.hour % 12) + ':' + pad2(next.minute)
      } },
      { key: 'rest', open: app('SYSTEM_APP_HR'), value: () => fmt(read(s.heart, 'getResting')) },
      { key: 'fat', open: app('SYSTEM_APP_STATUS'), value: () => fmt(read(s.fat, 'getCurrent')) },
      { key: 'sleep', open: app('SYSTEM_APP_SLEEP'), value: () => fmt(read(s.sleep, 'getInfo', 'getScore')) },
      { key: 'alarm', open: app('SYSTEM_APP_ALARM'), value: () => '▶' },
      { key: 'workout', open: app('SYSTEM_APP_SPORT'), value: () => '▶' },
      { key: 'compass', open: app('SYSTEM_APP_COMPASS'), value: () => '▶' },
      { key: 'timer', open: app('SYSTEM_APP_STOPWATCH'), value: () => '▶' },
    ]
    const cells = spiral(items.length)

    // Touch: every widget forwards presses, drags and releases here.
    let down = null
    let pan = { x: 0, y: 0 }
    let anim = null
    let lastLayout = 0
    function listen(w) {
      w.addEventListener(event.CLICK_DOWN, (e) => onDown(e))
      w.addEventListener(event.MOVE, (e) => onMove(e))
      w.addEventListener(event.CLICK_UP, (e) => onUp(e))
    }

    listen(createWidget(widget.FILL_RECT, { x: 0, y: 0, w: W, h: W, color: 0x000000 }))

    const bubbles = items.map((item) => {
      const b = {
        item,
        // Pre-drawn and anti-aliased (the watch draws its own circles with jagged edges); the
        // watch only moves and scales it, which is cheap.
        img: createWidget(widget.IMG, { x: C - 10, y: C - 10, w: 20, h: 20, src: 'b/' + item.key + '.png', auto_scale: true }),
        value: createWidget(widget.TEXT, {
          x: 0, y: 0, w: 10, h: 10, text_size: 20, color: WHITE, text: '--',
          align_h: align.CENTER_H, align_v: align.CENTER_V, text_style: text_style.NONE,
        }),
        // What was last sent to the watch, so unchanged properties aren't sent again.
        x: C, y: C, r: 0, sent: { img: '', value: '', bucket: -1, shown: true },
      }
      listen(b.img); listen(b.value)
      return b
    })

    /** Where cell [i] is drawn with the grid panned by [p]: centre and radius (0 = hidden). */
    function place(i, p) {
      const q = cells[i][0], r = cells[i][1]
      const dx = PITCH * (q + r / 2) + p.x
      const dy = PITCH * SQRT3_2 * r + p.y
      const d = Math.sqrt(dx * dx + dy * dy) / W
      const size = lerpTable(SIZE, d)
      const k = d > 1e-4 ? lerpTable(PULL, d) : 1
      return { x: C + dx * k, y: C + dy * k, r: (CENTER_D / 2) * size }
    }

    /**
     * The reading's text size for a bubble of radius [r]: three fixed sizes (or hidden), so text
     * doesn't jitter through sizes while dragging. The time is larger.
     */
    function bucket(r, isTime) {
      if (r >= 52) return isTime ? 48 : 28
      if (r >= 34) return isTime ? 32 : 20
      return 0
    }

    function layout() {
      for (let i = 0; i < bubbles.length; i++) {
        const b = bubbles[i]
        const pl = place(i, pan)
        const shown = pl.r > 9
        if (shown !== b.sent.shown) {
          b.sent.shown = shown
          b.img.setProperty(prop.VISIBLE, shown)
          if (!shown) b.value.setProperty(prop.VISIBLE, false)
        }
        if (!shown) continue
        b.x = pl.x; b.y = pl.y; b.r = pl.r
        const r = Math.round(pl.r)
        const x = Math.round(pl.x), y = Math.round(pl.y)
        const imgKey = x + ',' + y + ',' + r
        if (imgKey !== b.sent.img) {
          b.sent.img = imgKey
          b.img.setProperty(prop.MORE, { x: x - r, y: y - r, w: 2 * r, h: 2 * r, auto_scale: true })
        }
        const isTime = i === 0
        const size = bucket(r, isTime)
        if (size !== b.sent.bucket) {
          b.sent.bucket = size
          b.value.setProperty(prop.VISIBLE, size > 0)
          if (size > 0) b.value.setProperty(prop.MORE, { text_size: size, h: size + 10 })
          b.sent.value = ''
        }
        if (size === 0) continue
        // Readings sit below the label; the time is centred.
        const vy = isTime ? y - Math.round((size + 10) / 2) : y + Math.round(r * 0.12) - 4
        const valueKey = x + ',' + vy + ',' + r
        if (valueKey !== b.sent.value) {
          b.sent.value = valueKey
          b.value.setProperty(prop.MORE, { x: x - r, y: vy, w: 2 * r })
        }
      }
    }

    /** Glides the pan to [target] with an ease-out over ~0.3 s (the measured settle time). */
    function glide(target) {
      if (anim !== null) clearInterval(anim)
      const from = { x: pan.x, y: pan.y }
      const started = Date.now()
      anim = setInterval(() => {
        const t = Math.min(1, (Date.now() - started) / 300)
        const e = 1 - Math.pow(1 - t, 3)
        pan = { x: from.x + (target.x - from.x) * e, y: from.y + (target.y - from.y) * e }
        layout()
        if (t >= 1) { clearInterval(anim); anim = null }
      }, 33)
    }

    /** The pan that puts cell [i] in the middle. */
    function centreOn(i) {
      const q = cells[i][0], r = cells[i][1]
      return { x: -PITCH * (q + r / 2), y: -PITCH * SQRT3_2 * r }
    }

    function nearestToMiddle(p) {
      let best = 0, bestD = Infinity
      for (let i = 0; i < cells.length; i++) {
        const c = centreOn(i)
        const d = (c.x - p.x) * (c.x - p.x) + (c.y - p.y) * (c.y - p.y)
        if (d < bestD) { bestD = d; best = i }
      }
      return best
    }

    function onDown(e) {
      if (anim !== null) { clearInterval(anim); anim = null }
      down = { x: e.x, y: e.y, pan: { x: pan.x, y: pan.y }, moved: false, t: Date.now() }
    }

    function onMove(e) {
      if (!down) return
      const dx = e.x - down.x, dy = e.y - down.y
      if (!down.moved && dx * dx + dy * dy < 100) return
      down.moved = true
      pan = { x: down.pan.x + dx, y: down.pan.y + dy }
      // Only changed properties are sent now, so a layout per ~frame keeps up.
      const now = Date.now()
      if (now - lastLayout >= 16) { lastLayout = now; layout() }
    }

    function onUp(e) {
      if (!down) return
      const wasDrag = down.moved
      down = null
      if (wasDrag) {
        glide(centreOn(nearestToMiddle(pan)))
        return
      }
      // A tap: the topmost bubble under the finger opens its app.
      for (let i = bubbles.length - 1; i >= 0; i--) {
        const b = bubbles[i]
        if (!b.shown) continue
        const dx = e.x - b.x, dy = e.y - b.y
        if (dx * dx + dy * dy <= b.r * b.r) { open(b.item); return }
      }
    }

    function open(item) {
      if (item.open === undefined) return
      try {
        router.launchApp({ appId: item.open, native: true })
      } catch (e) {
        console.log('launch failed: ' + e)
      }
    }

    // The buttons step through the bubbles like a crown; select opens the middle one.
    onKey({
      callback: (key, keyEvent) => {
        if (keyEvent !== KEY_EVENT_CLICK) return false
        const current = nearestToMiddle(pan)
        if (key === KEY_UP || key === KEY_DOWN) {
          const next = (current + (key === KEY_DOWN ? 1 : items.length - 1)) % items.length
          glide(centreOn(next))
          return true
        }
        if (key === KEY_SELECT) { open(items[current]); return true }
        return false
      },
    })
    // Swipes belong to the honeycomb, not the system (the back button still leaves).
    onGesture({ callback: () => true })

    function refresh() {
      for (const b of bubbles) {
        try {
          b.value.setProperty(prop.MORE, { text: b.item.value() })
        } catch (e) {}
      }
    }

    layout()
    refresh()
    this.timer = setInterval(refresh, 2000)
  },

  onDestroy() {
    if (this.timer) clearInterval(this.timer)
  },
})
