// poi_extract.cpp — extract OSM POIs into the `ma_pois` tile layer + two side files.
//
// This is the P27 generator for the maps app's OWN POI layer. POI placement,
// name and type come from OpenStreetMap (baked at build time) so the app never
// scrapes a Google viewport; Google is hit only for rich details on tap.
//
// One pass over an OSM extract (nodes + way/relation centroids) produces three
// mutually-consistent outputs — same POI set, same coordinates:
//
//   1. <geojson>        newline-delimited GeoJSON (geojsonseq) Point features,
//                       fed to tippecanoe to build the `ma_pois` source-layer.
//                       Properties: name (string), type (number), osm_id (number).
//   2. poi_names.bin    DEDUPED UTF-8 name table: each unique name stored ONCE,
//                       NUL-terminated, concatenated. A "name start index" is the
//                       byte offset of the name's first byte. (Same convention as
//                       generator.cpp's road_names.bin.)
//   3. poi_index.bin    Flat array of fixed-size 14-byte records, little-endian,
//                       each a TRIPLE (coordinate, name_start_index, type):
//                           int32_t  lat_e7     latitude  * 1e7
//                           int32_t  lon_e7     longitude * 1e7
//                           uint32_t name_off   byte offset into poi_names.bin
//                           uint16_t type       POI type number (see below)
//                       Records are sorted ascending by the 64-bit Z-order
//                       (Morton) key of (lat,lon) — identical to generator.cpp's
//                       latlng_to_spatial — so the app can mmap + binary-scan a
//                       spatial range. POIs sharing a name point at the same
//                       name_off (that is the dedup win).
//
// A POI is any node OR way/relation-area that has BOTH a `name` tag AND one of
// the recognised POI keys (amenity/shop/tourism/leisure/office/healthcare, plus
// station-like railway/public_transport values -> type 50). The value maps to a
// stable type number; a recognised key with an unmapped value falls into the
// 255 = "other" bucket. Way/relation geometry is reduced to a representative
// centroid (average of outer-ring node locations; nodes use their own location)
// — POIs render as points.
//
// ================================ TYPE MAP ================================
// Stable POI type-number enum (KEEP IN SYNC with the app + README). Never
// renumber an existing value — only append. 255 is the catch-all "other".
//
//   0  restaurant        14 place_of_worship   28 car
//   1  cafe              15 attraction         29 bakery
//   2  fast_food         16 parking            30 books
//   3  bar               17 cinema             31 furniture
//   4  shop (generic)    18 theatre            32 sports_shop
//   5  grocery           19 library            33 department_store
//   6  gas_station       20 post_office        34 dentist
//   7  pharmacy          21 police             35 doctor
//   8  hotel             22 fire_station       36 veterinary
//   9  bank              23 townhall           37 charging_station
//   10 hospital          24 clothing          38 museum
//   11 school            25 electronics       39 office (generic)
//   12 park              26 hardware          40 tourism_info
//   13 gym               27 beauty            41 florist
//   42 jewelry   43 optician   44 laundry   45 pet   46 liquor   47 toys
//   48 gift     49 marketplace   50 station (train/tram/metro/bus, has departures)
//   255 other
//
// Build:  g++ -O3 -std=c++17 poi_extract.cpp -o poi_extract -lz -lexpat -lbz2 -pthread
// Usage:  ./poi_extract IN.osm.pbf --geojson pois.geojsonseq \
//                        --names poi_names.bin --index poi_index.bin

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <unordered_map>
#include <vector>

#include <osmium/area/assembler.hpp>
#include <osmium/area/multipolygon_manager.hpp>
#include <osmium/handler.hpp>
#include <osmium/handler/node_locations_for_ways.hpp>
#include <osmium/index/map/flex_mem.hpp>
#include <osmium/io/any_input.hpp>
#include <osmium/osm/area.hpp>
#include <osmium/osm/node.hpp>
#include <osmium/relations/relations_manager.hpp>
#include <osmium/visitor.hpp>

using namespace std;

using index_type =
    osmium::index::map::FlexMem<osmium::unsigned_object_id_type, osmium::Location>;
using location_handler_type = osmium::handler::NodeLocationsForWays<index_type>;

static const uint16_t TYPE_OTHER = 255;

// ---- OSM tag -> type-number tables (see TYPE MAP above) ------------------
// Precedence when several keys are present: amenity, shop, tourism, leisure,
// office, healthcare. Unmapped-but-recognised keys => TYPE_OTHER.

