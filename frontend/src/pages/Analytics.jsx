import { useCallback, useEffect, useState } from 'react'
import { Api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { BarList, DailyBars, StatTile } from '../components/charts.jsx'
import { Button, EmptyState, ErrorNote, Spinner } from '../components/ui.jsx'
import { relativeTime } from '../format.js'

const EVENT_LABELS = {
  'places.searched': 'Explored places',
  'event.viewed': 'Viewed an event',
  'itinerary.generated': 'Planned a trip',
  'event.saved': 'Saved an event',
  'travel.searched': 'Compared travel',
  'hotels.searched': 'Looked for stays',
}

/** F12: what people do on Wanderly, read from the S3 data lake with DuckDB (Athena locally). */
export default function Analytics() {
  const { isLoggedIn, openAuth } = useAuth()
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    Api.analytics().then(setData).catch(setError).finally(() => setLoading(false))
  }, [])

  useEffect(() => { if (isLoggedIn) load() }, [isLoggedIn, load])

  if (!isLoggedIn) {
    return (
      <div className="container page">
        <EmptyState icon="📊" title="Analytics" action={<Button onClick={() => openAuth('login')}>Log in</Button>}>
          Log in with an admin account to see how people use Wanderly.
        </EmptyState>
      </div>
    )
  }

  const rows = (list, labels) => list.map((c) => ({ key: c.key, label: labels?.[c.key] || c.key, value: c.value }))
  const f = data?.funnel
  const funnel = f ? [
    { key: 'active', label: 'Active users', value: f.active },
    { key: 'explored', label: 'Engaged', value: f.explored },
    { key: 'planned', label: 'Planned a trip', value: f.planned },
    { key: 'saved', label: 'Saved an event', value: f.saved },
  ] : []

  return (
    <div className="container page">
      <header className="page-head analytics-head">
        <div>
          <p className="eyebrow">Analytics · last 30 days (UTC)</p>
          <h1>How people use Wanderly</h1>
          <p className="muted">
            Every search, plan and save flows through Kafka into an S3 data lake; this page queries it with SQL (DuckDB, the local stand-in for Athena).
          </p>
        </div>
        <Button variant="secondary" size="sm" onClick={load} loading={loading}>Refresh</Button>
      </header>

      <ErrorNote error={error} />
      {!data && !error && <Spinner label="Querying the data lake…" />}
      {data && !data.available && <EmptyState icon="🪣" title="Nothing in the lake yet">{data.message}</EmptyState>}

      {data?.available && (
        <>
          <div className="stats">
            <StatTile label="Events" value={data.totalEvents.toLocaleString('en-IN')} hint="unique, de-duplicated by event id" />
            <StatTile label="Active users" value={data.activeUsers.toLocaleString('en-IN')} hint="signed-in users with any activity" />
            <StatTile label="Lake files" value={data.lakeFiles.toLocaleString('en-IN')} hint={`updated ${relativeTime(data.generatedAt)}`} />
          </div>

          <section className="card viz-card">
            <h3>Activity per day</h3>
            <DailyBars days={data.daily} />
          </section>

          <div className="viz-grid">
            <section className="card viz-card">
              <h3>Funnel</h3>
              <p className="muted small">Signed-in users reaching at least each step (engaged = explored, compared, planned or saved)</p>
              <BarList rows={funnel} share={f.active ? (r) => r.value / f.active : null} />
            </section>
            <section className="card viz-card">
              <h3>What people do</h3>
              <BarList rows={rows(data.byType, EVENT_LABELS)} />
            </section>
            <section className="card viz-card">
              <h3>Top cities</h3>
              <BarList rows={rows(data.topCities)} />
            </section>
            <section className="card viz-card">
              <h3>Popular interests</h3>
              <BarList rows={rows(data.topCategories)} />
            </section>
            <section className="card viz-card">
              <h3>Top travel searches</h3>
              <BarList rows={rows(data.topRoutes)} />
            </section>
            <section className="card viz-card">
              <h3>Most saved events</h3>
              <BarList rows={rows(data.topSaved)} />
            </section>
          </div>
        </>
      )}
    </div>
  )
}
