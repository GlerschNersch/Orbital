// Watch Reborn Hex: a honeycomb of live data for round 480 x 480 Zepp OS watches (Amazfit T-Rex 3).
// Laid out like the watchOS 27 app cluster measured for Watch Reborn (docs/RE_NOTES.md): one large
// bubble in the middle, six around it on a flat-topped hex ring, and smaller bubbles beyond, which
// on a round screen fit in the gaps toward the edge. Here the middle shows the time and every other
// bubble a reading. Our own design, from Zepp OS's documented widgets only.

import { createWidget, widget, align, prop, text_style, event } from '@zos/ui'
import * as sensors from '@zos/sensor'
import * as router from '@zos/router'

/** The draggable honeycomb app (zepp-app), opened by tapping the time, like pressing the crown. */
const HEX_APP_ID = 920425

const C = 240

// The cluster, sized to the round screen: the ring's outer edge just inside the bezel.
const CENTER_D = 150
const RING_D = 118
const RING_DIST = 178
const OUTER_D = 60
const OUTER_DIST = 205

/** On wake the bubbles arrive this much larger and further out, then settle (as measured, ~0.3 s). */
const ZOOM_FROM = 1.35
const ZOOM_MS = 300

const WHITE = 0xffffff
const GREY = 0x9a9a9a

const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']

/** Each ring bubble: where it sits (degrees, 0 = three o'clock), its colour and its label. */
const RING = [
  { angle: -90, color: 0xff5a5a, label: 'BPM', key: 'heart' },
  { angle: -30, color: 0x64d2ff, label: 'STEPS', key: 'steps', ring: true },
  { angle: 30, color: 0xa6e36e, label: 'BATTERY', key: 'battery', ring: true },
  { angle: 90, color: 0xffbe3c, label: 'HI / LO', key: 'weather' },
  { angle: 150, color: 0xff9f0a, label: 'KCAL', key: 'calories' },
  { angle: 210, color: 0x50dcc8, label: 'KM', key: 'distance' },
]

/** The small bubbles in the gaps between the ring's, toward the edge. */
const OUTER = [
  { angle: -60, color: 0xa6e36e, label: 'SEC', key: 'seconds', ring: true },
  { angle: 0, color: 0x5ac8fa, label: 'SpO2', key: 'spo2' },
  { angle: 60, color: 0xbf5af2, label: 'STRESS', key: 'stress' },
  { angle: 120, color: 0x30d158, label: 'STAND', key: 'stand', ring: true },
  { angle: 180, color: 0xff375f, label: 'PAI', key: 'pai' },
  { angle: 240, color: 0xffd60a, label: 'SUN', key: 'sun' },
]

function at(angle, dist) {
  const a = angle * Math.PI / 180
  return { x: Math.round(C + dist * Math.cos(a)), y: Math.round(C + dist * Math.sin(a)) }
}

/** [color] darkened to a quarter, for a bubble's fill. */
function tint(color) {
  const r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255
  return ((r * 0.22) << 16) | ((g * 0.22) << 8) | (b * 0.22)
}

function sensor(name) {
  try {
    const Cls = sensors[name]
    return Cls ? new Cls() : null
  } catch (e) {
    return null
  }
}

/** The first of [methods] [s] has, called; objects with a value field give that value. */
function read(s, ...methods) {
  if (!s) return null
  for (const m of methods) {
    try {
      if (typeof s[m] !== 'function') continue
      let v = s[m]()
      if (v && typeof v === 'object' && 'value' in v) v = v.value
      if (typeof v === 'number' && !isNaN(v)) return v
    } catch (e) {}
  }
  return null
}

function text(x, y, w, h, size, color, value) {
  return createWidget(widget.TEXT, {
    x, y, w, h, text_size: size, color,
    align_h: align.CENTER_H, align_v: align.CENTER_V,
    text_style: text_style.NONE, text: value,
  })
}

/**
 * One data bubble: disc, optional progress ring, label above the value. [move] redraws it at
 * [k] times its distance and size from the middle, for the wake-up zoom.
 */
