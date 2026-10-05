import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Api } from '../api.js'
import { cityOption } from '../data.js'

/**
 * Type-to-search city picker (WAI-ARIA combobox pattern) backed by the city API, in two tiers:
 *
 * - Instant: matches from the bundled list of Indian destinations, old names included (GET /api/cities).
 * - Everything else: any city, town or village via Photon (GET /api/cities/suggest), appended when it
 *   arrives. Debounced, and superseded requests are aborted so a slow answer never overwrites a newer one.
 *
 * `value` and `onChange` work with option objects ({ key, label, sublabel, lat, lng, curated }).
 * `extraOptions` (e.g. All of India) are listed first. `disabledReason(option)` greys options out.
 */
export default function CityCombobox({ value, onChange, extraOptions = [], disabledReason = () => null,
  placeholder = 'Search any city in India…', className = '' }) {
  const listId = useId()
  const inputRef = useRef(null)
  const [query, setQuery] = useState(value?.label || '')
  const [open, setOpen] = useState(false)
  const [results, setResults] = useState([])
  const [suggestions, setSuggestions] = useState([])
  const [suggesting, setSuggesting] = useState(false)
  const [active, setActive] = useState(-1)

  useEffect(() => { setQuery(value?.label || '') }, [value])

  const isTyping = query.trim() !== '' && query !== value?.label
  const q = isTyping ? query.trim() : ''

  // Tier 1: instant local search (the endpoint never calls external services).
  useEffect(() => {
    if (!open) return undefined
    const t = setTimeout(() => {
      Api.cities(q).then((cities) => setResults(cities.map(cityOption))).catch(() => setResults([]))
    }, q ? 80 : 0)
    return () => clearTimeout(t)
  }, [q, open])

  // Tier 2: every city, town and village in India (Photon), debounced; stale requests are aborted.
  useEffect(() => {
    setSuggestions([])
    if (!open || q.length < 2) { setSuggesting(false); return undefined }
    const controller = new AbortController()
    const t = setTimeout(() => {
      setSuggesting(true)
      Api.suggestCities(q, controller.signal)
        .then((cities) => setSuggestions(cities.map(cityOption)))
        .catch(() => {})
        .finally(() => { if (!controller.signal.aborted) setSuggesting(false) })
    }, 300)
    return () => { clearTimeout(t); controller.abort() }
  }, [q, open])

  const options = useMemo(() => {
    const extras = extraOptions.filter((o) => !q || o.label.toLowerCase().includes(q.toLowerCase()))
    const seen = new Set()
    return [...extras, ...results, ...suggestions]
      .filter((o) => (seen.has(o.key) ? false : seen.add(o.key)))
      .map((o) => ({ ...o, reason: o.all ? null : disabledReason(o) }))
  }, [extraOptions, results, suggestions, q, disabledReason])

  const rows = options
  const enabled = rows.map((o, i) => (o.reason ? -1 : i)).filter((i) => i >= 0)

  useEffect(() => {
    if (open) setActive(enabled.length ? enabled[0] : -1)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows.length, open, q])

  function choose(option) {
    if (!option || option.reason) return
    onChange(option)
    setQuery(option.label)
    setOpen(false)
  }

  function move(step) {
    if (!enabled.length) return
    const pos = enabled.indexOf(active)
    const next = pos === -1 ? 0 : (pos + step + enabled.length) % enabled.length
    setActive(enabled[next])
  }

  function onKeyDown(e) {
    if (e.key === 'ArrowDown') { e.preventDefault(); if (!open) setOpen(true); else move(1) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); if (!open) setOpen(true); else move(-1) }
    else if (e.key === 'Enter') { if (open) { e.preventDefault(); choose(rows[active]) } }
    else if (e.key === 'Escape') { setOpen(false); setQuery(value?.label || '') }
  }

  function onBlur() {
    const exact = options.find((o) => !o.reason && o.label.toLowerCase() === query.trim().toLowerCase())
    if (exact && exact.key !== value?.key) choose(exact)
    else setQuery(value?.label || '')
    setOpen(false)
  }

  return (
    <div className={`combo ${className}`}>
      <div className="combo-field">
        <span className="combo-icon" aria-hidden="true">📍</span>
        <input
          ref={inputRef}
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={open && active >= 0 ? `${listId}-${active}` : undefined}
          value={query}
          placeholder={placeholder}
          onChange={(e) => { setQuery(e.target.value); setOpen(true) }}
          onFocus={(e) => { setOpen(true); e.target.select() }}
          onClick={() => setOpen(true)}
          onKeyDown={onKeyDown}
          onBlur={onBlur}
          autoComplete="off"
          spellCheck="false"
        />
        {query && (
          <button type="button" className="combo-clear" aria-label="Clear city"
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => { setQuery(''); setOpen(true); inputRef.current?.focus() }}>×</button>
        )}
        <span className={`combo-caret ${open ? 'up' : ''}`} aria-hidden="true">▾</span>
      </div>

      {open && (
        <ul className="combo-list" id={listId} role="listbox">
          {rows.length === 0 && !suggesting && (
            <li className="combo-empty">{q.length >= 2 ? `No places in India match “${q}”` : 'Type a city name'}</li>
          )}
          {rows.map((o, i) => (
            <li
              key={o.key}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={o.key === value?.key}
              aria-disabled={Boolean(o.reason) || undefined}
              className={`combo-option ${i === active ? 'active' : ''} ${o.reason ? 'disabled' : ''}`}
              onMouseDown={(e) => e.preventDefault()}
              onMouseEnter={() => !o.reason && setActive(i)}
              onClick={() => choose(o)}
            >
              <div>
                <strong>{highlight(o.label, q)}</strong>
                <span className="muted small">{[o.sublabel, o.reason].filter(Boolean).join(' · ')}</span>
              </div>
              {o.key === value?.key && <span className="combo-check" aria-hidden="true">✓</span>}
              {o.reason && <span className="pill pill-muted">Unavailable</span>}
            </li>
          ))}
          {suggesting && (
            <li className="combo-searching" aria-live="polite">
              <span className="spinner spinner-dark" aria-hidden="true" /> Searching all of India…
            </li>
          )}
        </ul>
      )}
    </div>
  )
}

function highlight(text, q) {
  if (!q) return text
  const i = text.toLowerCase().indexOf(q.toLowerCase())
  if (i < 0) return text
  return <>{text.slice(0, i)}<mark>{text.slice(i, i + q.length)}</mark>{text.slice(i + q.length)}</>
}
