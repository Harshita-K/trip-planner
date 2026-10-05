import { useEffect, useState } from 'react'
import { Api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { money } from '../format.js'
import { Chip, EmptyState, ErrorNote, Spinner } from './ui.jsx'

const BUDGETS = [
  { value: 'low', label: 'Budget' },
  { value: 'mid', label: 'Comfort' },
  { value: 'high', label: 'Luxury' },
]
const TIER_LABEL = { budget: 'Budget', comfort: 'Comfort', luxury: 'Luxury' }

/** F9: real places to stay near the destination, with indicative prices for the trip dates. */
export default function WhereToStay({ destination, checkIn, checkOut }) {
  const { user } = useAuth()
  const [budget, setBudget] = useState(user?.budgetLevel || 'mid')
  const [guests, setGuests] = useState(2)
  const [showAll, setShowAll] = useState(false)
  const [stay, setStay] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => { if (user?.budgetLevel) setBudget(user.budgetLevel) }, [user?.budgetLevel])

  useEffect(() => {
    let active = true
    setStay(null)
    setError(null)
    Api.hotels({ name: destination.label, lat: destination.lat, lng: destination.lng, checkIn, checkOut, guests, budget })
      .then((s) => active && setStay(s))
      .catch((e) => active && setError(e))
    return () => { active = false }
  }, [destination, checkIn, checkOut, guests, budget])

  const matching = stay?.hotels.filter((h) => h.matchesBudget) || []
  const shown = showAll || matching.length === 0 ? stay?.hotels || [] : matching

  return (
    <section className="card trip-extra">
      <div className="trip-extra-head">
        <div>
          <p className="eyebrow">Where to stay</p>
          <h3>Places to stay in {destination.label}</h3>
          {stay && <p className="muted small">{stay.nights} {stay.nights === 1 ? 'night' : 'nights'} · {checkIn} → {checkOut}</p>}
        </div>
        <div className="trip-extra-controls">
          <div className="chips">
            {BUDGETS.map((b) => (
              <Chip key={b.value} selected={budget === b.value} onClick={() => { setBudget(b.value); setShowAll(false) }}>{b.label}</Chip>
            ))}
          </div>
          <label className="extra-small">
            <span className="filter-label">Guests</span>
            <select value={guests} onChange={(e) => setGuests(Number(e.target.value))}>
              {[1, 2, 3, 4, 5, 6].map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
        </div>
      </div>

      <ErrorNote error={error} />
      {!stay && !error && <Spinner label="Finding places to stay…" />}
      {stay && stay.hotels.length === 0 && (
        <EmptyState icon="🏨" title="No places to stay found nearby">OpenStreetMap doesn't list stays around here yet.</EmptyState>
      )}
      {stay && stay.hotels.length > 0 && (
        <>
          {matching.length === 0 && <p className="muted small">Nothing tagged as {BUDGETS.find((b) => b.value === budget).label.toLowerCase()} here, so showing everything nearby.</p>}
          <div className="stays">
            {shown.map((h) => (
              <article key={h.id} className="stay">
                <div className="stay-top">
                  <span className={`pill tier-${h.tier}`}>{TIER_LABEL[h.tier]}</span>
                  <span className="muted small">{h.kind} · {h.distanceKm} km</span>
                </div>
                <h4>{h.name}</h4>
                {h.street && <p className="muted small">{h.street}</p>}
                <div className="stay-price">
                  <strong>~{money(h.perNight)}</strong><span className="muted small"> / {h.kind === 'Hostel' ? 'bed' : 'room'} / night</span>
                  <div className="muted small">~{money(h.total)} total{h.rooms > 1 ? ` · ${h.rooms} ${h.kind === 'Hostel' ? 'beds' : 'rooms'}` : ''}</div>
                </div>
                <div className="stay-links">
                  <a href={h.mapsUrl} target="_blank" rel="noreferrer">Check on Google Maps ↗</a>
                  {h.osmUrl && <a href={h.osmUrl} target="_blank" rel="noreferrer">OpenStreetMap ↗</a>}
                </div>
              </article>
            ))}
          </div>
          {matching.length > 0 && matching.length < stay.hotels.length && (
            <button className="link" onClick={() => setShowAll((v) => !v)}>
              {showAll ? `Show only ${BUDGETS.find((b) => b.value === budget).label.toLowerCase()} stays` : `Show all ${stay.hotels.length} places nearby`}
            </button>
          )}
          <p className="muted small disclaimer">{stay.disclaimer}</p>
        </>
      )}
    </section>
  )
}
