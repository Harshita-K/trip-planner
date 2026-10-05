import { useCallback, useEffect, useMemo, useState } from 'react'
import { Api } from '../api.js'
import { useAuth } from '../auth.jsx'
import CityCombobox from '../components/CityCombobox.jsx'
import { PlaceCard } from '../components/cards.jsx'
import { Chip, EmptyState, ErrorNote, Spinner } from '../components/ui.jsx'
import { useCoverage } from '../coverage.js'
import { ALL_INDIA, DEFAULT_CITY, PLACE_CATEGORIES, category, cityFromParams, cityOption } from '../data.js'

const LIMIT = 40
/** "All of India" merges these many popular cities (curated and bundled ones are served from memory). */
const ALL_INDIA_CITIES = 6

export default function Discover({ params }) {
  const { isLoggedIn } = useAuth()
  const coverage = useCoverage()
  const [city, setCity] = useState(() => (params && cityFromParams(params)) || DEFAULT_CITY)
  const [radius, setRadius] = useState(5)
  const [cats, setCats] = useState([])
  const [places, setPlaces] = useState(null)
  const [error, setError] = useState(null)
  const extraOptions = useMemo(() => [ALL_INDIA], [])
  const disabledReason = useCallback(
    (o) => (coverage && !coverage.liveData && !o.curated ? 'live data not enabled yet' : null),
    [coverage])

  const load = useCallback(async () => {
    if (!coverage) return
    setPlaces(null)
    setError(null)
    try {
      let targets = [city]
      if (city.all) {
        const popular = (await Api.cities('')).map(cityOption)
        targets = popular.filter((c) => c.curated || coverage.liveData).slice(0, ALL_INDIA_CITIES)
      }
      const lists = await Promise.all(targets.map((t) =>
        Api.nearbyPlaces({ lat: t.lat, lng: t.lng, radiusKm: radius, category: cats, limit: LIMIT })
          // Live places don't carry a city name; label them with the city we searched.
          .then((list) => list.map((r) => ({ ...r, place: { ...r.place, city: r.place.city || t.label } })))))
      setPlaces(lists.flat().sort((a, b) => b.score - a.score).slice(0, LIMIT))
    } catch (e) {
      setError(e)
    }
  }, [city, radius, cats, coverage])

  // Debounce so dragging the radius slider doesn't fire a request per pixel.
  useEffect(() => {
    const t = setTimeout(load, 250)
    return () => clearTimeout(t)
  }, [load])

  const toggle = (c) => setCats((cs) => (cs.includes(c) ? cs.filter((x) => x !== c) : [...cs, c]))
  const where = city.all ? 'across India' : `around ${city.label}`

  return (
    <div className="container page">
      <header className="page-head">
        <p className="eyebrow">Discover</p>
        <h1>Places worth the detour</h1>
        <p className="muted">
          Sights, food and hidden gems {where}
          {isLoggedIn ? ', ranked for your tastes.' : '. Log in and we\'ll rank them for your tastes.'}
        </p>
      </header>

      <div className="card filters">
        <div className="filters-city">
          <span className="filter-label">City</span>
          <CityCombobox value={city} onChange={setCity} extraOptions={extraOptions} disabledReason={disabledReason} />
        </div>
        <label className="range">
          <span className="filter-label">Within <strong>{radius} km</strong> {city.all ? 'of each city centre' : 'of the centre'}</span>
          <input type="range" min="1" max="20" value={radius} onChange={(e) => setRadius(Number(e.target.value))} />
        </label>
        <div className="chips">
          <Chip selected={cats.length === 0} onClick={() => setCats([])}>Everything</Chip>
          {PLACE_CATEGORIES.map((c) => (
            <Chip key={c} selected={cats.includes(c)} onClick={() => toggle(c)} icon={category(c).icon}>{category(c).label}</Chip>
          ))}
        </div>
      </div>

      <ErrorNote error={error} onRetry={load} />
      {!places && !error && <Spinner label={city.curated || city.all ? 'Exploring…' : `Fetching live places for ${city.label}…`} />}
      {places && places.length === 0 && (
        <EmptyState icon="🔭" title="Nothing found nearby">Try a bigger radius or another category.</EmptyState>
      )}
      {places && places.length > 0 && (
        <>
          <p className="muted small results-count">{places.length} places · best matches first</p>
          <div className="grid grid-places">
            {places.map((r) => <PlaceCard key={r.place.id} ranked={r} showCity={city.all} />)}
          </div>
        </>
      )}
    </div>
  )
}
