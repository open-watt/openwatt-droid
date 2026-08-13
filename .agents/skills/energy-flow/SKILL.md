---
name: energy-flow
description: Energy flow diagram screen design — circuit tree visualization with animated power flow, appliance nodes, and detail panels. Use when working on the energy flow/detail view, circuit diagrams, or power routing visualization.
---

# Energy Flow Screen

The energy flow screen shows the electrical circuit topology as an interactive tree diagram. It visualizes how power routes between the grid, solar, battery, EV charger, and loads in real time. This is the "advanced" / "detail" energy view — less prominent than energy summary but essential for understanding the system.

## Layout Specification

```
┌─────────────────────────────────────┐
│          CIRCUIT TREE DIAGRAM       │  Scrollable, full-width
│                                     │
│       ┌──────────────────┐         │
│       │ ≋ Main Panel     │         │  Root circuit node
│       │ 3.2 kW IMPORTING │         │  (meter type icon, name,
│       │ 12.4 kWh ↓       │         │   power, energy)
│       └────────┬─────────┘         │
│           ┌────┴─────┐             │
│     ↓ 7.2 kW    ↑ 4.0 kW          │  Edge labels (power + direction)
│           │          │              │
│   ┌───────┴──────┐ ┌┴────────────┐│
│   │ ⚡ Inverter   │ │ 🔌 Hot Water ││  Appliance nodes
│   │ 4.0 kW PROD. │ │ 3.0 kW CONS.││  with subcomponents
│   │ ┈┈┈┈┈┈┈┈┈┈┈┈ │ └─────────────┘│
│   │ 🔋 Bat  85%  │                 │  Inverter shows battery
│   │ ☀ Solar 4 kW │                 │  + solar inside the node
│   └──────────────┘                  │
│                                     │
├─────────────────────────────────────┤
│ DETAIL PANEL                        │  Appears when a node is tapped
│ ┌─────────────────────────────────┐ │
│ │ ⚡ Solar Inverter               │ │  Header: icon + name + type
│ │ Type: inverter                  │ │
│ ├─────────────────────────────────┤ │
│ │ 4.0 kW  PRODUCING ↑            │ │  Power + state
│ │ Voltage: 230 V                  │ │  Expandable detail levels:
│ │ Current: 17.4 A                 │ │    Simple → Detail → Advanced
│ │ TODAY: 32.1 kWh ↑  6.2 kWh ↓  │ │
│ ├─────────────────────────────────┤ │
│ │ 🔋 Battery  85%                │ │  Inverter subsections
│ │    1.2 kW CHARGING ↓           │ │  (battery, solar)
│ │ ☀ Solar                        │ │  individually expandable
│ │    5.2 kW PRODUCING ↑          │ │
│ │         [DETAILS ▼]            │ │
│ └─────────────────────────────────┘ │
└─────────────────────────────────────┘
```

## Circuit Tree Diagram

The diagram renders the circuit hierarchy as a tree, top-down:

### Node Types

**Circuit nodes** represent electrical panels/buses:
- Header: meter type icon + display name
- Power: formatted value + state label (IMPORTING/EXPORTING/STANDBY)
- Max current badge (e.g., "63A") when configured
- Expandable meter details (voltage, current, per-phase for 3-phase)
- Daily energy summary (import ↓ / export ↑)

**Appliance nodes** represent physical devices:
- Header: appliance type icon + display name
- Power: formatted value + state label (CONSUMING/PRODUCING/STANDBY)
- Subcomponents rendered inside the node:
  - **Inverter:** Battery (SoC%, power) + Solar (power) aggregates
  - **EVSE:** Connected car name + power
- Expandable meter details
- Disabled badge when `enabled: false`

### Meter Type Icons

| Meter Type | Icon | Meaning |
|------------|------|---------|
| DC | ⎓ | DC circuit (battery, solar) |
| Single Phase | ∿ | AC single-phase |
| Three Phase | ≋ | AC three-phase |
| Delta | △ | AC delta configuration |

### Connectors

Visual lines connect parent circuits to children:
- **Vertical connector** from circuit to children row
- **Horizontal bar** spanning all children
- **Edge labels** on each vertical drop showing power + direction arrow
- Edge label color: red (consuming/importing), green (producing/exporting), grey (idle)

### Tree Layout Rules

1. Root circuit(s) at top
2. Children arranged horizontally below parent
3. Children include both appliances and sub-circuits
4. Sub-circuits recurse (same rendering, nested)
5. Horizontally scrollable if too many children to fit

## Detail Panel

Appears below the diagram when a node is tapped. Shows comprehensive meter information.

### Detail Levels (progressive disclosure)

| Level | Shows | Toggle |
|-------|-------|--------|
| **Simple** (default) | Power + state + daily energy | [DETAILS ▼] |
| **Detail** | + voltage, current (per-phase for 3Φ) | [LESS ▲] [MORE ▶] |
| **Advanced** | + frequency, power factor (with angle), apparent, reactive | [LESS ▲] [◀ BACK] |

