---
name: web-app-reference
description: Reference for the OpenWatt Web companion app. Use when migrating features from web to Android, comparing implementations, or looking up how a feature works in the web app.
---

# OpenWatt Web App Reference

The web app (`../openwatt-web`) is the feature-complete reference implementation. This skill maps its codebase so you know exactly where to look when migrating features to Android.

## Technology Overview

- **Vanilla JavaScript** (ES6 modules) — no frameworks, no build tools, no npm
- **Pure HTML/CSS/JS** SPA running entirely client-side
- **~4,600 lines** of JS across 26 files
- Connects to the same OpenWatt backend API as this Android app

## File Map

```
openwatt-web/
├── index.html                              # Entry point (234 lines)
├── css/
│   ├── style.css                          # Main import file
│   ├── base.css                           # Theme variables, typography, reset
│   ├── layout.css                         # Server registration, console, buttons
│   ├── devices.css                        # Device list styling
│   ├── energy.css                         # Energy flow diagram, charts
│   └── config.css                         # Configuration window styling
├── js/
│   ├── main.js                            # App entry point (107 lines)
│   ├── api/
│   │   └── openwatt-client.js             # API client (518 lines) *** KEY FILE ***
│   ├── models/
│   │   ├── server.js                      # Server model (45 lines)
│   │   └── device.js                      # Device + ARCHETYPES + FORMATTERS (200+ lines) *** KEY FILE ***
│   ├── storage/
│   │   └── local-storage.js               # localStorage wrapper (152 lines)
│   ├── ui/
│   │   ├── dashboard.js                   # Main coordinator, nav, polling (500+ lines)
│   │   ├── server-registration.js         # Server connection UI (250+ lines)
│   │   ├── console.js                     # Console interface (150+ lines)
│   │   ├── devices.js                     # Device list/detail view (300+ lines) *** KEY FILE ***
│   │   ├── router.js                      # Config UI orchestrator (520 lines) *** KEY FILE ***
│   │   ├── energy/
│   │   │   ├── index.js                   # EnergyUI coordinator (300 lines)
│   │   │   ├── constants.js               # METER_TYPE, DETAIL_LEVEL, CIRCUIT_TYPE
│   │   │   ├── formatters.js              # Value formatting (180 lines)
│   │   │   ├── chart.js                   # Canvas power history chart (140 lines)
│   │   │   ├── summary.js                 # Summary tab view (180 lines)
│   │   │   ├── flow.js                    # Flow diagram view (280 lines)
│   │   │   ├── details.js                 # Detail panel rendering (400 lines)
│   │   │   ├── meters.js                  # Meter detection & rendering (300 lines)
│   │   │   ├── nodes.js                   # Flow node boxes (180 lines)
│   │   │   └── switches.js               # Smart switch handling (180 lines)
│   │   └── components/
│   │       ├── window-manager.js          # Z-ordering, focus (190 lines)
│   │       ├── floating-window.js         # Draggable/resizable base (455 lines)
│   │       ├── list-window.js             # Table with toolbar (405 lines)
│   │       ├── editor-window.js           # Property editor dialog (450 lines)
│   │       └── property-fields.js         # Schema-to-form field factory (400 lines)
│   └── utils/
│       └── unit-converter.js              # SI prefix formatting (300 lines)
```

## Feature-to-File Lookup

### Server Management
| What | Web File |
|------|----------|
| Server model | `js/models/server.js` |
| Server registration UI | `js/ui/server-registration.js` |
| Connection test | `js/api/openwatt-client.js` → `testConnection()` |
| Server persistence | `js/storage/local-storage.js` |

### Console
| What | Web File |
|------|----------|
| Console UI | `js/ui/console.js` |
| Command execution | `js/api/openwatt-client.js` → `executeCommand()` |
| Command history | In-memory array in `console.js` |

### Device Monitoring
| What | Web File |
|------|----------|
| Device list UI | `js/ui/devices.js` → `DevicesUI` |
| Device model | `js/models/device.js` → `Device`, `DeviceManager` |
| Archetypes | `js/models/device.js` → `ARCHETYPES` |
| Value formatters | `js/models/device.js` → `FORMATTERS` |
| Component tree | `js/ui/devices.js` → `renderComponentTree()` |
| Summary metrics | `js/ui/devices.js` → `renderSummaryMetrics()` |

### Energy Dashboard
| What | Web File |
|------|----------|
| Energy coordinator | `js/ui/energy/index.js` → `EnergyUI` |
| Summary tab | `js/ui/energy/summary.js` |
| Flow diagram | `js/ui/energy/flow.js` |
| Power chart | `js/ui/energy/chart.js` |
| Meter rendering | `js/ui/energy/meters.js` |
| Node boxes | `js/ui/energy/nodes.js` |
| Detail panels | `js/ui/energy/details.js` |
| Smart switches | `js/ui/energy/switches.js` |
| Constants/enums | `js/ui/energy/constants.js` |
| Value formatting | `js/ui/energy/formatters.js` |

