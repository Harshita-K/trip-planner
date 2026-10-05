import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import { Api } from '../api.js'
import { useAuth } from '../auth.jsx'
import CityCombobox from '../components/CityCombobox.jsx'
import GettingThere from '../components/GettingThere.jsx'
import WhereToStay from '../components/WhereToStay.jsx'
import { useToast } from '../components/Toast.jsx'
import { Button, CategoryTag, Chip, EmptyState } from '../components/ui.jsx'
import { DEFAULT_CITY, INTERESTS, PACES, category, cityFromParams } from '../data.js'
import { useCoverage } from '../coverage.js'
import { addDays, dayLabel, durationBetween, isoDate, minutesLabel, parseIsoDate, shortDate, timeLabel } from '../format.js'

// Interests that match sightseeing place categories (events-only ones like music don't apply).
const ITINERARY_INTERESTS = INTERESTS.filter((i) => !['food', 'music', 'comedy', 'books', 'art', 'sports', 'business'].includes(i))

/** Next Saturday, for `days` days (from a trip idea's ?days=, else a weekend). */
function defaultDates(days) {
  const today = new Date()
  const toSaturday = (6 - today.getDay() + 7) % 7 || 7
  const start = addDays(today, toSaturday)
  const length = Math.min(Math.max(Number(days) || 2, 1), 7)
  return { start: isoDate(start), end: isoDate(addDays(start, length - 1)) }
}