### Circuit Detail Panel
- Circuit name + "Circuit" type label
- Meter display at current detail level
- Max current info when configured

### Appliance Detail Panel
- Appliance name + type label + enabled/disabled badge
- Meter display at current detail level
- **Inverter subsections:**
  - Battery group: aggregate power, average SoC, daily energy
  - Solar group: aggregate power, daily energy
  - Each group expandable to show individual units (battery units, solar strings)
  - Subsection detail levels are independent of main detail level
- **EVSE subsections:**
  - Connected car with name and power
- **Smart-switch controls:**
  - Toggle switch inline for switch-type appliances

### Three-Phase Detail Display

When a circuit/appliance is three-phase, the detail level shows per-phase data:

```
L1: 1.2 kW · 232 V · 5.2 A
L2: 1.0 kW · 230 V · 4.3 A
L3: 0.8 kW · 228 V · 3.5 A
```

Advanced level for 3-phase also shows per-phase PF, apparent, and reactive.

## Power State Logic

Direction and state labels depend on context:

| Context | power > 10W | power < -10W | |power| < 10W |
|---------|-------------|--------------|---------------|
| **DC Battery** | DISCHARGING ↑ (red) | CHARGING ↓ (green) | STANDBY (grey) |
| **DC Solar** | PRODUCING ↑ (green) | — | STANDBY (grey) |
| **DC General** | CONSUMING ↓ (red) | PRODUCING ↑ (green) | STANDBY (grey) |
| **AC Circuit** | IMPORTING ↓ (red) | EXPORTING ↑ (green) | STANDBY (grey) |
| **AC Appliance** | CONSUMING ↓ (red) | PRODUCING ↑ (green) | STANDBY (grey) |

The 10W dead-band prevents flickering near zero.

## Data Source

### Available Now

| Endpoint | Returns | Used For |
|----------|---------|----------|
| `GET /api/energy/circuit` | Circuit tree with nested sub_circuits, appliance refs, meter_data | Entire tree structure |
| `GET /api/energy/appliances` | Appliance details with type-specific data + meter_data | Node content, subcomponents |

### Circuit Data Structure (from API)

```json
{
    "main": {
        "name": "Main Panel",
        "type": "three_phase",
        "meter_data": {
            "power": [3200, 1200, 1000, 1000],
            "voltage": [230, 232, 230, 228],
            "current": [14, 5.2, 4.3, 4.5],
            "pf": [0.98, 0.98, 0.97, 0.99],
            "frequency": 50.01,
            "apparent": 3265,
            "reactive": 640,
            "import": 44640000,
            "export": 11520000
        },
        "max_current": 63,
        "appliances": ["inverter-1", "hot-water"],
        "sub_circuits": {
            "shed": {
                "name": "Shed Panel",
                "type": "single_phase",
                "meter_data": { ... },
                "appliances": ["evse-1"]
            }
        }
    }
}
```

### Appliance Data Structure (from API)

```json
{
    "inverter-1": {
        "name": "Solar Inverter",
        "type": "inverter",
        "enabled": true,
        "meter_data": { ... },
        "inverter": {
            "rated_power": 10000,
            "mppt": [
                { "id": "solar-1", "template": "Solar", "meter_data": { "power": 2600, "voltage": 380, "current": 6.8 } },
                { "id": "solar-2", "template": "Solar", "meter_data": { "power": 2400, "voltage": 375, "current": 6.4 } },
                { "id": "battery", "template": "Battery", "soc": 85, "meter_data": { "power": -1200, "voltage": 52, "current": 23 } }
            ]
        }
    },
    "evse-1": {
        "name": "EV Charger",
        "type": "evse",
        "meter_data": { ... },
        "evse": { "connected_car": "car-1" }
    },
    "car-1": {
        "name": "Tesla Model 3",
        "type": "car",
        "meter_data": { ... },
        "car": { "vin": "5YJ3E1...", "evse": "evse-1" }
    }
}
```

### Needed — Smart Switch Control

> **Backend team:** Smart switch state is fetched/set via the generic `/api/get` and `/api/set` endpoints using element paths. No dedicated energy switch API needed — the existing infrastructure handles this.

## Android Architecture

### Fragment & Navigation
- `EnergyFlowFragment` — sibling tab to `EnergySummaryFragment`
- Could be in a ViewPager2 within an Energy section, or a separate bottom nav destination
- Less prominent than summary — user navigates here for detailed circuit inspection

### Custom View for Tree Diagram

The tree diagram is the most complex rendering in the app. Options:

**Option A: Custom View with Canvas (recommended)**
- Custom `CircuitTreeView` extends `View`
- Measures and lays out nodes as rectangles
- Draws connectors with `Canvas.drawLine()` / `Path`
- Handles touch for node selection
- Advantages: full control, smooth animation, efficient rendering

**Option B: Nested RecyclerView / LinearLayout**
- Build tree from nested ViewGroups
- Each node is an inflated card layout
- Connectors are custom-drawn background `Drawable`s
- Advantages: familiar Android patterns, accessibility built-in
- Disadvantages: complex nesting, harder to draw connectors

