export function money(amount, currency = 'INR') {
  if (amount === null || amount === undefined) return ''
  if (Number(amount) === 0) return 'Free'
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency, maximumFractionDigits: 0 }).format(amount)
}

export function eventDate(iso) {
  return new Date(iso).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' })
}

export function eventTime(iso) {
  return new Date(iso).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' })
}

export function stamp(iso) {
  const d = new Date(iso)
  return { day: d.getDate(), month: d.toLocaleDateString('en-IN', { month: 'short' }).toUpperCase() }
}

export function dayLabel(isoDate) {
  // isoDate is a plain date (yyyy-mm-dd); parse as local to avoid timezone shifts.
  const [y, m, d] = isoDate.split('-').map(Number)
  return new Date(y, m - 1, d).toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' })
}

export function timeLabel(hhmm) {
  const [h, m] = hhmm.split(':').map(Number)
  const suffix = h >= 12 ? 'pm' : 'am'
  const hour = h % 12 === 0 ? 12 : h % 12
  return m === 0 ? `${hour}${suffix}` : `${hour}:${String(m).padStart(2, '0')}${suffix}`
}

export function minutesLabel(total) {
  if (total < 60) return `${total} min`
  const h = Math.floor(total / 60)
  const m = total % 60
  return m ? `${h} h ${m} min` : `${h} h`
}

export function durationBetween(start, end) {
  const toMin = (t) => { const [h, m] = t.split(':').map(Number); return h * 60 + m }
  return toMin(end) - toMin(start)
}

export function relativeTime(iso) {
  const diff = (Date.now() - new Date(iso).getTime()) / 1000
  if (diff < 60) return 'just now'
  if (diff < 3600) return `${Math.floor(diff / 60)} min ago`
  if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })
}

export function isoDate(date) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

export function addDays(date, days) {
  const copy = new Date(date)
  copy.setDate(copy.getDate() + days)
  return copy
}

/** Parse a plain yyyy-mm-dd date as local midnight (new Date('yyyy-mm-dd') would be UTC). */
export function parseIsoDate(value) {
  const [y, m, d] = value.split('-').map(Number)
  return new Date(y, m - 1, d)
}

export function shortDate(isoDateValue) {
  return parseIsoDate(isoDateValue).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })
}
