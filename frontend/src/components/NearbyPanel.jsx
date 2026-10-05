import { useEffect, useState } from 'react'
import { Api } from '../api.js'
import { PlaceCard } from './cards.jsx'
import { ErrorNote, Spinner } from './ui.jsx'

/** F7: food and sights around an event's venue. */
export default function NearbyPanel({ eventId }) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let active = true
    setData(null)
    setError(null)
    Api.eventNearby(eventId).then((d) => active && setData(d)).catch((e) => active && setError(e))
    return () => { active = false }
  }, [eventId])

  if (error) return <ErrorNote error={error} />
  if (!data) return <Spinner label="Finding great spots nearby…" />
  if (!data.food.length && !data.attractions.length) {
    return <p className="muted">We don't have places mapped around this venue yet.</p>
  }
  return (
    <div className="nearby">
      {data.food.length > 0 && (
        <div>
          <h4 className="nearby-heading">🍛 Eat nearby</h4>
          {data.food.slice(0, 3).map((r) => <PlaceCard key={r.place.id} ranked={r} compact />)}
        </div>
      )}
      {data.attractions.length > 0 && (
        <div>
          <h4 className="nearby-heading">📍 While you're there</h4>
          {data.attractions.slice(0, 3).map((r) => <PlaceCard key={r.place.id} ranked={r} compact />)}
        </div>
      )}
    </div>
  )
}