**Option C: Jetpack Compose Canvas (future)**
- When/if migrating to Compose, this is ideal
- `Canvas { }` composable for diagram, `LazyColumn` for detail panel

### ViewModel
- `EnergyFlowViewModel` — owns circuit tree, appliance map, selection state
- Polls `/api/energy/circuit` and `/api/energy/appliances` every 2 seconds
- Manages detail level state per node and per subsection
- Handles smart-switch toggle actions

### Data Models

```kotlin
// Reused from energy-summary (shared energy models)
data class Circuit(
    val id: String,
    val name: String?,
    val type: String,                    // "dc", "single_phase", "three_phase", "delta"
    val meterData: MeterData?,
    val maxCurrent: Double?,
    val appliances: List<String>,        // appliance IDs
    val subCircuits: Map<String, Circuit>,
)

data class Appliance(
    val id: String,
    val name: String?,
    val type: String,                    // "inverter", "evse", "car", "smart-switch", etc.
    val enabled: Boolean,
    val meterData: MeterData?,
    val inverter: InverterData?,
    val evse: EvseData?,
    val car: CarData?,
)

data class MeterData(
    val power: PhaseValue,               // watts
    val voltage: PhaseValue?,            // volts
    val current: PhaseValue?,            // amps
    val pf: PhaseValue?,                 // power factor (0-1)
    val frequency: Double?,              // Hz
    val apparent: Double?,               // VA
    val reactive: Double?,               // var
    val import: PhaseValue?,             // joules cumulative
    val export: PhaseValue?,             // joules cumulative
)

// Handles scalar, array [sum, L1, L2, L3], or quantity {q: value}
sealed class PhaseValue {
    data class Scalar(val value: Double) : PhaseValue()
    data class ThreePhase(val sum: Double, val l1: Double, val l2: Double, val l3: Double) : PhaseValue()

    val scalar: Double get() = when (this) {
        is Scalar -> value
        is ThreePhase -> sum
    }
}

data class InverterData(
    val ratedPower: Double?,
    val mppt: List<Mppt>,
)

data class Mppt(
    val id: String,
    val template: String,                // "Solar" or "Battery"
    val soc: Double?,                    // battery only
    val meterData: MeterData?,
)

data class EvseData(
    val connectedCar: String?,           // car appliance ID
)

data class CarData(
    val vin: String?,
    val evse: String?,                   // EVSE appliance ID
)

// Meter type detection
enum class MeterType { DC, SINGLE_PHASE, THREE_PHASE }

// Detail level for progressive disclosure
enum class DetailLevel { SIMPLE, DETAIL, ADVANCED }

// Power state for display
data class PowerState(
    val label: String,                   // "IMPORTING", "EXPORTING", "CONSUMING", etc.
    val stateClass: String,              // "consuming", "producing", "idle"
    val arrow: String,                   // "↓", "↑", ""
)
```

### Shared Components with Energy Summary

Both energy views share:
- `MeterData` model and parsing
- `PhaseValue` sealed class
- `Appliance` / `Circuit` models
- Power state logic (`getPowerState()`)
- Value formatters (power, energy, voltage, current, PF)
- `OpenWattClient` energy endpoint methods
- Detail level expand/collapse logic

These should live in shared packages:
- `model/energy/` — Circuit, Appliance, MeterData, etc.
- `util/` — ValueFormatter, PowerStateHelper
- `network/` — OpenWattClient energy methods

### Polling
- 2s interval for both circuit and appliance data (parallel fetch)
- Polling pauses when fragment not visible (`onPause` / `onResume`)
- Updates diagram in-place without full re-layout when possible (animate value changes)

## Animation (Future Enhancement)

When implemented, the flow diagram could include:
- **Animated arrows** on connectors showing power flow direction
- **Pulse rate** proportional to power magnitude
- **Color transitions** when state changes (idle→producing, etc.)
- **Node highlight** pulse when power changes significantly

This is a polish item — get the static diagram working first.

## Implementation Status

- [x] Create shared energy data models (Circuit, Appliance, MeterData, PhaseValue)
- [x] Add getCircuits() and getAppliances() to OpenWattClient
- [x] Create EnergyFlowFragment with ViewBinding layout
- [x] Implement circuit tree diagram (programmatic ViewGroup composition via CircuitTreeBuilder)
- [x] Implement connector drawing between nodes (View-based connectors)
- [x] Implement node rendering (circuit + appliance variants with MaterialCardView)
- [x] Implement appliance subcomponents (inverter battery/solar, EVSE connected car)
- [x] Implement detail panel with progressive disclosure (MeterDetailView: SIMPLE/DETAIL/ADVANCED)
- [x] Implement per-node detail level state (EnergyFlowViewModel.detailLevels map)
- [ ] Add smart-switch toggle in detail panel
- [x] Add 2s polling with lifecycle awareness (pause on onPause, resume on onResume)
- [ ] Add animated flow indicators (future)
