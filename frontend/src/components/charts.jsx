import { useState } from 'react'

/*
 * Small single-series charts for the analytics dashboard. One validated data colour (--viz, #008f9e:
 * passes lightness, chroma and contrast checks on the sand surface). Magnitude only, so no legend; the
 * labels carry identity. Thin bars (<= 24px), 4px rounded data end, square at the baseline, hairline grid,
 * hover tooltips, and a table view for the time series.
 */

/** Ranked horizontal bars: label, bar, value. Optional share (0-1) shown as a percentage. */
export function BarList({ rows, format = (v) => v.toLocaleString('en-IN'), share }) {
  if (!rows.length) return <p className="muted small">No data yet.</p>
  const max = Math.max(...rows.map((r) => r.value), 1)
  return (
    <ul className="barlist">
      {rows.map((r) => (
        <li key={r.key} className="barlist-row" title={`${r.label}: ${format(r.value)}`}>
          <span className="barlist-label">{r.label}</span>
          <span className="barlist-track" aria-hidden="true">
            <span className="barlist-bar" style={{ width: `${Math.max(2, (r.value / max) * 100)}%` }} />
          </span>
          <span className="barlist-value">
            {format(r.value)}
            {share && <span className="muted"> · {Math.round(share(r) * 100)}%</span>}
          </span>
        </li>
      ))}
    </ul>
  )
}

/** Events per day as columns with a hover tooltip; "View as table" for an accessible alternative. */
export function DailyBars({ days }) {
  const [hover, setHover] = useState(null)
  const [asTable, setAsTable] = useState(false)
  if (!days.length) return <p className="muted small">No data yet.</p>
  const max = Math.max(...days.map((d) => d.events), 1)
  const short = (iso) => new Date(iso + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })

  return (
    <div className="daily">
      <button className="link small daily-toggle" onClick={() => setAsTable((v) => !v)}>
        {asTable ? 'View as chart' : 'View as table'}
      </button>
      {asTable ? (
        <table className="departures">
          <thead><tr><th>Day</th><th>Events</th><th>Active users</th></tr></thead>
          <tbody>{days.map((d) => <tr key={d.date}><td>{short(d.date)}</td><td>{d.events}</td><td>{d.users}</td></tr>)}</tbody>
        </table>
      ) : (
        <div className="daily-chart" onMouseLeave={() => setHover(null)}>
          <div className="daily-grid" aria-hidden="true"><span>{max}</span></div>
          <div className="daily-cols" role="img" aria-label={`Events per day, ${days.length} days, peak ${max}`}>
            {days.map((d, i) => (
              <div key={d.date} className={`daily-col ${hover === i ? 'on' : ''}`} onMouseEnter={() => setHover(i)}
                onFocus={() => setHover(i)} tabIndex={0} aria-label={`${short(d.date)}: ${d.events} events, ${d.users} users`}>
                <span className="daily-bar" style={{ height: `${(d.events / max) * 100}%` }} />
                {hover === i && (
                  <span className="viz-tip" role="tooltip">
                    <strong>{short(d.date)}</strong>
                    <span>{d.events} events</span>
                    <span>{d.users} active users</span>
                  </span>
                )}
              </div>
            ))}
          </div>
          <div className="daily-axis" aria-hidden="true">
            <span>{short(days[0].date)}</span>
            {days.length > 1 && <span>{short(days[days.length - 1].date)}</span>}
          </div>
        </div>
      )}
    </div>
  )
}

export function StatTile({ label, value, hint }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <strong className="stat-value">{value}</strong>
      {hint && <span className="muted small">{hint}</span>}
    </div>
  )
}
