# 📋 CHANGE LOG — BGI Pathfinder

All changes tracked by file path.

---

## Change #1: Mapbox → OpenStreetMap (OSMDroid) Migration

**Date:** 2026-05-06  
**Reason:** User requested OpenStreetMap instead of Mapbox SDK.  
**Impact:** No API key needed. Fully free & open-source map tiles.

---

### Files Changed

| # | File Path | Change Type | Description |
|---|-----------|-------------|-------------|
| 1 | `build.gradle.kts` | **MODIFIED** | Removed Mapbox SDK dependencies (`com.mapbox.maps:android`, `plugin-annotation`, `mapbox-sdk-geojson`). Added OSMDroid dependency (`org.osmdroid:osmdroid-android:6.1.20`). Mapbox Maven repository no longer needed in `settings.gradle.kts`. |
| 2 | `ui/MapActivity.kt` | **REWRITTEN** | Full rewrite from Mapbox APIs to OSMDroid APIs. Replaced: `com.mapbox.maps.MapView` → `org.osmdroid.views.MapView`, `CircleAnnotationManager` → `Marker` with custom circle drawables, `GeoJSON LineString + lineLayer` → `Polyline` overlay, `addOnMapClickListener` → `MapEventsOverlay` + `MapEventsReceiver`, `Style.DARK` → `TileSourceFactory.MAPNIK`. Added OSMDroid `Configuration.getInstance().load()` init. Added lifecycle methods `onResume()`/`onPause()`. |
| 3 | `res/layout/activity_map.xml` | **MODIFIED** | Changed MapView class from `com.mapbox.maps.MapView` to `org.osmdroid.views.MapView`. All other layout elements unchanged. |
| 4 | `AndroidManifest.xml` | **MODIFIED** | Removed `<meta-data>` for Mapbox access token. Added `WRITE_EXTERNAL_STORAGE` permission (maxSdkVersion=28) for OSMDroid tile caching. Added `android:usesCleartextTraffic="true"` for OSM tile server HTTP requests. |
| 5 | `res/values/strings.xml` | **MODIFIED** | Removed `mapbox_access_token` string resource. OSMDroid requires no API key. |

### Files NOT Changed (no Mapbox dependency)

| # | File Path | Reason |
|---|-----------|--------|
| 1 | `models/GraphModels.kt` | Pure data classes — no map SDK imports |
| 2 | `algorithm/AStarAlgorithm.kt` | Pure algorithm — no map SDK imports |
| 3 | `utils/GraphBuilder.kt` | Graph construction utility — no map SDK imports |
| 4 | `res/values/colors.xml` | Color definitions only |
| 5 | `res/values/themes.xml` | Theme definitions only |
| 6 | `res/drawable/route_info_bg_blue.xml` | Shape drawable only |
| 7 | `res/drawable/route_info_bg_red.xml` | Shape drawable only |

---

### Key API Mapping (Mapbox → OSMDroid)

| Mapbox API | OSMDroid Equivalent |
|-----------|-------------------|
| `com.mapbox.maps.MapView` | `org.osmdroid.views.MapView` |
| `mapboxMap.setCamera(CameraOptions)` | `mapView.controller.setCenter()` + `.setZoom()` |
| `Style.DARK` | `TileSourceFactory.MAPNIK` (no dark mode built-in) |
| `CircleAnnotationManager` + `CircleAnnotationOptions` | `Marker` + custom `BitmapDrawable` |
| `geoJsonSource()` + `lineLayer()` | `Polyline` overlay |
| `addOnMapClickListener` | `MapEventsOverlay(MapEventsReceiver)` |
| `Point.fromLngLat(lng, lat)` | `GeoPoint(lat, lng)` |
| Requires `pk.xxx` public token | **No token needed** |
| Requires `sk.xxx` download token | **No token needed** |
| Requires Mapbox Maven repository | **Standard Maven Central** |

---

### Setup Simplification

**Before (Mapbox):**
1. Create Mapbox account
2. Generate secret token (`sk.xxx`) → `gradle.properties`
3. Generate public token (`pk.xxx`) → `strings.xml`
4. Add Mapbox Maven repository to `settings.gradle.kts`

**After (OSMDroid):**
1. Add one dependency line to `build.gradle.kts`
2. Done ✅

