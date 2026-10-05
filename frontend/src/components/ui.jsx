import { useEffect } from 'react'
import { category } from '../data.js'

export function Button({ variant = 'primary', size, loading, children, className = '', ...props }) {
  return (
    <button
      className={`btn btn-${variant} ${size ? `btn-${size}` : ''} ${className}`}
      disabled={loading || props.disabled}
      {...props}
    >
      {loading && <span className="spinner spinner-sm" aria-hidden="true" />}
      {children}
    </button>
  )
}

export function Chip({ selected, onClick, children, icon }) {
  return (
    <button type="button" className={`chip ${selected ? 'chip-on' : ''}`} onClick={onClick} aria-pressed={selected}>
      {icon && <span aria-hidden="true">{icon}</span>}
      {children}
    </button>
  )
}

export function CategoryTag({ value }) {
  const c = category(value)
  return (
    <span className="tag" style={{ '--hue': c.hue }}>
      <span aria-hidden="true">{c.icon}</span> {c.label}
    </span>
  )
}

export function Stars({ rating }) {
  return (
    <span className="stars" aria-label={`Rated ${rating} out of 5`}>
      <span aria-hidden="true">★</span> {Number(rating).toFixed(1)}
    </span>
  )
}

export function Spinner({ label = 'Loading…' }) {
  return (
    <div className="loading">
      <span className="spinner" aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}

export function EmptyState({ icon = '🧭', title, children, action }) {
  return (
    <div className="empty">
      <div className="empty-icon" aria-hidden="true">{icon}</div>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action}
    </div>
  )
}

export function ErrorNote({ error, onRetry }) {
  if (!error) return null
  return (
    <div className="error-note" role="alert">
      <span>{error.message || String(error)}</span>
      {onRetry && <button className="link" onClick={onRetry}>Try again</button>}
    </div>
  )
}

export function Modal({ open, onClose, title, children, wide }) {
  useEffect(() => {
    if (!open) return undefined
    const onKey = (e) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
    }
  }, [open, onClose])

  if (!open) return null
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? 'modal-wide' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
        <button className="modal-close" onClick={onClose} aria-label="Close">×</button>
        {children}
      </div>
    </div>
  )
}

export function SectionHeader({ title, subtitle, action }) {
  return (
    <div className="section-header">
      <div>
        <h2>{title}</h2>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {action}
    </div>
  )
}