### Configuration (Router)
| What | Web File |
|------|----------|
| Config orchestrator | `js/ui/router.js` → `RouterUI` |
| Collection list window | `js/ui/components/list-window.js` → `ListWindow` |
| Property editor | `js/ui/components/editor-window.js` → `EditorWindow` |
| Form field factory | `js/ui/components/property-fields.js` → `PropertyFields` |
| Window base class | `js/ui/components/floating-window.js` → `FloatingWindow` |
| Window manager | `js/ui/components/window-manager.js` → `WindowManager` |

### Utilities
| What | Web File |
|------|----------|
| Unit conversion | `js/utils/unit-converter.js` |
| HTML escaping | `js/ui/energy/formatters.js` → `escapeHtml()` |

## API Client Reference

All API calls are in `js/api/openwatt-client.js` → `OpenWattClient`:

| Client Method | HTTP | Endpoint | Timeout |
|--------------|------|----------|---------|
| `getHealth()` | GET | `/api/health` | 5s |
| `testConnection()` | GET | `/api/health` | 5s |
| `executeCommand(cmd)` | POST | `/api/cli/execute` | 30s |
| `listDevices(path, shallow)` | POST | `/api/list` | 10s |
| `getValues(paths)` | POST | `/api/get` | 10s |
| `setValues(values)` | POST | `/api/set` | 10s |
| `getCircuits()` | GET | `/api/energy/circuit` | 10s |
| `getAppliances()` | GET | `/api/energy/appliances` | 10s |
| `getSchema()` | GET | `/api/schema` | 10s |
| `getEnum(name)` | GET | `/api/enum/{name}` | 10s |
| `listCollection(path)` | POST | `/api/cli/execute` | 10s |
| `addItem(path, props)` | POST | `/api/cli/execute` | 10s |
| `setItem(path, name, props)` | POST | `/api/cli/execute` | 10s |
| `removeItem(path, name)` | POST | `/api/cli/execute` | 10s |

Online state is tracked per server URL via shared static `Map`.

## Device Archetypes (Port to Kotlin)

From `js/models/device.js`:

```javascript
ARCHETYPES = {
    'inverter': {
        icon: 'lightning',
        summary: [
            { label: 'Solar', path: 'solar.meter.power', format: 'power' },
            { label: 'Battery', path: 'battery.soc', format: 'percent' },
            { label: 'Grid', path: 'meter.power', format: 'power-signed' },
            { label: 'Load', path: 'load.power', format: 'power' }
        ],
        stateSource: 'inverter.state',
        stateMap: { 'standby': 'idle', 'grid_tied': 'ok', 'off_grid': 'warn', 'fault': 'error' }
    },
    'energy-meter': {
        icon: 'gauge',
        summary: [
            { label: 'Power', path: 'meter.power', format: 'power-signed' },
            { label: 'Voltage', path: 'meter.voltage', format: 'voltage' },
            { label: 'Import', path: 'meter.import', format: 'energy' },
            { label: 'Export', path: 'meter.export', format: 'energy' }
        ]
    },
    'battery': {
        icon: 'battery',
        summary: [
            { label: 'SoC', path: 'soc', format: 'percent' },
            { label: 'Power', path: 'meter.power', format: 'power-signed' },
            { label: 'Temp', path: 'temp', format: 'temperature' }
        ]
    },
    'evse': {
        icon: 'plug',
        summary: [
            { label: 'State', path: 'state', format: 'evse-state' },
            { label: 'Power', path: 'meter.power', format: 'power' },
            { label: 'Session', path: 'session_energy', format: 'energy' }
        ]
    }
}
```

## Value Formatters (Port to Kotlin)

```javascript
FORMATTERS = {
    'power':        (v) => autoScale(v, 'W'),      // 1234 → "1.23 kW"
    'power-signed': (v) => autoScale(v, 'W') + arrow, // -1234 → "1.23 kW ↑"
    'energy':       (v) => autoScale(v, 'Wh'),     // 45200 → "45.2 kWh"
    'voltage':      (v) => round(v) + ' V',        // 230.1 → "230 V"
    'current':      (v) => round(v, 1) + ' A',     // 15.5 → "15.5 A"
    'percent':      (v) => round(v) + '%',          // 85 → "85%"
    'temperature':  (v) => round(v) + '°C',
    'boolean':      (v) => v ? 'On' : 'Off',
    'evse-state':   (v) => { A:'Standby', B:'Connected', C:'Charging', D:'Fault' }[v]
}
```

## Polling Intervals

