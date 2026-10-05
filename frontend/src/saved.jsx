import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { Api } from './api.js'
import { useAuth } from './auth.jsx'

const SavedContext = createContext(null)

/** Which events the user has saved, shared by every page so a "Saved" badge is right everywhere. */
export function SavedProvider({ children }) {
  const { isLoggedIn } = useAuth()
  const [saved, setSaved] = useState(null)   // [{ event, savedAt }] or null while loading / logged out

  const reload = useCallback(() => {
    if (!isLoggedIn) { setSaved(null); return Promise.resolve() }
    return Api.savedEvents().then(setSaved).catch(() => setSaved([]))
  }, [isLoggedIn])

  useEffect(() => { reload() }, [reload])

  const ids = useMemo(() => new Set((saved || []).map((s) => s.event.id)), [saved])

  const save = useCallback(async (event) => {
    const entry = await Api.saveEvent(event.id)
    setSaved((list) => [entry, ...(list || []).filter((s) => s.event.id !== event.id)])
  }, [])

  const unsave = useCallback(async (event) => {
    await Api.unsaveEvent(event.id)
    setSaved((list) => (list || []).filter((s) => s.event.id !== event.id))
  }, [])

  const value = useMemo(() => ({ saved, isSaved: (id) => ids.has(id), save, unsave, reload }),
    [saved, ids, save, unsave, reload])
  return <SavedContext.Provider value={value}>{children}</SavedContext.Provider>
}

export function useSaved() {
  return useContext(SavedContext)
}