export default function Plan({ params }) {
  const { isLoggedIn, user, openAuth } = useAuth()
  const toast = useToast()
  const dates = defaultDates(params?.get('days'))
  const openSaved = params?.get('saved')
  const [form, setForm] = useState(() => ({
    city: (params && cityFromParams(params)) || DEFAULT_CITY,
    startDate: dates.start, endDate: dates.end, pace: 'balanced', interests: [],
  }))
  const coverage = useCoverage()
  const [touchedPrefs, setTouchedPrefs] = useState(false)
  const [plan, setPlan] = useState(null)
  const [saved, setSaved] = useState([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [lastRequest, setLastRequest] = useState(null)
  const [saving, setSaving] = useState(false)
  const [pendingSave, setPendingSave] = useState(false)

  // Start from the user's saved preferences until they change something here.
  useEffect(() => {
    if (user && !touchedPrefs) {
      setForm((f) => ({
        ...f,
        pace: user.travelPace || 'balanced',
        interests: (user.interests || []).filter((i) => ITINERARY_INTERESTS.includes(i)),
      }))
    }
  }, [user, touchedPrefs])

  useEffect(() => {
    if (!isLoggedIn) { setSaved([]); return }
    Api.itineraries().then((list) => {
      setSaved(list)
      // Arriving from My trips (#/plan?saved=<id>): show that plan.
      const wanted = openSaved && list.find((it) => it.id === openSaved)
      if (wanted) setPlan(wanted)
    }).catch(() => setSaved([]))
  }, [isLoggedIn, openSaved])

  // Stable object so the travel/stay sections don't refetch on every render.
  const destination = useMemo(() => (plan ? destinationOf(plan, form.city) : null), [plan, form.city])

  // Without live data (no OpenTripMap key on the server) only the curated cities have places.
  const disabledReason = useCallback(
    (o) => (coverage && !coverage.liveData && !o.curated ? 'live data not enabled yet' : null),
    [coverage])

  const set = (key, value) => setForm((f) => ({ ...f, [key]: value }))
  const toggleInterest = (i) => {
    setTouchedPrefs(true)
    set('interests', form.interests.includes(i) ? form.interests.filter((x) => x !== i) : [...form.interests, i])
  }

  async function submit(e) {
    e.preventDefault()
    if (form.endDate < form.startDate) {
      setError('Your trip has to end on or after the day it starts.')
      return
    }
    const city = form.city
    if (!city || city.lat == null) {
      setError('Choose a city from the list.')
      return
    }
    const body = {
      destination: city.label, lat: city.lat, lng: city.lng,
      startDate: form.startDate, endDate: form.endDate,
      travelPace: form.pace, interests: form.interests,
    }
    setLoading(true)
    setError('')
    try {
      // Logged in: generate and save. Visitors: preview only (nothing stored until they save).
      const result = isLoggedIn ? await Api.generateItinerary(body) : await Api.previewItinerary(body)
      setLastRequest(body)
      setPlan(result)
      if (result.id) {
        setSaved((s) => [result, ...s])
        toast('Your itinerary is ready and saved.')
      }
      setTimeout(() => document.getElementById('plan-result')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 50)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  // Saving re-runs the same request as a logged-in user; planning is deterministic, so it's the same plan.
  const savePlan = useCallback(async () => {
    if (!lastRequest) return
    setSaving(true)
    try {
      const result = await Api.generateItinerary(lastRequest)
      setPlan(result)
      setSaved((s) => [result, ...s])
      toast('Plan saved to your account.')
    } catch (err) {
      toast(err.message, 'error')
    } finally {
      setSaving(false)
      setPendingSave(false)
    }
  }, [lastRequest, toast])

  function onSave() {
    if (isLoggedIn) {
      savePlan()
    } else {
      setPendingSave(true)
      openAuth('signup', 'Create a free account to save this plan. It will be waiting for you.', { stay: true })
    }
  }

  // Finish a save that was waiting on login/sign-up.
  useEffect(() => {
    if (isLoggedIn && pendingSave) savePlan()
  }, [isLoggedIn, pendingSave, savePlan])

  return (
    <div className="container page">
      <header className="page-head">
        <p className="eyebrow">Itinerary planner</p>
        <h1>Plan a trip</h1>
        <p className="muted">Pick a city and your dates. We'll group nearby sights into days, fit each day around opening hours with a lunch break, and order the stops to cut travel time.</p>
      </header>

      <div className="plan-layout">
        <form className="card plan-form" onSubmit={submit}>
          <fieldset>
            <legend>Where?</legend>
            <CityCombobox value={form.city} onChange={(c) => set('city', c)} disabledReason={disabledReason} />
            {form.city && !form.city.curated && <p className="muted small">Live data from OpenStreetMap. Opening hours come from OSM where available, otherwise they're estimated.</p>}
          </fieldset>

          <fieldset>
            <legend>When?</legend>
            <div className="row">
              <label className="field">
                <span>From</span>
                <input type="date" value={form.startDate} min={isoDate(new Date())} onChange={(e) => set('startDate', e.target.value)} required />
              </label>
              <label className="field">
                <span>To</span>
                <input type="date" value={form.endDate} min={form.startDate} max={isoDate(addDays(parseIsoDate(form.startDate), 6))}
                  onChange={(e) => set('endDate', e.target.value)} required />
              </label>
            </div>
            <p className="muted small">Up to 7 days.</p>
          </fieldset>

          <fieldset>
            <legend>Your pace</legend>
            <div className="pace-options">
              {PACES.map((p) => (
                <label key={p.value} className={`pace ${form.pace === p.value ? 'on' : ''}`}>
                  <input type="radio" name="pace" value={p.value} checked={form.pace === p.value}
                    onChange={() => { setTouchedPrefs(true); set('pace', p.value) }} />
                  <strong>{p.label}</strong>
                  <span className="muted small">{p.detail}</span>
                </label>
              ))}
            </div>
          </fieldset>

          <fieldset>
            <legend>What do you love?</legend>
            <div className="chips">
              {ITINERARY_INTERESTS.map((i) => (
                <Chip key={i} selected={form.interests.includes(i)} onClick={() => toggleInterest(i)} icon={category(i).icon}>
                  {category(i).label}
                </Chip>
              ))}
            </div>
            <p className="muted small">Optional. Matching places get priority.</p>
          </fieldset>

          {error && <div className="error-note" role="alert">{error}</div>}
          <Button type="submit" loading={loading} className="btn-block" size="lg">
            ✨ Plan my trip
          </Button>
        </form>

        <div id="plan-result" className="plan-result">
          {plan ? (
            <>
              <Itinerary itinerary={plan} onSave={onSave} saving={saving} />
              <GettingThere destination={destination} date={plan.startDate} />
              <WhereToStay destination={destination} checkIn={plan.startDate}
                checkOut={isoDate(addDays(parseIsoDate(plan.endDate), 1))} />
            </>
          ) : (
            <EmptyState icon="🗺️" title="Your itinerary will appear here">
              Choose your dates and pace, then hit “Plan my trip”.
            </EmptyState>
          )}

          {saved.length > 0 && (
            <div className="saved">
              <h3>Your saved plans</h3>
              <ul>
                {saved.map((it) => (
                  <li key={it.id}>
                    <button className={`saved-item ${plan?.id === it.id ? 'on' : ''}`} onClick={() => setPlan(it)}>
                      <strong>{it.destination}</strong>
                      <span className="muted small">
                        {shortDate(it.startDate)} → {shortDate(it.endDate)} · {it.plan.days.reduce((n, d) => n + d.stops.length, 0)} stops
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * Where the trip is: the city picked in the form if it matches, otherwise the centre of the plan's
 * stops (saved plans don't store the city's coordinates).
 */
function destinationOf(itinerary, formCity) {
  if (formCity && formCity.label === itinerary.destination && formCity.lat != null) return formCity
  const stops = itinerary.plan.days.flatMap((d) => d.stops)
  if (!stops.length) return formCity
  const lat = stops.reduce((a, s) => a + s.lat, 0) / stops.length
  const lng = stops.reduce((a, s) => a + s.lng, 0) / stops.length
  return { key: `plan-${itinerary.destination}`, label: itinerary.destination, lat, lng }
}

/** The day's lunch break, with a nearby restaurant when we know one that's open then. */
function LunchStop({ lunch, travelToNextMin }) {
  // Exact coordinates, so Maps pins this place and not a namesake elsewhere.
  const mapsUrl = lunch.lat != null ? `https://www.google.com/maps/search/?api=1&query=${lunch.lat},${lunch.lng}` : null
  return (
    <li className="stop stop-lunch" style={{ '--hue': category('food').hue }}>
      <div className="stop-time">
        <strong>{timeLabel(lunch.start)}</strong>
        <span className="muted small">{timeLabel(lunch.end)}</span>
      </div>
      <div className="stop-dot" aria-hidden="true">🍽️</div>
      <div className="stop-body">
        <h4>Lunch break</h4>
        <p className="muted small">
          {lunch.name
            ? <>Try <a className="link" href={mapsUrl} target="_blank" rel="noreferrer">{lunch.name}</a>, {lunch.distanceKm} km away</>
            : 'Grab something nearby.'}
        </p>
        {travelToNextMin != null && <p className="travel">🚗 {minutesLabel(travelToNextMin)} to the next stop</p>}
      </div>
    </li>
  )
}

function Itinerary({ itinerary, onSave, saving }) {
  const { plan } = itinerary
  const stops = plan.days.reduce((n, d) => n + d.stops.length, 0)
  return (
    <div className="itinerary">
      {!itinerary.id && (
        <div className="save-banner">
          <div>
            <strong>This plan isn't saved yet</strong>
            <span className="muted small">Save it to your account to come back to it anytime.</span>
          </div>
          <Button size="sm" onClick={onSave} loading={saving}>💾 Save this plan</Button>
        </div>
      )}
      <div className="itinerary-head">
        <div>
          <p className="eyebrow">{plan.days.length}-day plan</p>
          <h2>{itinerary.destination}</h2>
        </div>
        <div className="itinerary-stats">
          <div><strong>{stops}</strong><span>stops</span></div>
          <div><strong>{minutesLabel(plan.totalTravelMinutes)}</strong><span>on the road</span></div>
        </div>
      </div>

      {plan.days.map((day, index) => (
        <section key={day.date} className="day">
          <h3><span className="day-num">Day {index + 1}</span> {dayLabel(day.date)}</h3>
          {day.stops.length === 0 ? (
            <p className="muted">A free day: rest, wander, or add your own plans.</p>
          ) : (
            <ol className="timeline">
              {day.stops.map((s, i) => {
                const lunchNext = day.lunch?.afterStops === i + 1
                return (
                  <Fragment key={s.placeId}>
                    {day.lunch?.afterStops === i && i === 0 && <LunchStop lunch={day.lunch} />}
                    <li className="stop" style={{ '--hue': category(s.category).hue }}>
                      <div className="stop-time">
                        <strong>{timeLabel(s.arrive)}</strong>
                        <span className="muted small">{timeLabel(s.leave)}</span>
                      </div>
                      <div className="stop-dot" aria-hidden="true">{category(s.category).icon}</div>
                      <div className="stop-body">
                        <h4>{s.name}</h4>
                        <div className="stop-meta">
                          <CategoryTag value={s.category} />
                          <span className="muted small">{minutesLabel(durationBetween(s.arrive, s.leave))} here</span>
                        </div>
                        {s.waitMin != null && (
                          <p className="wait">⏳ Opens at {timeLabel(s.arrive)}: you'll be there about {minutesLabel(s.waitMin)} early</p>
                        )}
                        {s.travelToNextMin != null && !lunchNext && (
                          <p className="travel">🚗 {minutesLabel(s.travelToNextMin)} to the next stop</p>
                        )}
                      </div>
                    </li>
                    {lunchNext && <LunchStop lunch={day.lunch} travelToNextMin={s.travelToNextMin} />}
                  </Fragment>
                )
              })}
            </ol>
          )}
        </section>
      ))}

      {plan.unscheduled.length > 0 && (
        <div className="unscheduled">
          <strong>Couldn't fit in this time:</strong> {plan.unscheduled.join(', ')}.
          <span className="muted"> Try a packed pace or an extra day.</span>
        </div>
      )}
    </div>
  )
}