static const unordered_map<string, uint16_t>& amenity_map() {
    static const unordered_map<string, uint16_t> m = {
        {"restaurant", 0}, {"food_court", 0},
        {"cafe", 1}, {"ice_cream", 1},
        {"fast_food", 2},
        {"bar", 3}, {"pub", 3}, {"biergarten", 3}, {"nightclub", 3},
        {"fuel", 6},
        {"pharmacy", 7},
        {"bank", 9}, {"atm", 9}, {"bureau_de_change", 9},
        {"hospital", 10}, {"clinic", 10},
        {"school", 11}, {"college", 11}, {"university", 11},
        {"kindergarten", 11}, {"language_school", 11}, {"driving_school", 11},
        {"place_of_worship", 14},
        {"parking", 16}, {"parking_entrance", 16}, {"bicycle_parking", 16},
        {"cinema", 17},
        {"theatre", 18}, {"arts_centre", 18},
        {"library", 19},
        {"post_office", 20},
        {"police", 21},
        {"fire_station", 22},
        {"townhall", 23}, {"courthouse", 23},
        {"car_rental", 28}, {"car_wash", 28}, {"car_sharing", 28},
        {"dentist", 34},
        {"doctors", 35},
        {"veterinary", 36},
        {"charging_station", 37},
        {"marketplace", 49},
        {"bus_station", 50},
    };
    return m;
}

static const unordered_map<string, uint16_t>& shop_map() {
    static const unordered_map<string, uint16_t> m = {
        {"supermarket", 5}, {"convenience", 5}, {"greengrocer", 5}, {"grocery", 5},
        {"chemist", 7}, {"pharmacy", 7},
        {"clothes", 24}, {"shoes", 24}, {"boutique", 24}, {"fashion", 24}, {"tailor", 24},
        {"electronics", 25}, {"mobile_phone", 25}, {"computer", 25}, {"hifi", 25},
        {"hardware", 26}, {"doityourself", 26}, {"trade", 26}, {"paint", 26},
        {"hairdresser", 27}, {"beauty", 27},
        {"car", 28}, {"car_repair", 28}, {"car_parts", 28}, {"tyres", 28},
        {"bakery", 29},
        {"books", 30}, {"stationery", 30},
        {"furniture", 31}, {"interior_decoration", 31}, {"houseware", 31},
        {"sports", 32}, {"outdoor", 32}, {"bicycle", 32},
        {"department_store", 33}, {"mall", 33},
        {"florist", 41},
        {"jewelry", 42}, {"jewellery", 42},
        {"optician", 43},
        {"laundry", 44}, {"dry_cleaning", 44},
        {"pet", 45},
        {"alcohol", 46}, {"wine", 46}, {"beverages", 46},
        {"toys", 47},
        {"gift", 48},
    };
    return m;
}

static const unordered_map<string, uint16_t>& tourism_map() {
    static const unordered_map<string, uint16_t> m = {
        {"hotel", 8}, {"motel", 8}, {"hostel", 8}, {"guest_house", 8}, {"apartment", 8},
        {"attraction", 15}, {"theme_park", 15}, {"zoo", 15}, {"viewpoint", 15},
        {"artwork", 15}, {"gallery", 15}, {"aquarium", 15},
        {"museum", 38},
        {"information", 40},
    };
    return m;
}

static const unordered_map<string, uint16_t>& leisure_map() {
    static const unordered_map<string, uint16_t> m = {
        {"park", 12}, {"garden", 12}, {"nature_reserve", 12},
        {"fitness_centre", 13}, {"sports_centre", 13},
    };
    return m;
}

static const unordered_map<string, uint16_t>& healthcare_map() {
    static const unordered_map<string, uint16_t> m = {
        {"pharmacy", 7},
        {"hospital", 10}, {"clinic", 10},
        {"dentist", 34},
        {"doctor", 35}, {"centre", 35},
        {"veterinary", 36},
        {"optometrist", 43},
    };
    return m;
}

