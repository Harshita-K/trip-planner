// Static UI data. CITIES are the cities with seeded events (Explore taglines); any city can be searched via /api/cities.

export const CITIES = [
  { name: 'Bengaluru', lat: 12.9716, lng: 77.5946, tagline: 'Gardens, palaces & filter coffee', hasPlaces: true },
  { name: 'Jaipur', lat: 26.9124, lng: 75.7873, tagline: 'Forts, bazaars & the Pink City', hasPlaces: true },
  { name: 'Goa', lat: 15.4909, lng: 73.8278, tagline: 'Beaches & sunset gigs', hasPlaces: false },
]


export const CATEGORIES = {
  music: { label: 'Music', icon: '🎵', hue: '#7B5EA7' },
  comedy: { label: 'Comedy', icon: '🎤', hue: '#D9643A' },
  history: { label: 'History', icon: '🏰', hue: '#A0673B' },
  food: { label: 'Food', icon: '🍛', hue: '#D9902A' },
  nature: { label: 'Nature', icon: '🌿', hue: '#4F8A5B' },
  business: { label: 'Business', icon: '💼', hue: '#3E6C8F' },
  books: { label: 'Books', icon: '📚', hue: '#8A5A7A' },
  art: { label: 'Art', icon: '🎨', hue: '#B5486B' },
  sports: { label: 'Sports', icon: '🏏', hue: '#2E7D5B' },
  museum: { label: 'Museums', icon: '🏛️', hue: '#5C6F9E' },
  religious: { label: 'Temples & churches', icon: '🛕', hue: '#C27B2C' },
  shopping: { label: 'Shopping', icon: '🛍️', hue: '#C2557A' },
  landmark: { label: 'Landmarks', icon: '📍', hue: '#2F7A7A' },
  nightlife: { label: 'Nightlife', icon: '🌙', hue: '#3B3F7A' },
  amusement: { label: 'Fun parks', icon: '🎢', hue: '#D04E4E' },
  trip: { label: 'Trip', icon: '🧳', hue: '#12434B' },
}

export function category(key) {
  return CATEGORIES[key] || { label: key ? key[0].toUpperCase() + key.slice(1) : 'Other', icon: '✨', hue: '#6B7A80' }
}

/** Interests a user can pick; they match event and place categories in the backend. */
export const INTERESTS = ['history', 'museum', 'nature', 'food', 'music', 'comedy', 'religious',
  'shopping', 'nightlife', 'landmark', 'amusement', 'books', 'art', 'sports', 'business']

/** Categories offered when browsing places. */
export const PLACE_CATEGORIES = ['history', 'museum', 'nature', 'religious', 'landmark', 'shopping',
  'nightlife', 'amusement', 'food']

/** Must match CatalogDtos.EVENT_CATEGORIES in the backend. */
export const EVENT_CATEGORIES = ['music', 'comedy', 'history', 'food', 'nature', 'art', 'sports', 'books', 'business']

export const PACES = [
  { value: 'relaxed', label: 'Relaxed', detail: 'Up to 3 stops a day · 10am–5pm · long lunch' },
  { value: 'balanced', label: 'Balanced', detail: 'Up to 5 stops a day · 9:30am–6:30pm · 1 h lunch' },
  { value: 'packed', label: 'Packed', detail: 'Up to 7 stops a day · 8:30am–8:30pm · quick lunch' },
]

export const BUDGETS = [
  { value: 'low', label: 'Budget' },
  { value: 'mid', label: 'Comfort' },
  { value: 'high', label: 'Luxury' },
]

export const ALL_INDIA = { key: 'all', label: 'All of India', sublabel: 'Every city we cover', all: true }

/** Turn a city from /api/cities into a combobox option. */
export function cityOption(city) {
  return {
    key: `${city.name}|${city.region}`,
    label: city.name,
    sublabel: [city.region, city.curated ? 'Curated guide' : null].filter(Boolean).join(' · '),
    lat: city.lat,
    lng: city.lng,
    curated: city.curated,
  }
}

export const DEFAULT_CITY = cityOption({ name: 'Bengaluru', region: 'Karnataka', lat: 12.9716, lng: 77.5946, curated: true })

export function cityFromParams(params) {
  const lat = Number(params.get('lat'))
  const lng = Number(params.get('lng'))
  const name = params.get('city')
  if (!name || Number.isNaN(lat) || Number.isNaN(lng) || (!lat && !lng)) return null
  return cityOption({ name, region: params.get('region') || '', lat, lng, curated: params.get('curated') === '1' })
}

export function cityParams(option) {
  return new URLSearchParams({ city: option.label, region: option.sublabel?.split(' · ')[0] || '', lat: option.lat, lng: option.lng, curated: option.curated ? '1' : '0' }).toString()
}
