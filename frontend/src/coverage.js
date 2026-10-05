import { useEffect, useState } from 'react'
import { Api } from './api.js'

let cached = null

/** Whether live place data (beyond the curated cities) is switched on. Fetched once per page load. */
export function useCoverage() {
  const [coverage, setCoverage] = useState(cached)
  useEffect(() => {
    if (cached) return
    Api.placesCoverage()
      .then((c) => { cached = c; setCoverage(c) })
      .catch(() => setCoverage({ liveData: false, curatedCities: [] }))
  }, [])
  return coverage
}
