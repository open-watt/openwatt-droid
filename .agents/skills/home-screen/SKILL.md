---
name: home-screen
description: Home screen design specification and implementation status. Use when working on the home/dashboard screen, energy flow, smart decisions, or light switch controls.
---

# Home Screen Design

The home screen is the app's primary view — it communicates what OpenWatt does at a glance: energy management, automation intelligence, and home control.

## Layout Specification

Vertical scrolling layout with 4 sections, top to bottom:

```
┌─────────────────────────────────────┐
│ ENERGY FLOW                    hero │  Section 1
│ ┌─────────────────────────────────┐ │
│ │  Solar ──→ Home ←── Grid       │ │  Visual power flow diagram
│ │            ↕    ↕              │ │  showing real-time energy
│ │         Battery  Car           │ │  routing between sources
│ └─────────────────────────────────┘ │  and consumers
├─────────────────────────────────────┤
│ ENERGY OVERVIEW                     │  Section 2
│ ┌────────┐ ┌────────┐ ┌────────┐  │
│ │ Solar  │ │ Grid   │ │Battery │  │  Key energy stats
│ │ 2.3 kW │ │-0.5 kW │ │  85%   │  │  Physical (kW, kWh)
│ │ $4.20  │ │-$0.90  │ │        │  │  + Financial ($)
│ └────────┘ └────────┘ └────────┘  │
├─────────────────────────────────────┤
│ SMART DECISIONS                     │  Section 3
│ "Charging car from excess solar"    │
│ "Battery holding at 85% SoC"       │  What OpenWatt is doing
│                                     │  + override controls
│ [Charge Car Now] [Full by 7am ▼]   │  (EV scheduling, battery
│                                     │   management overrides)
├─────────────────────────────────────┤
│ LIGHTS & SWITCHES                   │  Section 4
│ ┌──────────┐ ┌──────────┐         │
│ │    💡    │ │    💡    │         │  2-column grid of all
│ │  Lounge  │ │  Bedroom │         │  smart switches across
│ │ [==ON==] │ │ [=OFF==] │         │  all devices. Each card
│ │  45 W    │ │          │         │  shows icon, name,
│ └──────────┘ └──────────┘         │  toggle, and optional
│ ┌──────────┐ ┌──────────┐         │  power reading.
│ │    🔌    │ │    🌀    │         │
│ │  Porch   │ │   Fan    │         │  Icons derived from
│ │ [==ON==] │ │ [=OFF==] │         │  switch "type" element:
│ │  12 W    │ │          │         │  light→💡 outlet→🔌
│ └──────────┘ └──────────┘         │  fan→🌀 heater→🔥
└─────────────────────────────────────┘  pump→💧
```

## Section Details

### Section 1: Energy Flow (placeholder)
**Status:** Not yet implemented — requires porting from web app (`js/ui/energy/flow.js`)

Visual diagram showing power flowing between solar panels, grid, battery, EV charger, and home loads. Animated arrows indicate direction and magnitude. This is the hero element — the first thing users see.

**Data source:** `/api/energy/circuit` + `/api/energy/appliances`
**Web reference:** `js/ui/energy/flow.js`, `js/ui/energy/nodes.js`

### Section 2: Energy Overview (placeholder)
**Status:** Not yet implemented — requires energy API integration

Horizontal row of stat cards showing key metrics with both physical units (kW, kWh, %) and financial values ($). Cards vary by what equipment exists (solar, battery, grid, EV).

**Data source:** `/api/energy/circuit` + `/api/energy/appliances`
**Web reference:** `js/ui/energy/summary.js`, `js/ui/energy/meters.js`

### Section 3: Smart Decisions (placeholder)
**Status:** Not yet implemented — requires energy management API

Shows what OpenWatt's automation is currently doing ("Charging car with excess solar", "Holding battery at 85%") and provides override controls:
- **EV Charging:** "Charge Now", "Full by morning", "Full by [time picker]"
- **Battery:** "Force charge", "Force discharge", "Auto"
- **Load shedding:** Manual overrides for managed loads

**Data source:** TBD — needs backend automation status API
**Web reference:** No direct equivalent yet

### Section 4: Lights & Switches (IMPLEMENTED)
**Status:** Implemented

Discovers all Switch-template components across all devices and presents them in a 2-column card grid. Each card shows:
- Switch type icon (from `Archetypes.SWITCH_TYPE_ICONS`)
- Display name (prefers element name, falls back to humanized component ID)
- Material toggle switch
- Power reading (from nested `meter.power` element, when available)

**Data flow:**
1. `HomeViewModel` loads all devices via `OpenWattClient.listDevices()`
2. Fetches DeviceInfo + switch types (once)
3. Extracts switches via `Device.getSwitchComponents()` across all devices
4. Polls switch values + meter data every 2 seconds
5. `HomeSwitch` data class flattens device→component→element into a simple UI model
6. `SwitchGridAdapter` renders cards in RecyclerView with GridLayoutManager(2)
7. Toggle sends `setValues()` then refreshes to get actual state

**Key files:**
- Model: `app/.../model/HomeSwitch.kt`
- ViewModel: `app/.../viewmodel/HomeViewModel.kt`
- Adapter: `app/.../ui/adapters/SwitchGridAdapter.kt`
- Card layout: `app/.../res/layout/item_switch_card.xml`
- Fragment: `app/.../ui/fragments/HomeFragment.kt`
- Layout: `app/.../res/layout/fragment_home.xml`

## Architecture

### HomeViewModel
- Fragment-scoped (not activity-scoped like DashboardViewModel)
- Owns its own `DeviceManager` instance (independent of DevicesFragment)
- Only polls paths relevant to switches (not full device trees)
- Polling: 2s for switch values, 15s for device list changes

### Data Model
```kotlin
data class HomeSwitch(
    val deviceId: String,
    val deviceName: String,
    val switchId: String,
    val switchPath: String,    // relative path within device
    val displayName: String,
    val switchType: String?,   // "light", "outlet", "fan", etc.
    val icon: String,          // emoji from SWITCH_TYPE_ICONS
    val isOn: Boolean,
    val power: Double?,        // watts from meter.power
    val powerFormatted: String?,
)
```

### Switch Path Convention
- Switch value: `{deviceId}.{componentPath}.switch` (boolean)
- Switch type: `{deviceId}.{componentPath}.type` (string: "light", "outlet", etc.)
- Meter power: `{deviceId}.{componentPath}.meter.power` (number, watts)
- Meter import: `{deviceId}.{componentPath}.meter.import` (number, energy)

### Reused Infrastructure
- `DeviceManager` + `Device` — device loading, structure parsing, `getSwitchComponents()`
- `OpenWattClient` — `listDevices()`, `getValues()`, `setValues()`
- `ServerRepository` — server lookup
- `ValueFormatter` — power formatting
- `Archetypes.SWITCH_TYPE_ICONS` — type→emoji mapping

## Future Migration Path

When implementing the energy sections:
1. Add `getCircuits()` and `getAppliances()` to `OpenWattClient`
2. Create energy data models (Circuit, Appliance, MeterData)
3. Add energy data fetching + polling to `HomeViewModel`
4. Replace placeholder cards with real UI components
5. Port flow diagram from web app's canvas rendering to Android (Custom View or Compose Canvas)
