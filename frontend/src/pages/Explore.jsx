import { useCallback, useEffect, useMemo, useState } from 'react'
import { Api } from '../api.js'
import { useAuth } from '../auth.jsx'
import CityCombobox from '../components/CityCombobox.jsx'
import EventModal from '../components/EventModal.jsx'
import { EventCard, TripCard } from '../components/cards.jsx'
import { Button, Chip, EmptyState, ErrorNote, SectionHeader, Spinner } from '../components/ui.jsx'
import { ALL_INDIA, CITIES, EVENT_CATEGORIES, category, cityParams } from '../data.js'
import { useSaved } from '../saved.jsx'

/** Opens the planner for a trip idea: its place and length, starting next Saturday. */
function tripHref(trip) {
  return `#/plan?${cityParams({ label: trip.destination, lat: trip.lat, lng: trip.lng })}&days=${trip.durationDays}`
}

export default function Explore() {
  const { user, isLoggedIn, openAuth } = useAuth()
  const { isSaved } = useSaved()
  const [cityOption, setCityOption] = useState(ALL_INDIA)
  const city = cityOption.all ? '' : cityOption.label
  const [cat, setCat] = useState('')
  const [events, setEvents] = useState(null)
  const [trips, setTrips] = useState([])
  const [feed, setFeed] = useState(null)
  const [error, setError] = useState(null)
  const [selected, setSelected] = useState(null)
  const extraOptions = useMemo(() => [ALL_INDIA], [])

  const loadEvents = useCallback(() => {
    setEvents(null)
    setError(null)
    Api.events({ city, category: cat, size: 50 }).then((p) => setEvents(p.items)).catch(setError)
  }, [city, cat])

  const loadFeed = useCallback(() => {
    if (!isLoggedIn) { setFeed(null); return }
    Api.feed(city).then(setFeed).catch(() => setFeed(null))
  }, [isLoggedIn, city])

  useEffect(loadEvents, [loadEvents])
  useEffect(loadFeed, [loadFeed])
  useEffect(() => { Api.trips().then(setTrips).catch(() => setTrips([])) }, [])

  const firstName = user?.name?.split(' ')[0]
  const cityInfo = CITIES.find((c) => c.name === city)

  return (
    <>
      <section className="hero">
        <div className="hero-bg" aria-hidden="true"><div className="hero-sun" /></div>
        <div className="container hero-inner">
          <p className="eyebrow">{cityInfo ? cityInfo.tagline : 'Events, day plans & the best local spots across India'}</p>
          <h1>{firstName ? `Where to next, ${firstName}?` : 'Find your next great day out.'}</h1>
          <p className="hero-sub">Find gigs, walks and festivals, save the ones you like, and let Wanderly plan the rest of your trip around them.</p>
          <div className="hero-search">
            <span className="hero-search-label">Where to?</span>
            <CityCombobox value={cityOption} onChange={setCityOption} extraOptions={extraOptions} placeholder="Type any city, or pick All of India" />
          </div>
        </div>
        <svg className="hero-wave" viewBox="0 0 1440 60" preserveAspectRatio="none" aria-hidden="true">
          <path d="M0,40 C240,10 480,60 720,35 C960,10 1200,55 1440,30 L1440,60 L0,60 Z" />
        </svg>
      </section>

      <div className="container page">
        {isLoggedIn && feed && feed.items.length > 0 && (
          <section className="section">
            <SectionHeader title="Picked for you" subtitle="Based on your interests and what you've been exploring" />
            <div className="grid grid-cards">
              {feed.items.map((item) => (
                <EventCard key={item.event.id} event={item.event} reason={item.reason} saved={isSaved(item.event.id)}
                  onOpen={setSelected} />
              ))}
            </div>
          </section>
        )}

        {!isLoggedIn && (
          <section className="cta-card">
            <div>
              <h3>Get picks made for you</h3>
              <p className="muted">Tell us what you love and we'll surface events and places that match.</p>
            </div>
            <Button onClick={() => openAuth('signup')}>Create a free account</Button>
          </section>
        )}

        <section className="section">
          <SectionHeader title={city ? `Upcoming in ${city}` : 'Upcoming across India'}
            action={<a className="link" href={`#/list-event${cityOption.lat != null ? `?${cityParams(cityOption)}` : ''}`}>+ List an event</a>} />
          <div className="chips" role="group" aria-label="Filter by category">
            <Chip selected={cat === ''} onClick={() => setCat('')}>Everything</Chip>
            {EVENT_CATEGORIES.map((key) => (
              <Chip key={key} selected={cat === key} onClick={() => setCat(cat === key ? '' : key)} icon={category(key).icon}>
                {category(key).label}
              </Chip>
            ))}
          </div>
          <ErrorNote error={error} onRetry={loadEvents} />
          {!events && !error && <Spinner label="Finding events…" />}
          {events && events.length === 0 && (
            <EmptyState icon="🗓️" title={city ? `No events in ${city} yet` : 'Nothing scheduled here yet'}
              action={city && cityOption.lat != null ? (
                <div className="empty-actions">
                  <a className="btn btn-secondary" href={`#/list-event?${cityParams(cityOption)}`}>List an event in {city}</a>
                  <a className="btn btn-secondary" href={`#/discover?${cityParams(cityOption)}`}>Discover places</a>
                  <a className="btn btn-primary" href={`#/plan?${cityParams(cityOption)}`}>Plan a trip to {city}</a>
                </div>
              ) : null}>
              {city ? 'Know of a gig, walk or festival here? Anyone with an account can list it. You can still explore and plan meanwhile.' : 'Try another category.'}
            </EmptyState>
          )}
          {events && events.length > 0 && (
            <div className="grid grid-cards">
              {events.map((e) => (
                <EventCard key={e.id} event={e} saved={isSaved(e.id)} onOpen={setSelected} />
              ))}
            </div>
          )}
        </section>

        {trips.length > 0 && (
          <section className="section">
            <SectionHeader title="Trip ideas" subtitle="Pick one and we'll plan it day by day"
              action={<a className="link" href="#/plan">Or plan your own →</a>} />
            <div className="grid grid-trips">
              {trips.map((t) => <TripCard key={t.id} trip={t} href={tripHref(t)} />)}
            </div>
          </section>
        )}
      </div>

      <EventModal event={selected} onClose={() => setSelected(null)} />
    </>
  )
}
