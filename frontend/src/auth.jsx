import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { Api, setSessionActive, setUnauthorizedHandler } from './api.js'

const AuthContext = createContext(null)

/**
 * Who's logged in. The session itself is an httpOnly cookie (D59), invisible to this code, so on load
 * we simply ask the API who we are: a profile means logged in, a 401 means not.
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [ready, setReady] = useState(false)
  // Auth dialog is global so any page can ask the visitor to log in mid-action.
  const [prompt, setPrompt] = useState({ open: false, mode: 'login', reason: '', stay: false })

  useEffect(() => { setSessionActive(Boolean(user)) }, [user])

  const logout = useCallback(() => {
    setUser(null)
    setSessionActive(false)
    Api.logout().catch(() => {})   // the server deletes the cookie; scripts can't
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(() => {
      logout()
      setPrompt({ open: true, mode: 'login', reason: 'Your session expired. Please log in again.', stay: true })
    })
  }, [logout])

  const refreshUser = useCallback(async () => {
    try {
      setUser(await Api.me())
    } catch {
      setUser(null)   // 401: no session (or it expired)
    }
  }, [])

  useEffect(() => {
    refreshUser().finally(() => setReady(true))
  }, [refreshUser])

  // The API has already set the cookie; show the user as logged in now and load the profile behind it.
  const login = useCallback((tokenResponse) => {
    setUser((u) => u || { id: tokenResponse.userId })
    setSessionActive(true)
    refreshUser()
  }, [refreshUser])

  const value = useMemo(() => ({
    user,
    ready,
    isLoggedIn: Boolean(user),
    login,
    logout,
    refreshUser,
    setUser,
    prompt,
    // stay: keep the visitor on the current page after sign-up (e.g. mid-way through saving a plan)
    openAuth: (mode = 'login', reason = '', { stay = false } = {}) => setPrompt({ open: true, mode, reason, stay }),
    closeAuth: () => setPrompt((p) => ({ ...p, open: false })),
  }), [user, ready, login, logout, refreshUser, prompt])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}