---

## Change #2: File Structure Reorganization + Mapbox Reference Cleanup

**Date:** 2026-05-06  
**Reason:** Files were in flat folders (`BGI/ui/`, `BGI/models/`, etc.) which didn't match the Android Studio project layout. Restructured to follow the correct `app/src/main/...` format so the BGI folder mirrors a real Android Studio module.  
**Impact:** Files can now be directly copied into an Android Studio project without rearranging.

---

### Files Moved (Old Path → New Path)

| # | Old Path (in BGI) | New Path (in BGI) |
|---|-------------------|-------------------|
| 1 | `build.gradle.kts` | `app/build.gradle.kts` |
| 2 | `AndroidManifest.xml` | `app/src/main/AndroidManifest.xml` |
| 3 | `models/GraphModels.kt` | `app/src/main/java/com/bgi/pathfinder/models/GraphModels.kt` |
| 4 | `algorithm/AStarAlgorithm.kt` | `app/src/main/java/com/bgi/pathfinder/algorithm/AStarAlgorithm.kt` |
| 5 | `utils/GraphBuilder.kt` | `app/src/main/java/com/bgi/pathfinder/utils/GraphBuilder.kt` |
| 6 | `ui/MapActivity.kt` | `app/src/main/java/com/bgi/pathfinder/ui/MapActivity.kt` |
| 7 | `res/layout/activity_map.xml` | `app/src/main/res/layout/activity_map.xml` |
| 8 | `res/drawable/route_info_bg_blue.xml` | `app/src/main/res/drawable/route_info_bg_blue.xml` |
| 9 | `res/drawable/route_info_bg_red.xml` | `app/src/main/res/drawable/route_info_bg_red.xml` |
| 10 | `res/values/strings.xml` | `app/src/main/res/values/strings.xml` |
| 11 | `res/values/colors.xml` | `app/src/main/res/values/colors.xml` |
| 12 | `res/values/themes.xml` | `app/src/main/res/values/themes.xml` |

### Files Content-Modified

| # | File Path | Change |
|---|-----------|--------|
| 1 | `app/src/main/java/com/bgi/pathfinder/utils/GraphBuilder.kt` | Line 26-28: Replaced stale "MAPBOX DIRECTIONS API" comment with "OSRM (OpenStreetMap Routing Machine)" to match the OSMDroid migration from Change #1. |

### Old Flat Folders Removed

| # | Removed |
|---|---------|
| 1 | `BGI/models/` (folder) |
| 2 | `BGI/algorithm/` (folder) |
| 3 | `BGI/utils/` (folder) |
| 4 | `BGI/ui/` (folder) |
| 5 | `BGI/res/` (folder) |
| 6 | `BGI/AndroidManifest.xml` (file) |
| 7 | `BGI/build.gradle.kts` (file) |

### Final BGI Folder Structure

```
BGI/
├── change.md
└── app/
    ├── build.gradle.kts
    └── src/
        └── main/
            ├── AndroidManifest.xml
            ├── java/
            │   └── com/bgi/pathfinder/
            │       ├── algorithm/
            │       │   └── AStarAlgorithm.kt
            │       ├── models/
            │       │   └── GraphModels.kt
            │       ├── ui/
            │       │   └── MapActivity.kt
            │       └── utils/
            │           └── GraphBuilder.kt
            └── res/
                ├── drawable/
                │   ├── route_info_bg_blue.xml
                │   └── route_info_bg_red.xml
                ├── layout/
                │   └── activity_map.xml
                └── values/
                    ├── colors.xml
                    ├── strings.xml
                    └── themes.xml
```

---

## Change #3: Real Road Routing + Search + Multi-Route Upgrade

**Date:** 2026-05-06  
**Reason:** A* was showing straight lines only. Upgraded to use real OSM road network data, Nominatim search, and multiple route display.  
**Impact:** App now fetches actual road data from Overpass API, runs A* on real road graph, and shows routes following actual roads.

---

### New Files Added