// Classify a tag set. Returns true and sets `out_type` when the object is a POI
// (has one of the recognised POI keys). `office=*` maps to the generic office
// type (39) unless a higher-precedence key already matched.
static bool classify(const osmium::TagList& tags, uint16_t& out_type) {
    // Transit stations (type 50) take precedence. Only station-like values
    // qualify — bare railway/public_transport values (tracks, signals, bus
    // poles, platforms) are NOT POIs, so we must NOT fall through to the generic
    // "recognised key -> TYPE_OTHER" path for them (that would flood the map).
    {
        const char* rw = tags.get_value_by_key("railway");
        if (rw && (strcmp(rw, "station") == 0 || strcmp(rw, "halt") == 0 ||
                   strcmp(rw, "tram_stop") == 0)) {
            out_type = 50;
            return true;
        }
        const char* pt = tags.get_value_by_key("public_transport");
        if (pt && strcmp(pt, "station") == 0) {
            out_type = 50;
            return true;
        }
    }
    struct KV { const char* key; const unordered_map<string, uint16_t>* map; };
    const KV order[] = {
        {"amenity", &amenity_map()},
        {"shop", &shop_map()},
        {"tourism", &tourism_map()},
        {"leisure", &leisure_map()},
        {"healthcare", &healthcare_map()},
    };
    bool recognised = false;
    for (const auto& kv : order) {
        const char* v = tags.get_value_by_key(kv.key);
        if (!v || !*v || strcmp(v, "no") == 0) continue;
        recognised = true;
        auto it = kv.map->find(v);
        if (it != kv.map->end()) { out_type = it->second; return true; }
    }
    // office=* is a POI but almost always the generic office bucket.
    const char* off = tags.get_value_by_key("office");
    if (off && *off && strcmp(off, "no") != 0) {
        if (strcmp(off, "government") == 0) { out_type = 23; return true; }
        out_type = 39;
        return true;
    }
    if (recognised) { out_type = TYPE_OTHER; return true; }
    return false;
}

// ---- Morton / spatial key (identical to generator.cpp latlng_to_spatial) ---
static uint64_t latlng_to_spatial(double lat, double lon) {
    double x = (lon + 180.0) / 360.0;
    double y = (lat + 90.0) / 180.0;
    uint32_t ix = (uint32_t)(x * 4294967295.0);
    uint32_t iy = (uint32_t)(y * 4294967295.0);
    uint64_t res = 0;
    for (int i = 0; i < 32; i++) {
        res |= ((uint64_t)((ix >> i) & 1) << (2 * i));
        res |= ((uint64_t)((iy >> i) & 1) << (2 * i + 1));
    }
    return res;
}

struct Poi {
    double lat, lon;
    int32_t lat_e7, lon_e7;
    uint64_t morton;
    uint16_t type;
    int64_t osm_id;
    string name;
};

#pragma pack(push, 1)
struct PoiRecord {   // 14 bytes, little-endian
    int32_t lat_e7;
    int32_t lon_e7;
    uint32_t name_off;
    uint16_t type;
};
#pragma pack(pop)
static_assert(sizeof(PoiRecord) == 14, "PoiRecord must be 14 bytes");

class PoiHandler : public osmium::handler::Handler {
public:
    vector<Poi>& pois;
    explicit PoiHandler(vector<Poi>& out) : pois(out) {}

    void node(const osmium::Node& node) {
        const char* name = node.tags().get_value_by_key("name");
        if (!name || !*name) return;
        uint16_t type;
        if (!classify(node.tags(), type)) return;
        if (!node.location().valid()) return;
        add(node.location().lat(), node.location().lon(), type,
            (int64_t)node.id(), name);
    }

    void area(const osmium::Area& area) {
        const char* name = area.tags().get_value_by_key("name");
        if (!name || !*name) return;
        uint16_t type;
        if (!classify(area.tags(), type)) return;

        // Representative centroid: average of outer-ring node locations.
        double sum_lat = 0.0, sum_lon = 0.0;
        uint64_t n = 0;
        for (const auto& item : area) {
            if (item.type() != osmium::item_type::outer_ring) continue;
            const auto& ring = static_cast<const osmium::OuterRing&>(item);
            for (const auto& nr : ring) {
                if (!nr.location().valid()) continue;
                sum_lat += nr.location().lat();
                sum_lon += nr.location().lon();
                ++n;
            }
        }
        if (n == 0) return;
        // orig_id() is the source way/relation id; sign it negative for
        // relations so way/relation ids never collide in the osm_id attribute.
        int64_t oid = (int64_t)area.orig_id();
        if (!area.from_way()) oid = -oid;
        add(sum_lat / (double)n, sum_lon / (double)n, type, oid, name);
    }

private:
    void add(double lat, double lon, uint16_t type, int64_t osm_id,
             const char* name) {
        Poi p;
        p.lat = lat;
        p.lon = lon;
        p.lat_e7 = (int32_t)llround(lat * 1e7);
        p.lon_e7 = (int32_t)llround(lon * 1e7);
        p.morton = latlng_to_spatial(lat, lon);
        p.type = type;
        p.osm_id = osm_id;
        p.name = name;
        pois.push_back(std::move(p));
    }
};

