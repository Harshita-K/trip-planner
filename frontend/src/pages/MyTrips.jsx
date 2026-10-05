import { useCallback, useEffect, useState } from 'react'
import { Api } from '../api.js'
import { useAuth } from '../auth.jsx'
import EventModal from '../components/EventModal.jsx'
import { useToast } from '../components/Toast.jsx'
import { Button, CategoryTag, EmptyState, ErrorNote, SectionHeader, Spinner } from '../components/ui.jsx'
import { eventDate, eventTime, isoDate, money, shortDate } from '../format.js'
import { useSaved } from '../saved.jsx'

/** Everything the user is planning: saved events, saved itineraries, and events they listed. */
export default function MyTrips() {
  const { isLoggedIn, openAuth } = useAuth()
  const { saved, unsave, reload } = useSaved()
  const toast = useToast()
  const [plans, setPlans] = useState(null)
  const [listed, setListed] = useState(null)
  const [error, setError] = useState(null)
  const [open, setOpen] = useState(null)
  const [confirming, setConfirming] = useState(null)
  const [busy, setBusy] = useState(null)

  const load = useCallback(() => {
    if (!isLoggedIn) return
    setError(null)
    reload()
    Api.itineraries().then(setPlans).catch(setError)
    Api.myEvents().then(setListed).catch(setError)
  }, [isLoggedIn, reload])

  useEffect(load, [load])

  if (!isLoggedIn) {
    return (
      <div className="container page">
        <EmptyState icon="🧳" title="Your trips live here"
          action={<Button onClick={() => openAuth('login', 'Log in to see your plans.')}>Log in</Button>}>
          Log in to see the events you've saved and the trips you've planned.
        </EmptyState>
      </div>
    )
  }

  async function remove(event) {
    setBusy(event.id)
    try {
      await unsave(event)
      toast('Removed from your plans.')
    } catch (e) {
      toast(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  async function deleteListing(event) {
    setBusy(event.id)
    try {
      await Api.deleteEvent(event.id)
      toast('Event removed.')
      setConfirming(null)
      setListed((l) => l.filter((e) => e.id !== event.id))
      reload()
    } catch (e) {
      toast(e.message, 'error')
    } finally {
      setBusy(null)
    }
  }

  const now = Date.now()
  const byStart = (a, b) => new Date(a.event.startTime) - new Date(b.event.startTime)
  const upcoming = (saved || []).filter((s) => new Date(s.event.startTime).getTime() > now).sort(byStart)
  const past = (saved || []).filter((s) => new Date(s.event.startTime).getTime() <= now).sort(byStart).reverse()
  const today = isoDate(new Date())
  const nothingYet = saved && plans && listed && !saved.length && !plans.length && !listed.length

  return (
    <div className="container page">
      <header className="page-head">
        <p className="eyebrow">My trips</p>
        <h1>What you're planning</h1>
      </header>

      <ErrorNote error={error} onRetry={load} />
      {(!saved || !plans || !listed) && !error && <Spinner />}
      {nothingYet && (
        <EmptyState icon="🗺️" title="Nothing planned yet"
          action={(
            <div className="empty-actions">
              <a className="btn btn-secondary" href="#/">Find events</a>
              <a className="btn btn-primary" href="#/plan">Plan a trip</a>
            </div>
          )}>
          Save events you like and plan trips day by day. They'll all show up here, with reminders the day before.
        </EmptyState>
      )}

      {upcoming.length > 0 && (
        <section className="section">
          <SectionHeader title="Saved events" subtitle="We'll remind you the day before each one." />
          <div className="plan-list">
            {upcoming.map(({ event }) => (
              <article key={event.id} className="ticket">
                <div className="ticket-main">
                  <CategoryTag value={event.category} />
                  <h3>{event.title}</h3>
                  <p className="muted small">
                    {eventDate(event.startTime)} · {eventTime(event.startTime)} · {event.venue}, {event.city} · {money(event.price, event.currency)}
                  </p>
                </div>
                <div className="ticket-actions">
                  <Button variant="secondary" size="sm" onClick={() => setOpen(event)}>📍 Details & nearby</Button>
                  <Button variant="ghost" size="sm" loading={busy === event.id} onClick={() => remove(event)}>Remove</Button>
                </div>
              </article>
            ))}
          </div>
        </section>
      )}

      {plans && plans.length > 0 && (
        <section className="section">
          <SectionHeader title="Trip plans" subtitle="Day-by-day itineraries you've saved."
            action={<a className="link" href="#/plan">Plan another →</a>} />
          <div className="plan-list">
            {plans.map((it) => {
              const stops = it.plan.days.reduce((n, d) => n + d.stops.length, 0)
              const status = it.endDate < today ? 'Done' : it.startDate <= today ? 'Happening now' : null
              return (
                <a key={it.id} className="ticket ticket-plan" href={`#/plan?saved=${it.id}`}>
                  <div className="ticket-main">
                    {status && <span className={`pill ${status === 'Done' ? 'pill-muted' : 'pill-ok'}`}>{status}</span>}
                    <h3>{it.destination}</h3>
                    <p className="muted small">
                      {shortDate(it.startDate)} → {shortDate(it.endDate)} · {it.plan.days.length} {it.plan.days.length === 1 ? 'day' : 'days'} · {stops} stops
                    </p>
                  </div>
                  <span className="link">Open →</span>
                </a>
              )
            })}
          </div>
        </section>
      )}

      {listed && listed.length > 0 && (
        <section className="section">
          <SectionHeader title="Events you listed" subtitle="Anyone can find these on Explore."
            action={<a className="link" href="#/list-event">+ List another</a>} />
          <div className="plan-list">
            {listed.map((event) => (
              <article key={event.id} className="ticket ticket-listed">
                <div className="ticket-main">
                  {new Date(event.startTime).getTime() <= now && <span className="pill pill-muted">Past</span>}
                  <h3>{event.title}</h3>
                  <p className="muted small">{eventDate(event.startTime)} · {eventTime(event.startTime)} · {event.venue}, {event.city}</p>
                </div>
                <div className="ticket-actions">
                  <a className="btn btn-secondary btn-sm" href={`#/list-event?id=${event.id}`}>Edit</a>
                  {confirming === event.id ? (
                    <span className="confirm-inline">
                      Remove it for everyone?
                      <Button variant="danger" size="sm" loading={busy === event.id} onClick={() => deleteListing(event)}>Yes, remove</Button>
                      <Button variant="ghost" size="sm" onClick={() => setConfirming(null)}>Keep it</Button>
                    </span>
                  ) : (
                    <Button variant="ghost" size="sm" onClick={() => setConfirming(event.id)}>Remove</Button>
                  )}
                </div>
              </article>
            ))}
          </div>
        </section>
      )}

      {past.length > 0 && (
        <details className="past">
          <summary>Past saved events ({past.length})</summary>
          {past.map(({ event }) => (
            <div key={event.id} className="ticket ticket-cancelled">
              <div className="ticket-main">
                <h3>{event.title}</h3>
                <p className="muted small">{eventDate(event.startTime)} · {event.city}</p>
              </div>
            </div>
          ))}
        </details>
      )}

      <EventModal event={open} onClose={() => setOpen(null)} />
    </div>
  )
}
