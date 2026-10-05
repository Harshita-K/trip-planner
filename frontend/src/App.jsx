import { useCallback, useEffect, useState } from 'react'
import { Api } from './api.js'
import { useAuth } from './auth.jsx'
import AuthModal from './components/AuthModal.jsx'
import { Spinner } from './components/ui.jsx'
import Analytics from './pages/Analytics.jsx'
import Discover from './pages/Discover.jsx'
import Explore from './pages/Explore.jsx'
import Inbox from './pages/Inbox.jsx'
import ListEvent from './pages/ListEvent.jsx'
import MyTrips from './pages/MyTrips.jsx'
import Plan from './pages/Plan.jsx'
import Profile from './pages/Profile.jsx'

const NAV = [
  { path: '/', label: 'Explore' },
  { path: '/plan', label: 'Plan a trip' },
  { path: '/discover', label: 'Discover' },
  { path: '/trips', label: 'My trips' },
]

const SEEN_KEY = 'wanderly.inboxSeenAt'

function readRoute() {
  const raw = window.location.hash.replace(/^#/, '') || '/'
  const [path, query = ''] = raw.split('?')
  return { path, params: new URLSearchParams(query) }
}

function useRoute() {
  const [route, setRoute] = useState(readRoute)
  useEffect(() => {
    const onChange = () => {
      setRoute(readRoute())
      window.scrollTo(0, 0)
    }
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}

/** Polls the inbox so notes and reminders (delivered async via Kafka) show up as a badge. */
function useInbox(isLoggedIn) {
  const [notifications, setNotifications] = useState(null)
  const [seenAt, setSeenAt] = useState(() => {
    try { return Number(localStorage.getItem(SEEN_KEY)) || 0 } catch { return 0 }
  })

  useEffect(() => {
    if (!isLoggedIn) { setNotifications(null); return undefined }
    let active = true
    const load = () => Api.notifications().then((n) => active && setNotifications(n)).catch(() => {})
    load()
    const timer = setInterval(load, 8000)
    return () => { active = false; clearInterval(timer) }
  }, [isLoggedIn])

  const markSeen = useCallback(() => {
    const now = Date.now()
    setSeenAt(now)
    try { localStorage.setItem(SEEN_KEY, String(now)) } catch { /* ignore */ }
  }, [])

  const unread = notifications ? notifications.filter((n) => new Date(n.createdAt).getTime() > seenAt).length : 0
  return { notifications, unread, markSeen }
}

export default function App() {
  const { isLoggedIn, user, ready, openAuth, logout } = useAuth()
  const route = useRoute()
  const inbox = useInbox(isLoggedIn)
  const [menuOpen, setMenuOpen] = useState(false)

  useEffect(() => setMenuOpen(false), [route.path])
  useEffect(() => {
    if (!menuOpen) return undefined
    const close = (e) => { if (!e.target.closest('.menu')) setMenuOpen(false) }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [menuOpen])

  let page
  switch (route.path) {
    case '/plan': page = <Plan key={route.params.toString()} params={route.params} />; break
    case '/discover': page = <Discover key={route.params.toString()} params={route.params} />; break
    case '/trips': page = <MyTrips />; break
    case '/list-event': page = <ListEvent key={route.params.toString()} params={route.params} />; break
    case '/inbox': page = <Inbox notifications={inbox.notifications} markSeen={inbox.markSeen} />; break
    case '/analytics': page = <Analytics />; break
    case '/profile': page = <Profile welcome={route.params.get('welcome') === '1'} />; break
    default: page = <Explore />
  }

  const initials = (user?.name || '?').split(' ').map((w) => w[0]).join('').slice(0, 2).toUpperCase()

  return (
    <div className="app">
      <header className={`topbar ${route.path === '/' ? 'topbar-hero' : ''}`}>
        <div className="container topbar-inner">
          <a href="#/" className="brand" aria-label="Wanderly home">
            <span className="brand-mark" aria-hidden="true" />
            Wanderly
          </a>
          <nav className="nav" aria-label="Main">
            {NAV.map((n) => (
              <a key={n.path} href={`#${n.path}`} className={route.path === n.path ? 'active' : ''}>{n.label}</a>
            ))}
          </nav>
          <div className="topbar-actions">
            {!ready ? null : isLoggedIn ? (
              <>
                <a href="#/inbox" className={`icon-btn ${route.path === '/inbox' ? 'active' : ''}`} aria-label={`Inbox, ${inbox.unread} unread`}>
                  <span aria-hidden="true">🔔</span>
                  {inbox.unread > 0 && <span className="badge">{inbox.unread}</span>}
                </a>
                <div className="menu">
                  <button className="avatar" onClick={() => setMenuOpen((o) => !o)} aria-expanded={menuOpen} aria-label="Account menu">
                    {initials}
                  </button>
                  {menuOpen && (
                    <div className="menu-pop" role="menu">
                      <div className="menu-who">
                        <strong>{user?.name}</strong>
                        <span className="muted small">{user?.email}</span>
                      </div>
                      <a href="#/profile" role="menuitem">Preferences</a>
                      <a href="#/trips" role="menuitem">My trips</a>
                      <a href="#/list-event" role="menuitem">List an event</a>
                      <a href="#/inbox" role="menuitem">Inbox</a>
                      <a href="#/analytics" role="menuitem">Analytics</a>
                      <button role="menuitem" onClick={() => { logout(); setMenuOpen(false); window.location.hash = '#/' }}>Log out</button>
                    </div>
                  )}
                </div>
              </>
            ) : (
              <>
                <button className="btn btn-ghost-light btn-sm" onClick={() => openAuth('login')}>Log in</button>
                <button className="btn btn-primary btn-sm" onClick={() => openAuth('signup')}>Sign up</button>
              </>
            )}
          </div>
        </div>
      </header>

      {/* Wait for the session check, so pages don't flash their logged-out state. */}
      <main>{ready ? page : <div className="container page"><Spinner /></div>}</main>

      <footer className="footer">
        <div className="container">
          <span className="brand brand-small"><span className="brand-mark" aria-hidden="true" /> Wanderly</span>
          <span className="muted small attribution">
            Demo project · a planner, nothing is sold · Places © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap contributors</a> (ODbL)
            and <a href="https://en.wikipedia.org" target="_blank" rel="noreferrer">Wikipedia</a> (CC BY-SA) · City search by <a href="https://photon.komoot.io" target="_blank" rel="noreferrer">Photon</a>
          </span>
        </div>
      </footer>

      <AuthModal />
    </div>
  )
}
