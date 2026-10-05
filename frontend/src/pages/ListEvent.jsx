import { useEffect, useState } from 'react'
import { Api } from '../api.js'
import { useAuth } from '../auth.jsx'
import CityCombobox from '../components/CityCombobox.jsx'
import { useToast } from '../components/Toast.jsx'
import { Button, Chip, EmptyState, Spinner } from '../components/ui.jsx'
import { EVENT_CATEGORIES, category, cityFromParams, cityOption } from '../data.js'
import { addDays, isoDate } from '../format.js'

const EMPTY = { title: '', category: '', venue: '', date: isoDate(addDays(new Date(), 7)), time: '18:00', price: '', description: '' }

/** List a new event, or edit one you listed (#/list-event?id=…). Fills the cities the catalogue doesn't cover. */
export default function ListEvent({ params }) {
  const { isLoggedIn, openAuth } = useAuth()
  const toast = useToast()
  const editId = params?.get('id')
  const [form, setForm] = useState(EMPTY)
  const [city, setCity] = useState(() => (params && cityFromParams(params)) || null)
  const [loading, setLoading] = useState(Boolean(editId))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!editId) return
    Api.event(editId).then((e) => {
      const start = new Date(e.startTime)
      setForm({
        title: e.title, category: e.category, venue: e.venue, date: isoDate(start),
        time: `${String(start.getHours()).padStart(2, '0')}:${String(start.getMinutes()).padStart(2, '0')}`,
        price: Number(e.price) ? String(Number(e.price)) : '', description: e.description || '',
      })
      // The stored point is the venue's; it's only used if the city is changed, which re-geocodes.
      setCity(cityOption({ name: e.city, region: '', lat: e.lat, lng: e.lng }))
    }).catch((err) => setError(err.message)).finally(() => setLoading(false))
  }, [editId])

  if (!isLoggedIn) {
    return (
      <div className="container page">
        <EmptyState icon="📣" title="List an event"
          action={<Button onClick={() => openAuth('login', 'Log in to list an event.', { stay: true })}>Log in</Button>}>
          Know of a gig, walk, workshop or festival? Log in to add it so other travellers can plan around it.
        </EmptyState>
      </div>
    )
  }
  if (loading) return <div className="container page"><Spinner /></div>

  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }))

  async function submit(e) {
    e.preventDefault()
    setError('')
    if (!form.category) { setError('Pick a category.'); return }
    if (!city || city.lat == null) { setError('Choose the city from the list.'); return }
    const start = new Date(`${form.date}T${form.time}`)
    if (Number.isNaN(start.getTime()) || start <= new Date()) { setError('The event has to start in the future.'); return }
    const body = {
      title: form.title.trim(), category: form.category, venue: form.venue.trim(),
      city: city.label, lat: city.lat, lng: city.lng, startTime: start.toISOString(),
      price: form.price === '' ? 0 : Number(form.price), description: form.description.trim(),
    }
    setSaving(true)
    try {
      if (editId) {
        await Api.updateEvent(editId, body)
        toast('Changes saved.')
      } else {
        await Api.createEvent(body)
        toast(`Listed! It now shows up under ${city.label} on Explore.`)
      }
      window.location.hash = '#/trips'
    } catch (err) {
      setError(err.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="container page narrow">
      <header className="page-head">
        <p className="eyebrow">{editId ? 'Edit your event' : 'List an event'}</p>
        <h1>{editId ? form.title || 'Edit event' : 'Share something worth planning around'}</h1>
        <p className="muted">Gigs, heritage walks, workshops, festivals, matches. Listings are free and appear on Explore straight away. Wanderly doesn't sell tickets: add the entry fee so people can budget.</p>
      </header>

      <form className="card form list-event" onSubmit={submit}>
        <label className="field">
          <span>Title</span>
          <input required maxLength={120} value={form.title} onChange={set('title')} placeholder="Sunset folk music at Ambrai Ghat" />
        </label>

        <fieldset>
          <legend>Category</legend>
          <div className="chips">
            {EVENT_CATEGORIES.map((key) => (
              <Chip key={key} selected={form.category === key} icon={category(key).icon}
                onClick={() => setForm((f) => ({ ...f, category: key }))}>
                {category(key).label}
              </Chip>
            ))}
          </div>
        </fieldset>

        <div className="field">
          <span>City</span>
          <CityCombobox value={city} onChange={setCity} placeholder="Any city, town or village in India…" />
        </div>
        <label className="field">
          <span>Venue</span>
          <input required maxLength={160} value={form.venue} onChange={set('venue')} placeholder="Ambrai Ghat" />
          <span className="muted small">We'll find it on the map; if we can't, the event is pinned to the city centre.</span>
        </label>

        <div className="row">
          <label className="field">
            <span>Date</span>
            <input required type="date" min={isoDate(new Date())} value={form.date} onChange={set('date')} />
          </label>
          <label className="field">
            <span>Starts at</span>
            <input required type="time" value={form.time} onChange={set('time')} />
          </label>
          <label className="field">
            <span>Entry fee (₹)</span>
            <input type="number" min="0" max="1000000" step="1" value={form.price} onChange={set('price')} placeholder="0 = free" />
          </label>
        </div>

        <label className="field">
          <span>Description</span>
          <textarea rows={4} maxLength={2000} value={form.description} onChange={set('description')}
            placeholder="What to expect, who it's for, anything to bring." />
        </label>

        {error && <div className="error-note" role="alert">{error}</div>}
        <div className="modal-actions">
          <a className="btn btn-ghost" href="#/trips">Cancel</a>
          <Button type="submit" loading={saving}>{editId ? 'Save changes' : 'List event'}</Button>
        </div>
      </form>
    </div>
  )
}
