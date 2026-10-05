import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { Api, setUnauthorizedHandler, tokenStore } from './api.js'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [token, setToken] = useState(() => tokenStore.get())
  const [user, setUser] = useState(null)
  // Auth dialog is global so any page can ask the visitor to log in mid-action.
  const [prompt, setPrompt] = useState({ open: false, mode: 'login', reason: '', stay: false })

  const logout = useCallback(() => {
    tokenStore.clear()
    setToken(null)
    setUser(null)
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(() => {
      logout()
      setPrompt({ open: true, mode: 'login', reason: 'Your session expired. Please log in again.', stay: true })
    })
  }, [logout])

  const refreshUser = useCallback(async () => {
    if (!tokenStore.get()) {
      setUser(null)
      return
    }
    try {
      setUser(await Api.me())
    } catch {
      /* 401 is handled globally; other errors leave the user as-is */
    }
  }, [])

  useEffect(() => {
    refreshUser()
  }, [token, refreshUser])

  const login = useCallback((tokenResponse) => {
    tokenStore.set(tokenResponse.accessToken)
    setToken(tokenResponse.accessToken)
  }, [])

  const value = useMemo(() => ({
    token,
    user,
    isLoggedIn: Boolean(token),
    login,
    logout,
    refreshUser,
    setUser,
    prompt,
    // stay: keep the visitor on the current page after sign-up (e.g. mid-way through saving a plan)
    openAuth: (mode = 'login', reason = '', { stay = false } = {}) => setPrompt({ open: true, mode, reason, stay }),
    closeAuth: () => setPrompt((p) => ({ ...p, open: false })),
  }), [token, user, login, logout, refreshUser, prompt])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}
