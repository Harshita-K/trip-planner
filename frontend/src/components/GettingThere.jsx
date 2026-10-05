import { useEffect, useState } from 'react'
import { Api } from '../api.js'
import { minutesLabel, money } from '../format.js'
import CityCombobox from './CityCombobox.jsx'
import { ErrorNote, Spinner } from './ui.jsx'

const MODE = {
  flight: { icon: '✈️', label: 'Flight' },
  train: { icon: '🚆', label: 'Train' },
  bus: { icon: '🚌', label: 'Bus' },
  car: { icon: '🚗', label: 'Car' },
}

/** F8: every way to reach the destination from a chosen origin, for the trip's first day. */
export default function GettingThere({ destination, date }) {
  const [origin, setOrigin] = useState(null)
  const [travellers, setTravellers] = useState(2)
  const [plan, setPlan] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(null)

  useEffect(() => {
    if (!origin || !destination) return
    let active = true
    setLoading(true)
    setError(null)
    setOpen(null)
    Api.travel({
      fromName: origin.label, fromLat: origin.lat, fromLng: origin.lng,
      toName: destination.label, toLat: destination.lat, toLng: destination.lng, date, travellers,
    })
      .then((p) => active && setPlan(p))
      .catch((e) => active && (setError(e), setPlan(null)))
      .finally(() => active && setLoading(false))
    return () => { active = false }
  }, [origin, destination, date, travellers])

  return (
    <section className="card trip-extra">
      <div className="trip-extra-head">
        <div>
          <p className="eyebrow">Getting there</p>
          <h3>How do you reach {destination.label}?</h3>
        </div>
        <div className="trip-extra-controls">
          <div className="extra-origin">
            <span className="filter-label">Travelling from</span>
            <CityCombobox value={origin} onChange={setOrigin} placeholder="Your city" />
          </div>
          <label className="extra-small">
            <span className="filter-label">Travellers</span>
            <select value={travellers} onChange={(e) => setTravellers(Number(e.target.value))}>
              {[1, 2, 3, 4, 5, 6, 7, 8].map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
        </div>
      </div>

      {!origin && <p className="muted">Pick where you're starting from to compare flights, trains, buses and driving.</p>}
      {loading && <Spinner label="Comparing routes…" />}
      <ErrorNote error={error} />
      {plan && !loading && (
        <>
          <div className="modes">
            {plan.options.map((o) => (
              <article key={o.mode} className={`mode ${open === o.mode ? 'open' : ''}`}>
                <button className="mode-main" onClick={() => setOpen(open === o.mode ? null : o.mode)} aria-expanded={open === o.mode}>
                  <span className="mode-icon" aria-hidden="true">{MODE[o.mode].icon}</span>
                  <span className="mode-body">
                    <span className="mode-title">
                      <strong>{o.title}</strong>
                      {o.badges.map((b) => <span key={b} className={`pill badge-${b.toLowerCase()}`}>{b}</span>)}
                    </span>
                    <span className="muted small">{o.route} · {Math.round(o.distanceKm)} km</span>
                  </span>
                  <span className="mode-stats">
                    <strong>{minutesLabel(o.doorToDoorMinutes)}</strong>
                    <span className="muted small">door to door</span>
                  </span>
                  <span className="mode-stats">
                    <strong>{o.mode === 'car' ? money(o.fromPrice) : `from ${money(o.fromPrice)}`}</strong>
                    <span className="muted small">{o.mode === 'car' ? 'per car' : 'per person'}</span>
                  </span>
                </button>
                {open === o.mode && (
                  <div className="mode-detail">
                    {o.departures.length > 0 && (
                      <table className="departures">
                        <thead><tr><th>Option</th><th>Departs</th><th>Arrives</th><th>Duration</th><th>Fares</th></tr></thead>
                        <tbody>
                          {o.departures.map((d) => (
                            <tr key={d.label + d.depart}>
                              <td>{d.label}</td><td>{d.depart}</td><td>{d.arrive}</td><td>{minutesLabel(d.durationMinutes)}</td>
                              <td>{d.fares.map((f) => <span key={f.travelClass} className="fare">{f.travelClass} {money(f.price)}</span>)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                    <ul className="mode-notes">
                      <li>Total for {travellers} {travellers === 1 ? 'traveller' : 'travellers'}: <strong>{money(o.total)}</strong> · about {o.co2KgPerPerson} kg CO₂ per person</li>
                      {o.notes.map((n) => <li key={n}>{n}</li>)}
                    </ul>
                  </div>
                )}
              </article>
            ))}
          </div>
          <p className="muted small disclaimer">
            {plan.roadDistanceEstimated ? 'Road distance estimated. ' : 'Road distances from OpenStreetMap (OSRM). '}{plan.disclaimer}
          </p>
        </>
      )}
    </section>
  )
}