function bubble(spec, d, dist) {
  const big = d > 80
  // A FILL_RECT rounded into a circle: CIRCLE can't be moved once created.
  const disc = createWidget(widget.FILL_RECT, { x: C - d / 2, y: C - d / 2, w: d, h: d, radius: d / 2, color: tint(spec.color) })
  let track = null
  let arc = null
  if (spec.ring) {
    track = createWidget(widget.ARC, { x: 0, y: 0, w: 10, h: 10, radius: 5, line_width: big ? 6 : 4, start_angle: -90, end_angle: 270, color: 0x2a2a2a })
    arc = createWidget(widget.ARC, { x: 0, y: 0, w: 10, h: 10, radius: 5, line_width: big ? 6 : 4, start_angle: -90, end_angle: -90, color: spec.color })
  }
  const label = text(0, 0, d, big ? 22 : 16, big ? 17 : 12, spec.color, spec.label)
  const value = text(0, 0, d, big ? 40 : 22, big ? 34 : 18, WHITE, '--')
  function move(k) {
    const p = at(spec.angle, dist * k)
    const r = Math.round(d / 2 * k)
    disc.setProperty(prop.MORE, { x: p.x - r, y: p.y - r, w: 2 * r, h: 2 * r, radius: r })
    if (arc) {
      const rr = r - 5
      const box = { x: p.x - rr, y: p.y - rr, w: 2 * rr, h: 2 * rr, radius: rr }
      track.setProperty(prop.MORE, box)
      arc.setProperty(prop.MORE, box)
    }
    label.setProperty(prop.MORE, { x: p.x - r, y: Math.round(p.y - r * (big ? 0.62 : 0.6)), w: 2 * r })
    value.setProperty(prop.MORE, { x: p.x - r, y: p.y - (big ? 8 : 4), w: 2 * r })
  }
  move(1)
  return { arc, label, value, move }
}