| # | File Path | Purpose |
|---|-----------|---------|
| 1 | `app/.../network/NominatimService.kt` | Geocoding API — converts place names to coordinates via Nominatim |
| 2 | `app/.../network/OverpassService.kt` | Fetches real OSM road network (nodes+ways) and builds Graph |
| 3 | `app/.../ui/SearchResultAdapter.kt` | RecyclerView adapter for search result dropdowns |
| 4 | `app/.../res/layout/item_search_result.xml` | Layout for individual search result items |
| 5 | `app/.../res/drawable/search_bar_bg.xml` | Dark themed search bar background |

### Files Modified

| # | File Path | Changes |
|---|-----------|---------|
| 1 | `models/GraphModels.kt` | Added `SearchResult` data class. Added `roadType`, `speedKmh` to `Edge`. Added `totalDistanceMeters`, `estimatedTimeMinutes` to `PathResult`. Added `findNearestNode()`, `isEmpty()` to `Graph`. |
| 2 | `algorithm/AStarAlgorithm.kt` | Added penalty-based alternative routing. Added `findMultipleRoutes()`. Added distance+time calculation along path. |
| 3 | `ui/MapActivity.kt` | Complete rewrite: search bars with debounced Nominatim, Overpass road fetching, real road A* routing, dual-route polylines, loading states. |
| 4 | `res/layout/activity_map.xml` | Added search bars, result dropdowns, loading card, route comparison panel. |
| 5 | `build.gradle.kts` | Added `com.squareup.okhttp3:okhttp:4.12.0` and `lifecycle-runtime-ktx`. |
| 6 | `AndroidManifest.xml` | Added `windowSoftInputMode="adjustResize"` to activity. |

### App Flow (After This Change)

```
User types in Start search bar
  → Nominatim API returns places (debounced 500ms)
  → User selects result → Green marker placed

User types in End search bar
  → Same flow → Red marker placed

User taps "Find Routes"
  → Overpass API fetches road network for the area
  → Graph built from OSM nodes + ways
  → A* runs STANDARD mode → Blue route (shortest)
  → A* runs WEIGHTED mode → Red route (traffic+quality)
  → Both routes drawn on map following actual roads
  → Bottom card shows distance + estimated time for each
```

---

## Change #4: Node.js + Express + MongoDB Backend

**Date:** 2026-05-07  
**Reason:** Need a persistent backend with spatial queries for the Android app — replacing live Overpass API calls with own database.  
**Impact:** New `pathfinder-backend/` directory added. AWS/Docker ready.

---

### New Directory: `pathfinder-backend/`

| # | File | Purpose |
|---|------|---------|
| 1 | `server.js` | Express entry point, MongoDB connection, health check |
| 2 | `models/MapData.js` | Mongoose schemas: MapNode (GeoJSON 2dsphere), MapEdge |
| 3 | `controllers/mapController.js` | Spatial queries: `$nearSphere`, `$geoWithin`, nearest node |
| 4 | `routes/mapRoutes.js` | 4 API endpoints: subgraph, subgraph-bbox, nearest, stats |
| 5 | `scripts/importOSM.js` | GeoJSON parser + batch seeder (5000/batch) |
| 6 | `.env` | MONGO_URI, PORT config |
| 7 | `package.json` | Dependencies + npm scripts (start, dev, seed, docker) |
| 8 | `Dockerfile` | Alpine Node 18, production build, health check |
| 9 | `.dockerignore` | Excludes node_modules, .env from Docker |

### API Endpoints

| Method | Endpoint | Params | Description |
|--------|----------|--------|-------------|
| GET | `/api/map/subgraph` | `lat, lng, radius` | Nodes+edges within radius via `$nearSphere` |
| GET | `/api/map/subgraph-bbox` | `south, west, north, east` | Nodes+edges in bounding box via `$geoWithin` |
| GET | `/api/map/nearest` | `lat, lng` | Single nearest node |
| GET | `/api/map/stats` | — | Node/edge counts |
| GET | `/health` | — | Server + MongoDB health |

### Quick Start

```bash
cd pathfinder-backend
npm install
# Edit .env with your MONGO_URI
npm run dev
# Seed: node scripts/importOSM.js delhi_roads.geojson --force
```

---

## Change #5: Professional BBox + Padding Route Graph Endpoint

**Date:** 2026-05-08  
**Reason:** Need efficient route-specific data fetching — only fetch road data between Start and End locations.  
**Impact:** New `/route-graph` endpoint with dynamic padding, projections, and area stats.

