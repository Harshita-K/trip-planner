import { useState } from 'react'
import { useAuth } from '../auth.jsx'
import { category } from '../data.js'
import { eventDate, eventTime, money } from '../format.js'
import { useSaved } from '../saved.jsx'
import NearbyPanel from './NearbyPanel.jsx'
import { useToast } from './Toast.jsx'
import { Button, CategoryTag, Modal } from './ui.jsx'

/** An event's details, with "Save to my plans" and what's around the venue. */
export default function EventModal({ event, onClose }) {
  const { isLoggedIn, openAuth } = useAuth()
  const { isSaved, save, unsave } = useSaved()
  const toast = useToast()
  const [busy, setBusy] = useState(false)

  if (!event) return null
  const c = category(event.category)
  const saved = isLoggedIn && isSaved(event.id)

  async function toggle() {
    if (!isLoggedIn) {
      openAuth('login', 'Log in to save events to your plans.', { stay: true })
      return
    }
    setBusy(true)
    try {
      if (saved) {
        await unsave(event)
        toast('Removed from your plans.')
      } else {
        await save(event)
        toast("Saved. We'll remind you the day before.")
      }
    } catch (e) {
      toast(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const mapsUrl = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${event.venue}, ${event.city}`)}`

  return (
    <Modal open={Boolean(event)} onClose={onClose} title={event.title} wide>
      <div className="event-hero" style={{ '--hue': c.hue }}>
        <span className="event-hero-icon" aria-hidden="true">{c.icon}</span>
        <div>
          <CategoryTag value={event.category} />
          <h2>{event.title}</h2>
          <p>{eventDate(event.startTime)} · {eventTime(event.startTime)} · {event.venue}, {event.city}</p>
        </div>
      </div>

      <div className="event-body">
        {event.description && <p>{event.description}</p>}
        <div className="event-panel">
          <div>
            <span className="muted small">Entry</span>
            <strong>{money(event.price, event.currency)}</strong>
            <span className="muted small">{Number(event.price) === 0 ? 'No ticket needed' : 'Indicative, pay at the venue or with the organiser'}</span>
          </div>
          <div className="event-side">
            <a className="link small" href={mapsUrl} target="_blank" rel="noreferrer">Open in Maps ↗</a>
            {event.community && <span className="muted small">Listed by the community</span>}
          </div>
        </div>
        <div className="modal-actions">
          <Button variant="ghost" onClick={onClose}>Close</Button>
          <Button variant={saved ? 'secondary' : 'primary'} onClick={toggle} loading={busy}>
            {saved ? '✓ Saved · Remove' : isLoggedIn ? '☆ Save to my plans' : 'Log in to save'}
          </Button>
        </div>

        <h3 className="mt">Make a day of it</h3>
        <NearbyPanel eventId={event.id} />
      </div>
    </Modal>
  )
}