WatchFace({
  onInit() {
    this.loop = null
  },

  build() {
    const time = sensor('Time')
    const s = {
      battery: sensor('Battery'), step: sensor('Step'), heart: sensor('HeartRate'),
      calorie: sensor('Calorie'), distance: sensor('Distance'), spo2: sensor('BloodOxygen'),
      stress: sensor('Stress'), stand: sensor('Stand'), pai: sensor('Pai'), weather: sensor('Weather'),
    }
    let h24 = true
    try {
      h24 = time.getHourFormat() !== sensors.TIME_HOUR_FORMAT_12
    } catch (e) {}

    // Middle: the time, with the date above and seconds below.
    const centre = createWidget(widget.CIRCLE, { center_x: C, center_y: C, radius: CENTER_D / 2, color: 0x1c1e1c })
    const dateText = text(C - 70, C - 58, 140, 24, 20, 0xa6e36e, '')
    const timeText = text(C - 75, C - 34, 150, 66, 56, WHITE, '')
    const ampmText = text(C - 70, C + 32, 140, 24, 18, GREY, '')
    // Tapping the time opens the honeycomb app, where the bubbles can be dragged around.
    const openHex = () => {
      try {
        router.launchApp({ appId: HEX_APP_ID, url: 'page/index' })
      } catch (e) {
        console.log('open Hex failed: ' + e)
      }
    }
    for (const w of [centre, dateText, timeText, ampmText]) {
      try {
        w.addEventListener(event.CLICK_UP, openHex)
      } catch (e) {}
    }

    const ring = {}
    for (const spec of RING) ring[spec.key] = bubble(spec, RING_D, RING_DIST)
    for (const spec of OUTER) ring[spec.key] = bubble(spec, OUTER_D, OUTER_DIST)

    const self = this
    let lastMinute = -1

    function set(key, value) {
      ring[key].value.setProperty(prop.MORE, { text: value })
    }
    function progress(key, fraction) {
      const f = Math.max(0, Math.min(1, fraction || 0))
      ring[key].arc.setProperty(prop.MORE, { end_angle: -90 + 360 * f })
    }
    function fmt(v, suffix) {
      return v === null ? '--' : String(Math.round(v)) + (suffix || '')
    }

    function refresh() {
      if (!time) return
      const h = time.getHours()
      const m = time.getMinutes()
      const sec = time.getSeconds()
      progress('seconds', sec / 60)
      set('seconds', (sec < 10 ? '0' : '') + sec)

      if (m !== lastMinute) {
        lastMinute = m
        const hh = h24 ? (h < 10 ? '0' : '') + h : String(h % 12 === 0 ? 12 : h % 12)
        timeText.setProperty(prop.MORE, { text: hh + ':' + (m < 10 ? '0' : '') + m })
        ampmText.setProperty(prop.MORE, { text: h24 ? '' : (h < 12 ? 'AM' : 'PM') })
        dateText.setProperty(prop.MORE, { text: (DAYS[time.getDay() - 1] || '') + ' ' + time.getDate() + ' ' + (MONTHS[time.getMonth() - 1] || '') })
        refreshSlow(h, m)
      }

      const hr = read(s.heart, 'getLast', 'getCurrent')
      set('heart', hr ? String(hr) : '--')
      const st = read(s.step, 'getCurrent')
      const goal = read(s.step, 'getTarget')
      set('steps', st === null ? '--' : st >= 10000 ? (st / 1000).toFixed(1) + 'k' : String(st))
      progress('steps', goal ? (st || 0) / goal : 0)
      const b = read(s.battery, 'getCurrent')
      set('battery', fmt(b, '%'))
      progress('battery', (b || 0) / 100)
      set('calories', fmt(read(s.calorie, 'getCurrent')))
      const dm = read(s.distance, 'getCurrent')
      set('distance', dm === null ? '--' : (dm / 1000).toFixed(1))
    }

    /** Readings that change slowly: once a minute. */
    function refreshSlow(h, m) {
      set('spo2', fmt(read(s.spo2, 'getCurrent', 'getLastDay'), '%'))
      set('stress', fmt(read(s.stress, 'getCurrent')))
      const stand = read(s.stand, 'getCurrent')
      const standGoal = read(s.stand, 'getTarget')
      set('stand', fmt(stand))
      progress('stand', standGoal ? (stand || 0) / standGoal : 0)
      set('pai', fmt(read(s.pai, 'getTotal', 'getToday', 'getCurrent')))

      // Weather: today's high / low, and the next sunrise or sunset.
      let forecast = null
      try {
        const w = s.weather
        forecast = w && (typeof w.getForecastWeather === 'function' ? w.getForecastWeather() : w.getForecast())
      } catch (e) {}
      let today = forecast && forecast.forecastData && forecast.forecastData.count > 0 ? forecast.forecastData.data[0] : null
      // No forecast synced yet reads as 0 / 0.
      if (today && today.high === 0 && today.low === 0) today = null
      set('weather', today ? today.high + '°/' + today.low + '°' : '--')
      const tide = forecast && forecast.tideData && forecast.tideData.count > 0 ? forecast.tideData.data[0] : null
      if (tide && tide.sunrise && tide.sunset) {
        const now = h * 60 + m
        const rise = tide.sunrise.hour * 60 + tide.sunrise.minute
        const setT = tide.sunset.hour * 60 + tide.sunset.minute
        const next = now < rise || now >= setT ? tide.sunrise : tide.sunset
        ring.sun.label.setProperty(prop.MORE, { text: next === tide.sunrise ? 'RISE' : 'SET' })
        const nh = h24 ? next.hour : (next.hour % 12 === 0 ? 12 : next.hour % 12)
        set('sun', nh + ':' + (next.minute < 10 ? '0' : '') + next.minute)
      } else {
        set('sun', '--')
      }
    }

    /** The wake-up zoom: bubbles arrive larger and further out and settle into place. */
    let zoom = null
    function zoomIn() {
      if (zoom !== null) clearInterval(zoom)
      const started = Date.now()
      const all = Object.keys(ring).map((k) => ring[k])
      const step = () => {
        const t = Math.min(1, (Date.now() - started) / ZOOM_MS)
        const e = 1 - Math.pow(1 - t, 3)
        const k = ZOOM_FROM + (1 - ZOOM_FROM) * e
        for (const b of all) b.move(k)
        if (t >= 1 && zoom !== null) { clearInterval(zoom); zoom = null }
      }
      step()
      zoom = setInterval(step, 33)
    }

    function start() {
      if (self.loop !== null) return
      lastMinute = -1
      refresh()
      self.loop = setInterval(refresh, 1000)
      zoomIn()
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
