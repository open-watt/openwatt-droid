---
name: energy-summary
description: Energy summary screen design — power chart with time scales, appliance consumption ranking, and cost integration. Use when working on the energy summary tab, history charts, appliance list, or cost display.
---

# Energy Summary Screen

The energy summary is the primary energy monitoring view — it answers "how much energy am I using, generating, and spending?" across multiple time horizons. This is a **separate screen/tab** from the home screen, accessed from the bottom navigation.

## Layout Specification

```
┌─────────────────────────────────────┐
│ [Realtime] [Day] [Week] [Month] [Year]  ← tab/chip selector
├─────────────────────────────────────┤
│                                     │
│         POWER / ENERGY CHART        │  Adapts to selected period
│         (see chart modes below)     │  Full-width, ~200dp height
│                                     │
├─────────────────────────────────────┤
│ PERIOD TOTALS                       │
│ ┌──────────┐ ┌──────────┐         │
│ │ Consumed │ │ Generated│         │  Summary stats for the
│ │ 12.4 kWh │ │  8.2 kWh │         │  selected period
│ │   $3.72  │ │  -$2.46  │         │
│ └──────────┘ └──────────┘         │
│ ┌──────────┐ ┌──────────┐         │
│ │ Imported │ │ Exported │         │
│ │  6.1 kWh │ │  1.9 kWh │         │  Grid exchange
│ │   $1.83  │ │  -$0.38  │         │
│ └──────────┘ └──────────┘         │
│        Net cost: $1.45             │
├─────────────────────────────────────┤
│ APPLIANCES              sort: ▼    │
│ ┌─ ☀ Solar Inverter ────────────┐ │
│ │  ↑ 2.3 kW now    8.2 kWh today│ │  Ranked by consumption
│ ├─ 🔌 Hot Water ────────────────┤ │  (or generation, or cost)
│ │  ↓ 3.0 kW now    4.1 kWh today│ │
│ ├─ 🚗 EV Charger ──────────────┤ │  Each row: icon, name,
│ │  ↓ 7.2 kW now    3.8 kWh today│ │  current power, period energy
│ ├─ ❄ Air Con ──────────────────┤ │  Tap to expand detail
│ │  idle            1.2 kWh today│ │
│ └───────────────────────────────┘ │
└─────────────────────────────────────┘
```

## Chart Modes

The chart adapts its rendering based on the selected time period:

### Realtime
- **Type:** Scrolling line chart (continuous)
- **X-axis:** Time, scrolling left as new data arrives
- **Y-axis:** Power in watts (auto-scaled with SI prefix)
- **Window:** ~10 minutes visible, grows from app launch
- **Data:** Client-side buffer, collected every 2 seconds from polling
- **Fill:** Red area above zero (import), green area below zero (export)
- **Zero line:** Emphasized horizontal line
- **No backend history needed** — pure client-side buffer

### Day
- **Type:** Area/line chart
- **X-axis:** Hours 00:00–now (or 00:00–23:59 for past days)
- **Y-axis:** Power in watts
- **Data:** ⚠️ **NEEDS BACKEND API** — time-series power data for the day
- **Interaction:** Swipe left/right to navigate to previous/next days
- **Fill:** Same red/green import/export areas as realtime

### Week
- **Type:** Stacked bar chart
- **X-axis:** 7 bars, one per day (Mon–Sun or rolling 7 days)
- **Y-axis:** Energy in kWh
- **Bars:** Stacked segments for import (red), export (green), self-consumption (blue)
- **Data:** ⚠️ **NEEDS BACKEND API** — daily energy totals
- **Interaction:** Tap bar for day breakdown, swipe for previous weeks

### Month
- **Type:** Stacked bar chart
- **X-axis:** One bar per day of the month (28–31 bars)
- **Y-axis:** Energy in kWh
- **Bars:** Same stacking as weekly
- **Data:** ⚠️ **NEEDS BACKEND API** — daily energy totals for month
- **Interaction:** Swipe for previous months

### Year
- **Type:** Stacked bar chart
- **X-axis:** 12 bars, one per month (Jan–Dec)
- **Y-axis:** Energy in kWh
- **Bars:** Same stacking as weekly/monthly
- **Data:** ⚠️ **NEEDS BACKEND API** — monthly energy totals
- **Interaction:** Swipe for previous years

## Period Totals

Four stat cards below the chart summarize the selected period:

| Stat | Meaning | Source |
|------|---------|--------|
| **Consumed** | Total energy consumed by all loads | Sum of appliance import |
| **Generated** | Total energy produced (solar, etc.) | Sum of producer export |
| **Imported** | Energy drawn from grid | Main circuit import |
| **Exported** | Energy fed to grid | Main circuit export |

