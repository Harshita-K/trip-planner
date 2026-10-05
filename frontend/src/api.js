// Thin client for the Wanderly REST API. All paths are relative; Vite proxies /api to :8080.

// The session is an httpOnly cookie set by the API (D59): scripts never see the token, so XSS can't
// steal it. Remove the token older versions kept in localStorage.
try { localStorage.removeItem('wanderly.token') } catch { /* ignore */ }

/** Whether we believe there's a session, so a 401 means "expired" rather than "never logged in". */
let sessionActive = false
export function setSessionActive(active) {
  sessionActive = active
}

export class ApiError extends Error {
  constructor(status, message, code) {
    super(message)
    this.status = status
    this.code = code   // machine-readable reason from the API, e.g. EMAIL_NOT_VERIFIED
  }
}

let onUnauthorized = () => {}
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn
}

function friendlyMessage(status, data) {
  const detail = data && (data.detail || data.message)
  if (status === 0) return "Can't reach the Wanderly server. Is the backend running?"
  if (detail === 'Invalid request content.' || detail === 'Validation failure') {
    return 'Some details look off. Please check the form and try again.'
  }
  if (status === 401) return detail || 'Please log in to continue.'
  if (status === 429) return detail || 'Too many attempts. Please wait a moment.'
  if (status >= 500) return 'Something went wrong on our side. Please try again.'
  return detail || `Request failed (${status})`
}

async function request(path, { method = 'GET', body, signal } = {}) {
  // X-Requested-With is the CSRF guard: the API ignores the session cookie on writes without it.
  const headers = { 'X-Requested-With': 'wanderly' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let res
  try {
    res = await fetch(path, { method, headers, signal, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) })
  } catch (e) {
    if (e.name === 'AbortError') throw e
    throw new ApiError(0, friendlyMessage(0))
  }

  const text = await res.text()
  let data = null
  if (text) {
    try { data = JSON.parse(text) } catch { data = null }
  }
  if (res.status === 401 && sessionActive && !path.startsWith('/api/auth/')) {
    onUnauthorized()
    throw new ApiError(401, 'Your session has expired. Please log in again.')
  }
  if (!res.ok) throw new ApiError(res.status, friendlyMessage(res.status, data), data && data.code)
  return data
}

function qs(params) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    if (Array.isArray(value) && value.length === 0) return
    search.set(key, Array.isArray(value) ? value.join(',') : String(value))
  })
  const s = search.toString()
  return s ? `?${s}` : ''
}

export const Api = {
  register: (body) => request('/api/auth/register', { method: 'POST', body }),
  login: (body) => request('/api/auth/login', { method: 'POST', body }),
  logout: () => request('/api/auth/logout', { method: 'POST' }),
  verifyEmail: (body) => request('/api/auth/verify', { method: 'POST', body }),
  resendCode: (email) => request('/api/auth/resend-code', { method: 'POST', body: { email } }),
  forgotPassword: (email) => request('/api/auth/forgot-password', { method: 'POST', body: { email } }),
  resetPassword: (body) => request('/api/auth/reset-password', { method: 'POST', body }),
  me: () => request('/api/users/me'),
  savePreferences: (body) => request('/api/users/me/preferences', { method: 'PUT', body }),

  events: (params = {}) => request(`/api/events${qs(params)}`),
  event: (id) => request(`/api/events/${id}`),
  eventNearby: (id) => request(`/api/events/${id}/nearby`),
  myEvents: () => request('/api/events/mine'),
  createEvent: (body) => request('/api/events', { method: 'POST', body }),
  updateEvent: (id, body) => request(`/api/events/${id}`, { method: 'PUT', body }),
  deleteEvent: (id) => request(`/api/events/${id}`, { method: 'DELETE' }),
  trips: () => request('/api/trips'),

  savedEvents: () => request('/api/saved-events'),
  saveEvent: (id) => request(`/api/saved-events/${id}`, { method: 'PUT' }),
  unsaveEvent: (id) => request(`/api/saved-events/${id}`, { method: 'DELETE' }),

  nearbyPlaces: (params) => request(`/api/places/nearby${qs(params)}`),
  placesCoverage: () => request('/api/places/coverage'),
  cities: (q) => request(`/api/cities${qs({ q })}`),
  suggestCities: (q, signal) => request(`/api/cities/suggest${qs({ q })}`, { signal }),

  generateItinerary: (body) => request('/api/itineraries', { method: 'POST', body }),
  previewItinerary: (body) => request('/api/itineraries/preview', { method: 'POST', body }),
  itineraries: () => request('/api/itineraries'),

  feed: (city) => request(`/api/recommendations/feed${qs({ city, limit: 6 })}`),
  travel: (params) => request(`/api/travel${qs(params)}`),
  hotels: (params) => request(`/api/hotels${qs(params)}`),
  analytics: () => request('/api/analytics/summary'),
  notifications: () => request('/api/notifications'),
}
