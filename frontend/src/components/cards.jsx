import { category } from '../data.js'
import { eventDate, eventTime, money, stamp, timeLabel } from '../format.js'
import { CategoryTag, Stars } from './ui.jsx'

export function EventCard({ event, reason, saved, onOpen }) {
  const c = category(event.category)
  const s = stamp(event.startTime)
  return (
    <button className="postcard" onClick={() => onOpen(event)} style={{ '--hue': c.hue }}>
      <div className="postcard-art">
        <span className="postcard-icon" aria-hidden="true">{c.icon}</span>
        <div className="stamp" aria-label={`${s.day} ${s.month}`}>
          <strong>{s.day}</strong>
          <span>{s.month}</span>
        </div>
      </div>
      <div className="postcard-body">
        <CategoryTag value={event.category} />
        <h3>{event.title}</h3>
        <p className="muted small">{event.venue} · {event.city}</p>
        <p className="muted small">{eventDate(event.startTime)} · {eventTime(event.startTime)}</p>
        {reason && <p className="reason">✨ {reason}</p>}
        <div className="postcard-foot">
          <strong className="price">{money(event.price, event.currency)}</strong>
          {saved
            ? <span className="pill pill-ok">✓ Saved</span>
            : event.community && <span className="pill">Community</span>}
        </div>
      </div>
    </button>
  )
}

/** A trip idea: opens the planner for that place and length. */
export function TripCard({ trip, href }) {
  return (
    <a className="trip-card" href={href}>
      <div className="trip-card-top">
        <span aria-hidden="true">🧳</span>
        <span className="pill pill-light">{trip.durationDays} {trip.durationDays === 1 ? 'day' : 'days'}</span>
      </div>
      <h3>{trip.destination}</h3>
      <p className="muted small">{trip.description}</p>
      <p className="trip-price">Plan this trip →</p>
    </a>
  )
}

export function PlaceCard({ ranked, compact, showCity }) {
  const p = ranked.place
  const c = category(p.category)
  return (
    <article className={`place ${compact ? 'place-compact' : ''}`} style={{ '--hue': c.hue }}>
      <div className="place-icon" aria-hidden="true">{c.icon}</div>
      <div className="place-body">
        <div className="place-title">
          <h4>{p.name}</h4>
          {isLive(p)
            ? (p.rating >= 4.1 && <span className="notable" title="Well known: has a Wikipedia article and/or is widely read about">Notable</span>)
            : <Stars rating={p.rating} />}
        </div>
        <p className="muted small">
          {c.label} · {showCity && p.city ? `${p.city} · ` : ''}{p.distanceKm} km {showCity ? 'from centre' : 'away'}
          {!compact && <> · Open {timeLabel(p.opens)}–{timeLabel(p.closes)} · ~{p.visitMinutes} min visit</>}
        </p>
        {!compact && <HoursNote place={p} />}
        {ranked.reason && <p className="reason">✨ {ranked.reason}</p>}
      </div>
    </article>
  )
}

/** Live places (OpenStreetMap / OpenTripMap / Wikipedia) have a notability or popularity score, not reviews, so no stars. */
function isLive(place) {
  return place.id.startsWith('osm-') || place.id.startsWith('otm-') || place.id.startsWith('wiki-')
}

const DAY_SHORT = { MONDAY: 'Mon', TUESDAY: 'Tue', WEDNESDAY: 'Wed', THURSDAY: 'Thu', FRIDAY: 'Fri', SATURDAY: 'Sat', SUNDAY: 'Sun' }
const DAY_ORDER = Object.keys(DAY_SHORT)

/** Where the hours come from, and weekly closures (e.g. museums closed on Mondays). */
function HoursNote({ place }) {
  const closed = (place.closedOn || []).slice().sort((a, b) => DAY_ORDER.indexOf(a) - DAY_ORDER.indexOf(b))
  const source = place.hoursSource === 'estimated' ? 'Hours estimated'
    : place.hoursSource === 'osm' ? 'Hours from OpenStreetMap' : null
  if (!closed.length && !source) return null
  return (
    <p className="hours-note small">
      {closed.length > 0 && <span className="closed-days">Closed {closed.map((d) => DAY_SHORT[d]).join(', ')}</span>}
      {closed.length > 0 && source && ' · '}
      {source && <span className="muted">{source}</span>}
    </p>
  )
}