Each stat shows:
- Energy value in kWh (auto-scaled: Wh, kWh, MWh)
- Cost/revenue in local currency (when cost data available)

**Net cost** is displayed below: imported cost minus export revenue.

## Appliance List

Ranked list of all appliances (same data as web app's energy summary):

**Columns per row:**
- Type icon (from `Archetypes`)
- Display name
- Current power with direction arrow (↓ consuming, ↑ producing, · idle)
- Period energy total (adapts to selected time period)
- Period cost (when available)

**Sorting options:**
- By consumption (default) — highest energy use first
- By cost — highest cost first
- By current power — highest instantaneous power first
- Alphabetical

**Filtering:**
- Cars are hidden (they're sub-items of EVSEs)
- Disabled appliances shown at bottom, greyed out

**Tap to expand:**
- Shows meter detail panel (power, voltage, current, power factor)
- Same expandable detail levels as the web app (Simple → Detail → Advanced)
- Inverter subsections (solar strings, battery units) with independent expand
- Smart-switch controls inline

## Cost Integration

> **Design principle:** Saving money is a core business goal. Cost must be visible at every level — period totals, per-appliance, and in the chart — not buried in a settings page.

### Cost Data Model

```kotlin
data class TariffRate(
    val importRate: Double,      // $/kWh for energy imported from grid
    val exportRate: Double,      // $/kWh for energy exported to grid (feed-in tariff)
    val currency: String,        // "AUD", "USD", etc.
    val currencySymbol: String,  // "$", "€", etc.
)

// Future: time-of-use tariffs
data class TimeOfUseTariff(
    val periods: List<TariffPeriod>,
)

data class TariffPeriod(
    val name: String,            // "Peak", "Off-Peak", "Shoulder"
    val startHour: Int,          // 0-23
    val endHour: Int,            // 0-23
    val daysOfWeek: List<Int>,   // 1=Mon, 7=Sun
    val importRate: Double,
    val exportRate: Double,
)
```

### Where Cost Appears

1. **Period totals** — Import cost, export revenue, net cost
2. **Appliance rows** — Per-appliance cost for the selected period
3. **Chart overlay** (future) — Cost bands showing peak/off-peak pricing zones
4. **Bar chart segments** — Color-coded by tariff period (peak=red, off-peak=green, shoulder=amber)

### Cost Calculation

For flat-rate tariff:
```
import_cost = imported_kWh * import_rate
export_revenue = exported_kWh * export_rate
net_cost = import_cost - export_revenue
self_consumption_savings = (generated_kWh - exported_kWh) * import_rate
```

For time-of-use (future):
- Each bar chart segment splits into tariff period bands
- Cost calculated per period using the applicable rate

## Required Backend APIs

### Available Now

| Endpoint | Returns | Used For |
|----------|---------|----------|
| `GET /api/energy/circuit` | Circuit tree with real-time meter_data | Current power, realtime chart data source |
| `GET /api/energy/appliances` | Appliance list with real-time meter_data | Appliance list, current power values |

### Needed — Energy History

> **Backend team:** We need time-series energy data for chart rendering and period aggregation.

**Option A: Pre-aggregated endpoint (preferred)**

```
GET /api/energy/history?period=day&date=2026-02-26
GET /api/energy/history?period=week&date=2026-02-26
GET /api/energy/history?period=month&date=2026-02
GET /api/energy/history?period=year&date=2026
```

Response:
```json
{
    "period": "day",
    "date": "2026-02-26",
    "resolution": "5min",
    "series": {
        "timestamps": ["2026-02-26T00:00:00", "2026-02-26T00:05:00", ...],
        "grid_import_power": [1200, 1150, ...],
        "grid_export_power": [0, 0, ...],
        "solar_power": [0, 0, ...],
        "battery_power": [0, 0, ...],
        "load_power": [1200, 1150, ...]
    },
    "totals": {
        "grid_import": 45200000,
        "grid_export": 12000000,
        "solar_generation": 32000000,
        "battery_charge": 8000000,
        "battery_discharge": 6000000,
        "load_consumption": 51200000
    }
}
```

For week/month: resolution becomes "day", for year: "month".

**Option B: Raw time-series query**

```
GET /api/energy/timeseries?path=main.meter.power&from=2026-02-26T00:00&to=2026-02-26T23:59&resolution=5min
```

### Needed — Per-Appliance History

> **Backend team:** Appliance-level energy totals for arbitrary periods, for the ranked consumption list.

```
GET /api/energy/appliances/history?period=day&date=2026-02-26
```

Response:
```json
{
    "period": "day",
    "date": "2026-02-26",
    "appliances": {
        "inverter-1": { "import": 0, "export": 32000000, "cost": -2.46 },
        "evse-1": { "import": 13680000, "export": 0, "cost": 1.14 },
        "hot-water": { "import": 14760000, "export": 0, "cost": 1.23 }
    }
}
```

### Needed — Tariff Configuration

> **Backend team:** Tariff/pricing configuration so the app can display costs.

```
GET /api/energy/tariff
```

Response:
```json
{
    "currency": "AUD",
    "currency_symbol": "$",
    "mode": "flat",
    "import_rate": 0.30,
    "export_rate": 0.05,
    "periods": []
}
```

Or for time-of-use:
```json
{
    "currency": "AUD",
    "currency_symbol": "$",
    "mode": "time_of_use",
    "import_rate": null,
    "export_rate": 0.05,
    "periods": [
        { "name": "Peak", "start_hour": 14, "end_hour": 20, "days": [1,2,3,4,5], "import_rate": 0.52 },
        { "name": "Shoulder", "start_hour": 7, "end_hour": 14, "days": [1,2,3,4,5], "import_rate": 0.30 },
        { "name": "Off-Peak", "start_hour": 20, "end_hour": 7, "days": [1,2,3,4,5,6,7], "import_rate": 0.18 }
    ]
}
```

## Stub Strategy

Until backend APIs are available, the app should:

1. **Realtime chart** — Works immediately using polled data (client-side buffer)
2. **Day/Week/Month/Year charts** — Show "Historical data not yet available" placeholder with the period selector still visible and functional
3. **Period totals** — For "realtime", calculate from current session. For other periods, show "--" with tooltip "Needs history API"
4. **Appliance list** — Works immediately for current power + today's energy (from live cumulative counters, noting they're lifetime not daily)
5. **Cost figures** — Show energy values without cost. Display "Configure tariff in settings" prompt. When tariff API unavailable, allow local tariff entry in app settings as fallback
6. **Today's energy** — Use the live cumulative import/export from meter_data. Note: these are **lifetime counters**, not daily. Ideally the backend provides a daily reset or the app snapshots at midnight. Until then, label as "Session" not "Today"

## Architecture

### Fragment & Navigation
- `EnergySummaryFragment` — lives in a tab/page alongside other energy views
- Accessed via bottom navigation "Energy" item (not part of the home screen)
- Or: Could be a ViewPager2 tab within an Energy section

### ViewModel
- `EnergySummaryViewModel` — owns chart data, appliance list, period selection
- Manages client-side history buffer (realtime chart)
- Fetches historical data when period changes
- Calculates cost from tariff + energy data

### Data Models
```kotlin
// Chart data for all modes
data class ChartData(
    val timestamps: List<Long>,       // epoch millis
    val importPower: List<Double>,    // watts (realtime) or joules (aggregated)
    val exportPower: List<Double>,
    val solarPower: List<Double>?,
    val batteryPower: List<Double>?,
)

// Period totals
data class PeriodTotals(
    val consumed: Double,             // joules
    val generated: Double,            // joules
    val imported: Double,             // joules
    val exported: Double,             // joules
    val importCost: Double?,          // currency
    val exportRevenue: Double?,       // currency
    val netCost: Double?,             // currency
)

// Appliance summary for the list
data class ApplianceSummary(
    val id: String,
    val name: String,
    val type: String,
    val icon: String,
    val currentPower: Double,         // watts, signed
    val periodEnergy: Double,         // joules for selected period
    val periodCost: Double?,          // currency for selected period
    val isProducing: Boolean,
)

enum class ChartPeriod {
    REALTIME, DAY, WEEK, MONTH, YEAR
}
```

### Chart Rendering
- Use `MPAndroidChart` library or custom `Canvas` drawing in a custom `View`
- Realtime mode: ring buffer with fixed max points, shifts on each poll
- Bar chart modes: standard grouped/stacked bar chart

### Polling
- Realtime data: 2s interval (same as web app)
- Historical data: fetch once when period/date changes, no polling needed

## Implementation Status

- [x] Create EnergySummaryFragment with period selector
- [x] Implement realtime chart with client-side buffer (PowerChartView)
- [x] Implement appliance list with current power + ranking (ApplianceListAdapter)
- [x] Add energy API endpoints to OpenWattClient (getCircuits, getAppliances)
- [x] Create energy data models (Circuit, Appliance, MeterData, EnergyParser)
- [x] Stub day/week/month/year charts with placeholder messages (amber banner)
- [x] Stub cost display with backend-todo messages in period totals
- [x] Period totals with realtime cumulative counters + stub for non-realtime periods
- [ ] Integrate history API when backend provides it
- [ ] Integrate tariff API when backend provides it
