import { useEffect, useRef, useState } from 'react'
import { Api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { Button, Modal } from './ui.jsx'
import { useToast } from './Toast.jsx'

const RESEND_SECONDS = 60

/**
 * Log in / sign up / forgot password. Sign-up has a second step: we email a 6-digit code and the
 * account only becomes usable once the code is entered, which proves the email address is real and
 * theirs. Forgot password works the same way: a code by email, then a new password.
 */
export default function AuthModal() {
  const { prompt, closeAuth, login } = useAuth()
  const toast = useToast()
  const [mode, setMode] = useState(prompt.mode)          // 'login' | 'signup' | 'verify' | 'forgot' | 'reset'
  const [form, setForm] = useState({ name: '', email: '', password: '' })
  const [newPassword, setNewPassword] = useState('')
  const [code, setCode] = useState('')
  const [cooldown, setCooldown] = useState(0)
  const [error, setError] = useState('')
  const [info, setInfo] = useState('')
  const [loading, setLoading] = useState(false)
  const codeRef = useRef(null)

  useEffect(() => {
    if (prompt.open) {
      setMode(prompt.mode)
      setError('')
      setInfo('')
    }
  }, [prompt.open, prompt.mode])

  useEffect(() => {
    if (cooldown <= 0) return undefined
    const t = setTimeout(() => setCooldown((c) => c - 1), 1000)
    return () => clearTimeout(t)
  }, [cooldown])

  useEffect(() => {
    if (mode === 'verify' || mode === 'reset') setTimeout(() => codeRef.current?.focus(), 50)
  }, [mode])

  const isSignup = mode === 'signup'
  const update = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }))
  const firstName = form.name.trim().split(' ')[0]

  function goToVerify(message) {
    setMode('verify')
    setCode('')
    setError('')
    setInfo(message)
    setCooldown(RESEND_SECONDS)
  }

  async function submit(e) {
    e.preventDefault()
    setError('')
    if (isSignup && form.password.length < 8) {
      setError('Password needs at least 8 characters.')
      return
    }
    setLoading(true)
    try {
      if (isSignup) {
        // Same response whether or not the address already has an account (no account enumeration).
        await Api.register({ name: form.name.trim(), email: form.email.trim(), password: form.password })
        goToVerify('')
      } else {
        finish(await Api.login({ email: form.email.trim(), password: form.password }), false)
      }
    } catch (err) {
      if (err.code === 'EMAIL_NOT_VERIFIED') {
        // Signed up earlier but never verified: send a fresh code and move to the code step.
        // Codes are throttled to one a minute server-side; a recent code stays valid either way.
        await Api.resendCode(form.email.trim()).catch(() => {})
        const message = 'You still need to verify your email. Enter the latest code from your inbox.'
        goToVerify(message)
      } else {
        setError(err.message)
      }
    } finally {
      setLoading(false)
    }
  }

  async function verify(e) {
    e?.preventDefault()
    if (code.length !== 6) {
      setError('Enter the 6-digit code from the email.')
      return
    }
    setLoading(true)
    setError('')
    try {
      finish(await Api.verifyEmail({ email: form.email.trim(), code }), true)
    } catch (err) {
      setError(err.message)
      setCode('')
      codeRef.current?.focus()
    } finally {
      setLoading(false)
    }
  }

  async function resend() {
    setError('')
    try {
      await Api.resendCode(form.email.trim())
      setInfo('A new code is on its way. It replaces the old one and expires after 10 minutes.')
      setCooldown(RESEND_SECONDS)
    } catch (err) {
      setError(err.message)
    }
  }

  async function requestReset(e) {
    e?.preventDefault()
    setError('')
    setLoading(true)
    try {
      // Same response whether or not the address has an account.
      await Api.forgotPassword(form.email.trim())
      setMode('reset')
      setCode('')
      setNewPassword('')
      setInfo('')
      setCooldown(RESEND_SECONDS)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  async function resendReset() {
    setError('')
    try {
      await Api.forgotPassword(form.email.trim())
      setInfo('A new code is on its way. It replaces the old one and expires after 10 minutes.')
      setCooldown(RESEND_SECONDS)
    } catch (err) {
      setError(err.message)
    }
  }

  async function reset(e) {
    e.preventDefault()
    setError('')
    if (code.length !== 6) { setError('Enter the 6-digit code from the email.'); return }
    if (newPassword.length < 8) { setError('Your new password needs at least 8 characters.'); return }
    setLoading(true)
    try {
      finish(await Api.resetPassword({ email: form.email.trim(), code, password: newPassword }), false,
        'Password updated. You are logged in, and signed out everywhere else.')
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  function finish(tokenResponse, newAccount, message) {
    login(tokenResponse)
    closeAuth()
    setForm({ name: '', email: '', password: '' })
    setCode('')
    setNewPassword('')
    if (message) {
      toast(message)
    } else if (!newAccount) {
      toast('Welcome back!')
    } else if (prompt.stay) {
      toast(`Email verified. Welcome to Wanderly${firstName ? `, ${firstName}` : ''}!`)
    } else {
      toast(`Email verified. Welcome to Wanderly${firstName ? `, ${firstName}` : ''}! Tell us what you love.`)
      window.location.hash = '#/profile?welcome=1'
    }
  }

  function onCodeChange(e) {
    const digits = e.target.value.replace(/\D/g, '').slice(0, 6)
    setCode(digits)
    setError('')
  }

  // Submit as soon as all six digits are in (typed or pasted).
  useEffect(() => {
    if (mode === 'verify' && code.length === 6 && !loading) verify()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code])

  const title = mode === 'verify' ? 'Verify your email' : mode === 'forgot' || mode === 'reset' ? 'Reset password'
    : isSignup ? 'Create account' : 'Log in'
  const backToLogin = () => { setMode('login'); setError(''); setInfo('') }

  return (
    <Modal open={prompt.open} onClose={closeAuth} title={title}>
      {mode === 'forgot' ? (
        <div className="auth">
          <div className="auth-mail" aria-hidden="true">🔑</div>
          <h2>Forgot your password?</h2>
          <p className="muted">Enter your account's email and we'll send you a 6-digit code to set a new one.</p>
          <form onSubmit={requestReset} className="form">
            <label className="field">
              <span>Email</span>
              <input required type="email" value={form.email} onChange={update('email')} placeholder="you@example.com" autoComplete="email" />
            </label>
            {error && <div className="error-note" role="alert">{error}</div>}
            <Button type="submit" loading={loading} className="btn-block">Send me a code</Button>
          </form>
          <p className="auth-switch">Remembered it? <button className="link" onClick={backToLogin}>Back to log in</button></p>
        </div>
      ) : mode === 'reset' ? (
        <div className="auth">
          <div className="auth-mail" aria-hidden="true">✉️</div>
          <h2>Check your email</h2>
          <p className="muted">
            If <strong className="auth-email">{form.email.trim()}</strong> has a Wanderly account, we've sent it a 6-digit code.
            It expires in 10 minutes.
          </p>
          <form onSubmit={reset} className="form">
            <label className="field">
              <span>Reset code</span>
              <input ref={codeRef} className="code-input" value={code} onChange={onCodeChange} inputMode="numeric"
                autoComplete="one-time-code" placeholder="••••••" maxLength={6} />
            </label>
            <label className="field">
              <span>New password</span>
              <input required type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)}
                placeholder="At least 8 characters" autoComplete="new-password" />
            </label>
            {info && !error && <div className="info-note">{info}</div>}
            {error && <div className="error-note" role="alert">{error}</div>}
            <Button type="submit" loading={loading} className="btn-block">Set new password</Button>
          </form>
          <p className="auth-switch">
            Didn't get it? Check spam, or{' '}
            {cooldown > 0
              ? <span>send a new code in {cooldown}s</span>
              : <button className="link" onClick={resendReset}>send a new code</button>}
          </p>
          <p className="auth-switch"><button className="link" onClick={backToLogin}>Back to log in</button></p>
        </div>
      ) : mode === 'verify' ? (
        <div className="auth">
          <div className="auth-mail" aria-hidden="true">✉️</div>
          <h2>Check your email</h2>
          <p className="muted">
            If <strong className="auth-email">{form.email.trim()}</strong> can be used, we've sent it a 6-digit code.
            Enter it below to activate your account. It expires in 10 minutes.
          </p>
          <form onSubmit={verify} className="form">
            <label className="field">
              <span>Verification code</span>
              <input
                ref={codeRef}
                className="code-input"
                value={code}
                onChange={onCodeChange}
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="••••••"
                maxLength={6}
                aria-describedby="code-help"
              />
            </label>
            {info && !error && <div className="info-note">{info}</div>}
            {error && <div className="error-note" role="alert">{error}</div>}
            <Button type="submit" loading={loading} className="btn-block">Verify and continue</Button>
          </form>
          <p id="code-help" className="auth-switch">
            Didn't get it? Check spam, or{' '}
            {cooldown > 0
              ? <span>send a new code in {cooldown}s</span>
              : <button className="link" onClick={resend}>send a new code</button>}
          </p>
          <p className="auth-switch">
            Wrong email?{' '}
            <button className="link" onClick={() => { setMode('signup'); setError(''); setInfo('') }}>Go back and fix it</button>
          </p>
        </div>
      ) : (
        <div className="auth">
          <div className="auth-sun" aria-hidden="true" />
          <h2>{isSignup ? 'Start your journey' : 'Welcome back'}</h2>
          <p className="muted">
            {prompt.reason || (isSignup
              ? 'Save events, plan trips and get picks made for you.'
              : 'Log in to see your trips and recommendations.')}
          </p>
          <form onSubmit={submit} className="form">
            {isSignup && (
              <label className="field">
                <span>Your name</span>
                <input required value={form.name} onChange={update('name')} placeholder="Jiya" autoComplete="name" />
              </label>
            )}
            <label className="field">
              <span>Email</span>
              <input required type="email" value={form.email} onChange={update('email')} placeholder="you@example.com" autoComplete="email" />
            </label>
            <label className="field">
              <span>Password</span>
              <input required type="password" value={form.password} onChange={update('password')}
                placeholder={isSignup ? 'At least 8 characters' : 'Your password'}
                autoComplete={isSignup ? 'new-password' : 'current-password'} />
            </label>
            {isSignup && <p className="muted small">We'll email you a code to confirm it's really you.</p>}
            {!isSignup && (
              <p className="forgot">
                <button type="button" className="link small" onClick={() => { setMode('forgot'); setError('') }}>Forgot password?</button>
              </p>
            )}
            {error && <div className="error-note" role="alert">{error}</div>}
            <Button type="submit" loading={loading} className="btn-block">
              {isSignup ? 'Create account' : 'Log in'}
            </Button>
          </form>
          <p className="auth-switch">
            {isSignup ? 'Already have an account?' : 'New to Wanderly?'}{' '}
            <button className="link" onClick={() => { setMode(isSignup ? 'login' : 'signup'); setError('') }}>
              {isSignup ? 'Log in' : 'Create an account'}
            </button>
          </p>
        </div>
      )}
    </Modal>
  )
}
