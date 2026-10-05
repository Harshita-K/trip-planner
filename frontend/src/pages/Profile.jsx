import { useEffect, useState } from 'react'
import { Api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { useToast } from '../components/Toast.jsx'
import { Button, Chip, EmptyState } from '../components/ui.jsx'
import { BUDGETS, INTERESTS, PACES, category } from '../data.js'

export default function Profile({ welcome }) {
  const { user, setUser, isLoggedIn, openAuth, logout } = useAuth()
  const toast = useToast()
  const [form, setForm] = useState({ interests: [], budgetLevel: 'mid', travelPace: 'balanced' })
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (user) setForm({ interests: user.interests || [], budgetLevel: user.budgetLevel, travelPace: user.travelPace })
  }, [user])

  if (!isLoggedIn) {
    return (
      <div className="container page">
        <EmptyState icon="🙂" title="Your profile" action={<Button onClick={() => openAuth('login')}>Log in</Button>}>
          Log in to set your interests and travel style.
        </EmptyState>
      </div>
    )
  }

  const toggle = (i) => setForm((f) => ({
    ...f, interests: f.interests.includes(i) ? f.interests.filter((x) => x !== i) : [...f.interests, i],
  }))

  async function save() {
    setSaving(true)
    try {
      setUser(await Api.savePreferences(form))
      toast('Preferences saved. Your recommendations are updated.')
      if (welcome) window.location.hash = '#/'
    } catch (e) {
      toast(e.message, 'error')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="container page narrow">
      <header className="page-head">
        <p className="eyebrow">{welcome ? 'Welcome aboard' : 'Profile'}</p>
        <h1>{welcome ? 'What kind of traveller are you?' : `Hi, ${user?.name?.split(' ')[0] || 'there'}`}</h1>
        <p className="muted">{welcome
          ? 'Pick a few things you love. We use them to rank events, places and itineraries for you.'
          : user?.email}</p>
      </header>

      <section className="card stack">
        <div>
          <h3>Things you love</h3>
          <div className="chips">
            {INTERESTS.map((i) => (
              <Chip key={i} selected={form.interests.includes(i)} onClick={() => toggle(i)} icon={category(i).icon}>
                {category(i).label}
              </Chip>
            ))}
          </div>
        </div>

        <div>
          <h3>Travel pace</h3>
          <div className="pace-options">
            {PACES.map((p) => (
              <label key={p.value} className={`pace ${form.travelPace === p.value ? 'on' : ''}`}>
                <input type="radio" name="travelPace" checked={form.travelPace === p.value}
                  onChange={() => setForm((f) => ({ ...f, travelPace: p.value }))} />
                <strong>{p.label}</strong>
                <span className="muted small">{p.detail}</span>
              </label>
            ))}
          </div>
        </div>

        <div>
          <h3>Budget</h3>
          <div className="segmented">
            {BUDGETS.map((b) => (
              <button key={b.value} type="button" className={form.budgetLevel === b.value ? 'on' : ''}
                onClick={() => setForm((f) => ({ ...f, budgetLevel: b.value }))}>{b.label}</button>
            ))}
          </div>
        </div>

        <div className="modal-actions">
          {!welcome && <Button variant="ghost" onClick={() => { logout(); window.location.hash = '#/' }}>Log out</Button>}
          <Button onClick={save} loading={saving}>{welcome ? 'Save & start exploring' : 'Save preferences'}</Button>
        </div>
      </section>
    </div>
  )
}
