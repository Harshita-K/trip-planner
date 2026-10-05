#!/usr/bin/env python3
"""
Build backend/src/main/resources/places/osm-cities.json: OpenStreetMap places for every city in
cities/india.json, fetched once via the Overpass API so the app never depends on Overpass at runtime
for these cities. Raw OSM tags are stored; the Java OverpassPlacesProvider.parse() interprets them,
so categories, notability and opening hours are computed by exactly the same code as live data.

Polite by design (public Overpass servers are shared): one request at a time, pauses between
requests, exponential backoff on 429/504/timeouts, resumable (finished cities are kept in a work
directory). Keep the tag filters in sync with OverpassPlacesProvider.FILTERS.

Usage:  python3 tools/fetch_osm_places.py               # fetch missing cities, then write the bundle
        python3 tools/fetch_osm_places.py --refresh     # refetch everything
        python3 tools/fetch_osm_places.py --bundle-only # just write the bundle from cities fetched so far
        python3 tools/fetch_osm_places.py --views-only  # add Wikipedia page views to fetched cities, then bundle
Data © OpenStreetMap contributors, ODbL 1.0.
"""
import json, math, os, sys, time, urllib.error, urllib.parse, urllib.request
from datetime import date

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CITIES = os.path.join(ROOT, "backend/src/main/resources/cities/india.json")
OUT = os.path.join(ROOT, "backend/src/main/resources/places/osm-cities.json")
WORK = os.path.join(ROOT, "tools/.osm-cache")
SERVERS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"]
# Wikimedia and OSM ask for contact details in the User-Agent; set OSM_CONTACT (email or URL) to include yours.
UA = "Wanderly/0.1 (trip planner; city data build" + (f"; {os.environ['OSM_CONTACT']}" if os.environ.get("OSM_CONTACT") else "") + ")"
CURATED = {"Bengaluru", "Jaipur"}          # these have hand-curated data already
KEEP_TAGS = {"name", "name:en", "int_name", "tourism", "historic", "amenity", "leisure", "natural", "shop",
             "wikidata", "wikipedia", "heritage", "opening_hours", "website", "contact:website", "wanderly:views30"}

SIGHTS = [
    '["tourism"~"^(museum|attraction|viewpoint|theme_park|zoo|aquarium)$"]',
    '["historic"~"^(castle|fort|monument|ruins|archaeological_site|palace|city_gate|tomb|memorial)$"]',
    '["amenity"="place_of_worship"]["wikidata"]',
    '["leisure"~"^(park|garden)$"]["wikidata"]',
    '["leisure"~"^(nature_reserve|water_park)$"]',
    '["natural"~"^(beach|waterfall|peak)$"]',
    '["shop"="mall"]', '["amenity"="marketplace"]',
]
FOOD = ['["amenity"~"^(restaurant|cafe|bar|pub|nightclub)$"]']


def bbox(lat, lng, km):
    d_lat = km / 111.0
    d_lng = km / (111.0 * math.cos(math.radians(lat)))
    return f"{lat - d_lat:.5f},{lng - d_lng:.5f},{lat + d_lat:.5f},{lng + d_lng:.5f}"


def query(filters, lat, lng, km, limit):
    body = "".join(f'nwr{f}["name"];' for f in filters)
    return f"[out:json][timeout:60][bbox:{bbox(lat, lng, km)}];({body});out center tags {limit};"


def run(ql):
    delay = 15
    for attempt in range(8):
        server = SERVERS[attempt % len(SERVERS)]
        try:
            req = urllib.request.Request(server, data=urllib.parse.urlencode({"data": ql}).encode(),
                                         headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=90) as res:
                data = json.load(res)
            remark = data.get("remark", "")
            if "error" in remark:
                raise RuntimeError(remark)
            return data["elements"]
        except Exception as e:
            print(f"    retry {attempt + 1} in {delay}s ({server.split('/')[2]}: {str(e)[:70]})", flush=True)
            time.sleep(delay)
            delay = min(delay * 2, 240)
    raise RuntimeError("Overpass unavailable after retries")


def wiki_get(base, params):
    """GET a Wikimedia API with ~1 request/second pacing and Retry-After handling. {} on failure."""
    url = base + "?" + urllib.parse.urlencode({**params, "format": "json", "formatversion": "2"})
    for attempt in range(5):
        time.sleep(1.0)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=30) as res:
                return json.load(res)
        except urllib.error.HTTPError as e:
            wait = int(e.headers.get("Retry-After") or 0) or 10 * (attempt + 1)
            print(f"    wiki {e.code}, waiting {wait}s", flush=True)
            time.sleep(wait)
        except Exception as e:
            print(f"    wiki retry {attempt + 1}: {str(e)[:60]}", flush=True)
            time.sleep(5 * (attempt + 1))
    return {}