static void json_escape(const string& s, string& out) {
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += (char)c;  // pass UTF-8 bytes through verbatim
                }
        }
    }
}

int main(int argc, char* argv[]) {
    if (argc < 2) {
        cerr << "Usage: " << argv[0]
             << " IN.osm.pbf --geojson FILE --names FILE --index FILE\n";
        return 1;
    }
    string in_path = argv[1];
    string geojson_path, names_path, index_path;
    for (int i = 2; i < argc; ++i) {
        string a = argv[i];
        if (a == "--geojson" && i + 1 < argc) geojson_path = argv[++i];
        else if (a == "--names" && i + 1 < argc) names_path = argv[++i];
        else if (a == "--index" && i + 1 < argc) index_path = argv[++i];
        else { cerr << "Unknown/incomplete arg: " << a << "\n"; return 1; }
    }
    if (geojson_path.empty() || names_path.empty() || index_path.empty()) {
        cerr << "ERROR: --geojson, --names and --index are all required\n";
        return 1;
    }

    vector<Poi> pois;

    osmium::io::File input_file{in_path};

    // Pass 1: collect multipolygon/boundary relations for the area assembler.
    osmium::area::Assembler::config_type assembler_config;
    assembler_config.create_empty_areas = false;
    osmium::area::MultipolygonManager<osmium::area::Assembler> mp_manager{
        assembler_config};
    cerr << "[poi] pass 1: reading relations\n";
    osmium::relations::read_relations(input_file, mp_manager);

    // Pass 2: nodes (direct) + assembled areas (closed ways + relations).
    index_type index;
    location_handler_type location_handler{index};
    location_handler.ignore_errors();
    PoiHandler handler{pois};

    cerr << "[poi] pass 2: extracting POIs\n";
    osmium::io::Reader reader{input_file};
    osmium::apply(reader, location_handler, handler,
                  mp_manager.handler([&handler](osmium::memory::Buffer&& buffer) {
                      osmium::apply(buffer, handler);
                  }));
    reader.close();

    cerr << "[poi] extracted " << pois.size() << " POI(s)\n";

    // Deterministic order: sort by Morton spatial key (ties broken by osm_id) so
    // both poi_index.bin and the geojson are stable across runs.
    sort(pois.begin(), pois.end(), [](const Poi& a, const Poi& b) {
        if (a.morton != b.morton) return a.morton < b.morton;
        return a.osm_id < b.osm_id;
    });

    // Build the deduped name pool (NUL-terminated) and the flat record array.
    ofstream names_out(names_path, ios::binary);
    ofstream index_out(index_path, ios::binary);
    ofstream geojson_out(geojson_path, ios::binary);
    if (!names_out || !index_out || !geojson_out) {
        cerr << "ERROR: cannot open output file(s)\n";
        return 1;
    }

    unordered_map<string, uint32_t> name_pool;
    uint32_t name_offset = 0;
    string line;

    for (const auto& p : pois) {
        auto it = name_pool.find(p.name);
        uint32_t off;
        if (it != name_pool.end()) {
            off = it->second;
        } else {
            off = name_offset;
            name_pool.emplace(p.name, off);
            names_out.write(p.name.c_str(), (streamsize)p.name.size());
            char nul = '\0';
            names_out.write(&nul, 1);
            name_offset += (uint32_t)p.name.size() + 1;
        }

        PoiRecord rec{p.lat_e7, p.lon_e7, off, p.type};
        index_out.write((const char*)&rec, sizeof(rec));

        line.clear();
        line += "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[";
        char coord[64];
        snprintf(coord, sizeof(coord), "%.7f,%.7f", p.lon, p.lat);
        line += coord;
        line += "]},\"properties\":{\"name\":\"";
        json_escape(p.name, line);
        char props[64];
        snprintf(props, sizeof(props), "\",\"type\":%u,\"osm_id\":%lld}}\n",
                 (unsigned)p.type, (long long)p.osm_id);
        line += props;
        geojson_out.write(line.data(), (streamsize)line.size());
    }

    cerr << "[poi] wrote " << name_pool.size() << " unique name(s), "
         << pois.size() << " record(s)\n";
    cerr << "[poi]   " << geojson_path << " (geojsonseq for ma_pois)\n";
    cerr << "[poi]   " << names_path << " (" << name_offset << " bytes)\n";
    cerr << "[poi]   " << index_path << " ("
         << (uint64_t)pois.size() * sizeof(PoiRecord) << " bytes)\n";
    return 0;
}
