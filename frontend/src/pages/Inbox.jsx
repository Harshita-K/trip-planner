import { useEffect } from 'react'
import { useAuth } from '../auth.jsx'
import { Button, EmptyState, Spinner } from '../components/ui.jsx'
import { relativeTime } from '../format.js'

const ICONS = { 'event.saved': '⭐', 'event.reminder': '⏰', 'itinerary.reminder': '🧳' }

export default function Inbox({ notifications, markSeen }) {
  const { isLoggedIn, openAuth } = useAuth()

  useEffect(() => { if (notifications) markSeen() }, [notifications, markSeen])

  if (!isLoggedIn) {
    return (
      <div className="container page">
        <EmptyState icon="📬" title="Your inbox"
          action={<Button onClick={() => openAuth('login')}>Log in</Button>}>
          Notes on what you save, and reminders the day before your events and trips, arrive here.
        </EmptyState>
      </div>
    )
  }

  return (
    <div className="container page narrow">
      <header className="page-head">
        <p className="eyebrow">Inbox</p>
        <h1>Updates</h1>
        <p className="muted">Notes on what you save, and reminders the day before. Reminders are also emailed to you.</p>
      </header>
      {!notifications && <Spinner />}
      {notifications && notifications.length === 0 && (
        <EmptyState icon="📬" title="All quiet">Save an event or plan a trip, and reminders will land here.</EmptyState>
      )}
      {notifications && notifications.map((n) => (
        <article key={n.id} className="message">
          <div className="message-icon" aria-hidden="true">{ICONS[n.type] || '✉️'}</div>
          <div className="message-body">
            <div className="message-head">
              <h3>{n.subject}</h3>
              <span className="muted small">{relativeTime(n.createdAt)}</span>
            </div>
            <p className="message-text">{n.body}</p>
          </div>
        </article>
      ))}
    </div>
  )
}