def add_page_views(elements):
    """Popularity signal: 30-day English Wikipedia page views for places with a Wikidata id
    (Wikidata -> enwiki title -> page views). Stored as the tag wanderly:views30."""
    qids = sorted({e["tags"]["wikidata"] for e in elements if e["tags"].get("wikidata", "").startswith("Q")})
    title_by_qid = {}
    for i in range(0, len(qids), 50):
        d = wiki_get("https://www.wikidata.org/w/api.php",
                     {"action": "wbgetentities", "ids": "|".join(qids[i:i + 50]), "props": "sitelinks", "sitefilter": "enwiki"})
        for qid, ent in d.get("entities", {}).items():
            title = ent.get("sitelinks", {}).get("enwiki", {}).get("title")
            if title:
                title_by_qid[qid] = title
    views_by_title = {}   # only titles whose views actually arrived; failures must not look like "0 views"
    titles = sorted(set(title_by_qid.values()))
    for i in range(0, len(titles), 50):
        params = {"action": "query", "titles": "|".join(titles[i:i + 50]), "prop": "pageviews", "pvipdays": "30"}
        for _ in range(5):   # page views come back over several "continue" rounds
            d = wiki_get("https://en.wikipedia.org/w/api.php", params)
            norm = {n["to"]: n["from"] for n in d.get("query", {}).get("normalized", [])}
            for page in d.get("query", {}).get("pages", []):
                if "pageviews" not in page:
                    continue   # not in this round (continuation) or failed
                views = sum(v for v in (page.get("pageviews") or {}).values() if v)
                t = norm.get(page["title"], page["title"])
                views_by_title[t] = views_by_title.get(t, 0) + views
            if "continue" not in d:
                break
            params = {**params, **d["continue"]}
    for e in elements:
        title = title_by_qid.get(e["tags"].get("wikidata", ""))
        if title is not None and title in views_by_title:
            e["tags"]["wanderly:views30"] = str(views_by_title[title])
    return sum(1 for e in elements if "wanderly:views30" in e["tags"])


def slim(el):
    lat = el.get("lat", el.get("center", {}).get("lat"))
    lon = el.get("lon", el.get("center", {}).get("lon"))
    tags = {k: v for k, v in el.get("tags", {}).items() if k in KEEP_TAGS}
    return {"type": el["type"], "id": el["id"], "lat": round(lat, 6), "lon": round(lon, 6), "tags": tags}


def main():
    refresh = "--refresh" in sys.argv
    os.makedirs(WORK, exist_ok=True)
    cities = [c for c in json.load(open(CITIES)) if c["name"] not in CURATED]
    skip_fetch = "--bundle-only" in sys.argv or "--views-only" in sys.argv
    skipped = []
    for i, c in enumerate([] if skip_fetch else cities, 1):
        path = os.path.join(WORK, c["name"].replace(" ", "_") + ".json")
        if os.path.exists(path) and not refresh:
            continue
        print(f"[{i}/{len(cities)}] {c['name']}", flush=True)
        try:
            sights = run(query(SIGHTS, c["lat"], c["lng"], 15, 400))
            time.sleep(3)
            food = run(query(FOOD, c["lat"], c["lng"], 4, 150))
        except RuntimeError as e:
            # One stubborn city must not end the run: skip it; the next run retries it (resumable).
            skipped.append(c["name"])
            print(f"    skipped ({e}); will retry on the next run", flush=True)
            time.sleep(30)
            continue
        seen, elements = set(), []
        for el in sights + food:
            key = (el["type"], el["id"])
            if key not in seen and (el.get("lat") or el.get("center")):
                seen.add(key)
                elements.append(slim(el))
        with_views = add_page_views(elements)
        json.dump({**c, "fetchedAt": str(date.today()), "elements": elements}, open(path, "w"), ensure_ascii=False)
        print(f"    {len(elements)} places ({with_views} with Wikipedia page views)", flush=True)
        time.sleep(4)

    if "--views-only" in sys.argv:
        for c in cities:
            path = os.path.join(WORK, c["name"].replace(" ", "_") + ".json")
            if os.path.exists(path):
                data = json.load(open(path))
                n = add_page_views(data["elements"])
                json.dump(data, open(path, "w"), ensure_ascii=False)
                print(f"  {c['name']}: page views for {n} places", flush=True)

    bundle = []
    for c in cities:
        path = os.path.join(WORK, c["name"].replace(" ", "_") + ".json")
        if os.path.exists(path):
            bundle.append(json.load(open(path)))
    with open(OUT, "w") as f:
        json.dump({"source": "OpenStreetMap via Overpass API", "licence": "ODbL 1.0, © OpenStreetMap contributors",
                   "cities": bundle}, f, ensure_ascii=False, separators=(",", ":"))
    print(f"wrote {OUT}: {len(bundle)} cities, {sum(len(c['elements']) for c in bundle)} places, "
          f"{os.path.getsize(OUT) // 1024} KB")
    if skipped:
        print(f"skipped (Overpass unavailable), rerun to retry: {', '.join(skipped)}")


if __name__ == "__main__":
    main()