---

### Files Modified

| # | File | Changes |
|---|------|---------|
| 1 | `controllers/mapController.js` | Added `kmToLatDegrees()`, `kmToLngDegrees()`, `calculatePaddedBBox()` utilities. Added `getRouteGraph` controller with `$geoWithin/$box`, Mongoose projections, bbox area stats. |
| 2 | `routes/mapRoutes.js` | Added `GET /route-graph` as primary endpoint. Organized routes with categories. |

### New Endpoint

```
GET /api/map/route-graph?startLat=28.61&startLng=77.20&endLat=28.63&endLng=77.25&padding=1.5
```

### Key Features

| Feature | Detail |
|---------|--------|
| Dynamic Padding | Default 1.5km buffer, configurable via `?padding=` (capped at 5km) |
| Latitude-aware | `kmToLngDegrees()` uses `cos(lat)` for accurate lng conversion |
| Projections | Only returns `osmId`, `coordinates`, `name` for nodes; skips `_id`, `tags`, timestamps |
| $box query | `$geoWithin/$box` with `[bottom-left, top-right]` for fast rectangle query |
| Response stats | Includes `bboxAreaKm2`, node/edge counts |

---

## Change #6: Security, Validation & Android→Backend Integration

**Date:** 2026-05-08  
**Reason:** Backend had no auth, no rate limiting, no input validation. Android app was not connected to backend.  
**Impact:** Production-ready security + automatic Backend/Overpass switching in Android app.

---

### New Files

| # | File | Purpose |
|---|------|---------|
| 1 | `pathfinder-backend/middleware/validate.js` | Input validation middleware (lat/lng ranges, bbox ordering) |
| 2 | `app/.../network/BackendService.kt` | Android service connecting to Node.js backend |

### Files Modified

| # | File | Changes |
|---|------|---------|
| 1 | `server.js` | Added helmet, express-rate-limit (100/15min + 20/min spatial), API key auth, 404/error handlers |
| 2 | `routes/mapRoutes.js` | Wired validation middleware into all routes |
| 3 | `package.json` | Added `express-rate-limit`, `helmet` dependencies |
| 4 | `.env` | Added `API_KEY`, `ALLOWED_ORIGINS`, `NODE_ENV` |
| 5 | `ui/MapActivity.kt` | Backend→Overpass auto-fallback, `useBackend` flag, health check on startup |

### Data Source Flow

```
App starts → checkBackendAvailability()
  → Backend healthy?  YES → useBackend = true  (faster, cached)
                       NO  → useBackend = false (Overpass direct)

Find Routes → fetchGraphData()
  → useBackend?  YES → BackendService.fetchRouteGraph()
                       → If fails → fallback to OverpassService
                  NO  → OverpassService.fetchRoadNetwork()
```

---

## Change #7: Protobuf Streaming Architecture

**Date:** 2026-05-08  
**Reason:** JSON responses too heavy for mobile. Upgrade to Protocol Buffers for Google Maps-like binary streaming.  
**Impact:** ~60-80% smaller payloads (85-90% with Gzip). New v1 streaming API.

---

### New Files

| # | File | Purpose |
|---|------|---------|
| 1 | `proto/map.proto` | Protobuf schema (Node, Edge, LatLng, MapGraph, BoundingBox, GraphStats) |
| 2 | `services/protoSerializer.js` | Loads .proto, serializes MongoDB docs → binary buffer |
| 3 | `controllers/streamController.js` | Streaming endpoint with `Content-Type: application/x-protobuf` |
| 4 | `routes/streamRoutes.js` | v1 streaming routes with validation |

### Files Modified

| # | File | Changes |
|---|------|---------|
| 1 | `server.js` | Added `compression` (Gzip level 6), v1 stream routes, async startup with `loadProto()` |
| 2 | `package.json` | Added `compression` + `protobufjs` dependencies |
| 3 | `scripts/importOSM.js` | Added 6 indexes (2dsphere, osmId unique, compound, individual, roadType) |

### New Endpoints

| Endpoint | Content-Type | Description |
|----------|-------------|-------------|
| `GET /api/v1/stream-map` | `application/x-protobuf` | Binary MapGraph stream |
| `GET /api/v1/stream-map/info` | `application/json` | Schema info + Android decode instructions |

