# Generates app/src/main/assets/world.bin: the world map for the Map page, from Natural Earth (public domain,
# https://github.com/nvkelso/natural-earth-vector/tree/master/geojson) - the same sources as the Tab5 version:
#   ne_50m_land.geojson                        land polygons, 1:50 million
#   ne_50m_admin_0_boundary_lines_land.geojson country borders on land
#   ne_110m_populated_places_simple.geojson    major cities (scalerank 0-1) for the Cities layer
# Format (little-endian): "HPW1"; then three sections, each int32 count and its items:
#   land rings / border lines: int32 points, then int16 lon*100, int16 lat*100 per point (0.01 degree ~ 1 km)
#   cities: uint8 name length, UTF-8 name, int16 lat*100, int16 lon*100
# Usage: python tools/gen_world_asset.py tools/ne app/src/main/assets/world.bin

import json, struct, sys                             # GeoJSON, packing

src, dst = sys.argv[1], sys.argv[2]                  # folder, output


def rings(geom):                                     # every ring / line of a (Multi)Polygon or (Multi)LineString
    t, c = geom['type'], geom['coordinates']         # kind, coordinates
    if t == 'Polygon': return c                      # rings
    if t == 'MultiPolygon': return [r for p in c for r in p] # rings of every polygon
    if t == 'LineString': return [c]                 # one line
    if t == 'MultiLineString': return c              # lines
    return []                                        # other kinds: none


def pack_lines(lines):                               # count, then each line's points
    out = [struct.pack('<i', len(lines))]            # count
    for ln in lines:                                 # each
        pts, last = [], None                         # points, previous (drop repeats after rounding)
        for x, y in ((p[0], p[1]) for p in ln):
            q = (round(x * 100), round(y * 100))     # 0.01 degree
            if q != last: pts.append(q); last = q    # keep
        out.append(struct.pack('<i', len(pts)))      # points
        out.append(b''.join(struct.pack('<hh', *q) for q in pts)) # lon, lat
    return b''.join(out), sum(len(l) for l in lines)


land = [r for f in json.load(open(f'{src}/ne_50m_land.geojson', encoding='utf-8'))['features'] for r in rings(f['geometry'])]
brd = [r for f in json.load(open(f'{src}/ne_50m_admin_0_boundary_lines_land.geojson', encoding='utf-8'))['features'] for r in rings(f['geometry'])]
cities = [f for f in json.load(open(f'{src}/ne_110m_populated_places_simple.geojson', encoding='utf-8'))['features']
          if f['properties'].get('scalerank', 9) <= 1] # the major ones
lb, ln_ = pack_lines(land); bb, bn = pack_lines(brd) # sections
cb = [struct.pack('<i', len(cities))]                # cities
for f in cities:
    name = f['properties']['name'].encode('utf-8')[:255] # name
    lon, lat = f['geometry']['coordinates'][:2]      # position
    cb.append(struct.pack('<B', len(name)) + name + struct.pack('<hh', round(lat * 100), round(lon * 100)))
data = b'HPW1' + lb + bb + b''.join(cb)              # the file
open(dst, 'wb').write(data)                          # write
print(f'{len(land)} land rings ({ln_} points), {len(brd)} border lines ({bn} points), {len(cities)} cities -> {len(data)} bytes')