| Feature | Interval | Condition |
|---------|----------|-----------|
| System info (health) | 3s | Always when connected |
| Device values | 2s | Server online |
| Device list refresh | 15s | Server online |
| Energy data | 2s | Server online |
| Config collection | 2s | Server online, window open |

All polling pauses when server goes offline (`OpenWattClient.isOnline`).

## Energy Data Structures

### Circuit Tree (from `/api/energy/circuit`)
```javascript
{
    "main": {
        name: "Main Panel",
        type: "three_phase",           // "dc" | "single_phase" | "three_phase"
        meter_data: {
            power: [sum, L1, L2, L3],  // Arrays for 3-phase, scalar for others
            voltage: [avg, L1, L2, L3],
            current: [sum, L1, L2, L3],
            pf: [avg, L1, L2, L3],
            frequency: 50,
            apparent: 5000, reactive: 1000,
            import: 12345.6, export: 789.0  // Cumulative energy (Joules)
        },
        max_current: 63,
        appliances: ["inverter-1"],     // IDs referencing appliances
        sub_circuits: { /* recursive */ }
    }
}
```

### Appliances (from `/api/energy/appliances`)
```javascript
{
    "inverter-1": {
        name: "Solar Inverter",
        type: "inverter",              // inverter | evse | car | ac | water-heater | smart-switch
        enabled: true,
        meter_data: { /* same as circuit */ },
        inverter: {                    // Type-specific data
            rated_power: 10000,
            mppt: [
                { id: "solar", template: "Solar", meter_data: {...} },
                { id: "battery", template: "Battery", soc: 85, meter_data: {...} }
            ]
        }
    }
}
```

## Power State Logic

From `js/ui/energy/meters.js` → `getPowerState()`:

| Context | power > 0 | power < 0 | power ≈ 0 |
|---------|-----------|-----------|-----------|
| DC Battery | Discharging ↑ | Charging ↓ | Standby |
| DC Solar | Producing ↑ | — | Standby |
| AC Circuit | Importing ↓ | Exporting ↑ | Standby |
| AC Appliance | Consuming ↓ | Producing ↑ | Standby |

## Config Schema Format

From `/api/schema`:
```javascript
{
    "collection_name": {
        path: "/interface/modbus",
        properties: {
            "name":    { type: ["str"],  access: "rw", default: null, category: "General", flags: "" },
            "enabled": { type: ["bool"], access: "rw", default: true, category: "General", flags: "" },
            "hidden":  { type: ["str"],  access: "rw", default: null, category: "Advanced", flags: "H" }
        }
    }
}
```

**Property types:** `str`, `bool`, `int`, `uint`, `num`, `byte`, `ipv4`, `eui`, `dt`, `com`, `elem`, `byte[]`, `enum_Name`, `#collection`, `q_unit`, `type[]`

**Flags:** `H` = hidden (skip in UI)

## State Indicators (CSS colors to Material Design)

| State | Web Color | Android Equivalent |
|-------|-----------|-------------------|
| ok / running / producing | Green | `@color/status_ok` |
| warn / standby / starting | Yellow/Amber | `@color/status_warn` |
| error / fault | Red | `@color/status_error` |
| idle / disabled / off | Grey | `@color/status_idle` |
| charging (battery) | Blue | `@color/status_charging` |

## Migration Guidance

When porting a web feature to Android:

1. **Read the web implementation first** — Open the relevant web file(s) listed above
2. **Port data models** — Convert JS classes/objects to Kotlin data classes
3. **Port API calls** — The Android app already has `CliClient`; add new endpoints following the same pattern
4. **Port business logic** — Convert JS functions to Kotlin (formatters, state logic, polling)
5. **Build Android UI** — Don't replicate web DOM; use Material Design components, RecyclerView, Fragments
6. **Port polling** — Use ViewModel coroutines instead of `setInterval()`

**Key differences:**
- Web uses `innerHTML` templates → Android uses XML layouts or Compose
- Web uses `setInterval` polling → Android uses `viewModelScope.launch` + `delay()`
- Web uses `localStorage` → Android uses `SharedPreferences` or Room
- Web uses in-memory state → Android uses `LiveData` / `StateFlow`
- Web uses `fetch()` → Android uses `OkHttp`
- Web energy values in Joules → Convert to kWh for display (same as web does)

## Quick Reference Commands

To read any web app file during migration:
```
Read ../openwatt-web/js/ui/devices.js
Read ../openwatt-web/js/models/device.js
Read ../openwatt-web/js/ui/energy/index.js
Read ../openwatt-web/js/api/openwatt-client.js
```

The web app also has its own skills in `../openwatt-web/.claude/skills/`:
- `backend-reference` — Backend D codebase reference
- `openwatt-api` — API endpoint request/response details
- `component-templates` — Device component structure reference
- `config-tab` — Config UI implementation details
- `devices-tab` — Device tab implementation details
- `energy-tab` — Energy tab implementation details