### NPM Packages Added

```
npm install compression protobufjs
```

### Android Decoding

```kotlin
// build.gradle.kts
implementation("com.google.protobuf:protobuf-javalite:4.29.3")

// Kotlin usage
val bytes = response.body?.bytes()
val graph = MapProto.MapGraph.parseFrom(bytes)
graph.nodesList.forEach { node -> /* node.id, node.lat, node.lng */ }
graph.edgesList.forEach { edge -> /* edge.startNode, edge.endNode, edge.distance */ }
```

---

## Change #8: Global PBF Import System (masterImport.js)

**Date:** 2026-05-09  
**Reason:** Need to import full OSM PBF extracts (Geofabrik) with millions of elements efficiently.  
**Impact:** New streaming PBF parser with 3 raw collections + 11 indexes.

---

### New Files

| # | File | Purpose |
|---|------|---------|
| 1 | `scripts/masterImport.js` | PBF streaming importer with bulkWrite batches |
| 2 | `models/OsmData.js` | Raw OSM schemas: OsmNode, OsmWay, OsmRelation |

### Files Modified

| # | File | Changes |
|---|------|---------|
| 1 | `package.json` | Added `osm-pbf-parser`, `master-import` npm script |

### Collections

| Collection | Fields | Indexes |
|------------|--------|---------|
| `OsmNode` | `nodeId`, `location` (GeoJSON), `tags` | 2dsphere, nodeId unique, tags.amenity, tags.shop |
| `OsmWay` | `wayId`, `nodeRefs[]`, `tags` | wayId unique, nodeRefs multikey, tags.highway, tags.building |
| `OsmRelation` | `relationId`, `members[]`, `tags` | relationId unique, tags.type, members.ref |

### Usage

```bash
# Install
cd pathfinder-backend
npm install

# Import Delhi (small, ~50MB)
node scripts/masterImport.js delhi.osm.pbf --force

# Import India (large, ~1.5GB)
node scripts/masterImport.js india-latest.osm.pbf --force --batch=10000

# Skip relations (save time)
node scripts/masterImport.js city.osm.pbf --no-rels

# NPM script
npm run master-import -- delhi.osm.pbf --force
```

### Key Features

| Feature | Detail |
|---------|--------|
| Streaming parser | `osm-pbf-parser` — never loads entire file into memory |
| Batch bulkWrite | 10,000 ops per batch with upsert (configurable `--batch=N`) |
| Progress logger | Real-time: count, rate, elapsed time, memory usage (every 5s) |
| Error tolerance | Duplicate keys silently handled, partial failures counted |
| Selective import | `--no-nodes`, `--no-ways`, `--no-rels` flags |
| 11 indexes | Spatial, unique, multikey, tag-based — all auto-created |

---

## Change #9: Android Retrofit + Protobuf Decoding

**Date:** 2026-05-09  
**Reason:** Connect Android app to backend's Protobuf streaming endpoint.  
**Impact:** 3-tier fallback (Protobuf → JSON → Overpass), structured Retrofit client, hand-written protobuf decoder.

---

### New Files

| # | File | Purpose |
|---|------|---------|
| 1 | `network/MapApiService.kt` | Retrofit interface (4 endpoints) |
| 2 | `network/RetrofitClient.kt` | OkHttp + Retrofit config (API key, timeouts, no converter) |
| 3 | `network/MapRepository.kt` | Repository with Protobuf→JSON→Overpass fallback chain |
| 4 | `proto/MapProto.java` | Hand-written protobuf decoder (Node, Edge, MapGraph) |

### Files Modified

| # | File | Changes |
|---|------|---------|
| 1 | `app/build.gradle.kts` | Added `retrofit`, `converter-scalars`, `protobuf-javalite` |

### Decode Flow

```
Backend sends: application/x-protobuf (gzip compressed)
       ↓
OkHttp auto-decompresses gzip
       ↓
response.body().bytes() → raw protobuf bytes
       ↓
MapProto.MapGraph.parseFrom(bytes) → decoded message
       ↓
graph.nodesList / graph.edgesList → A* ready Graph
```







