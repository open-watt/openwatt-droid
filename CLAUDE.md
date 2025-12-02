# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenWatt Droid is an Android mobile application written in Kotlin that provides a UI for monitoring and controlling OpenWatt backend instances. OpenWatt is an industrial/IoT communications router and automation platform written in D (see `..\openwatt\CLAUDE.md` for backend details).

**Key objectives:**
- Provide mobile access to OpenWatt's runtime operation
- Display real-time telemetry from industrial protocols (Modbus, MQTT, Zigbee, CAN, etc.)
- Visualize device hierarchies (Device → Component → Element structure)
- Execute console commands remotely
- Monitor system status and device health

## Relationship to Backend (../openwatt)

The backend OpenWatt application is an industrial/IoT communications router and automation platform that provides:
- **Network/IoT routing**: Protocol-agnostic packet routing between industrial protocols
- **Runtime console**: Command-line interface for configuration and monitoring
- **Protocol implementations**: Modbus, MQTT, HTTP, Zigbee, CAN, etc.
- **Device model**: Hierarchical data structure (Device → Component → Element)
- **Collection system**: Runtime object management for devices, interfaces, streams
- **Sampler system**: Periodic data collection from industrial equipment

This Android app interfaces with OpenWatt via a **web API** implemented in the backend. The API exposes:
- Console command execution (execute commands, get output)
- Device data access (list devices, get component/element values, subscribe to updates)
- System status (list interfaces, streams, connections)
- Configuration management (add/remove/modify runtime objects)

## Technology Stack

**Language:** Kotlin 2.0.21
**Build System:** Gradle 9.0 with Android Gradle Plugin 8.13.1
**Target Platform:** Android API 24+ (Android 7.0+)
**UI Framework:** AndroidX with Material Design Components

**Key Libraries:**
- `androidx.core:core-ktx` - Kotlin extensions for Android
- `androidx.appcompat:appcompat` - Backwards-compatible UI components
- `com.google.android.material:material` - Material Design components
- `androidx.constraintlayout:constraintlayout` - Flexible layouts
- `androidx.lifecycle:lifecycle-viewmodel-ktx` - ViewModel with coroutines support
- `androidx.lifecycle:lifecycle-livedata-ktx` - LiveData for reactive UI
- `com.squareup.okhttp3:okhttp` - HTTP client for API communication
- `com.google.code.gson:gson` - JSON serialization
- `org.jetbrains.kotlinx:kotlinx-coroutines-android` - Async operations

## Project Structure

```
openwatt-droid/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/openwatt/droid/
│   │   │   │   └── MainActivity.kt           # Main activity
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   └── activity_main.xml     # UI layouts
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml           # String resources
│   │   │   │   │   ├── colors.xml            # Color palette
│   │   │   │   │   └── themes.xml            # Material themes
│   │   │   │   └── mipmap/                   # App icons
│   │   │   └── AndroidManifest.xml           # App configuration
│   │   └── test/                              # Unit tests
│   ├── build.gradle                           # App-level build config
│   └── proguard-rules.pro                     # Code obfuscation rules
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties          # Gradle wrapper config
├── build.gradle                               # Root build config
├── settings.gradle                            # Project settings
├── gradle.properties                          # Gradle properties
└── README.md                                  # User-facing documentation
```

## Architecture Patterns

### Planned Architecture (Not Yet Implemented)

**MVVM Pattern (Model-View-ViewModel):**
- **Model**: Data classes representing OpenWatt entities (Device, Component, Element, Interface, Stream)
- **ViewModel**: Business logic and API communication, exposing LiveData/StateFlow to views
- **View**: Activities and Fragments observing ViewModel state

**Repository Pattern:**
- Abstract data access behind repository interfaces
- Handle API calls, caching, and error handling
- Provide clean API to ViewModels

**Dependency Injection:**
- Use Hilt or Koin for managing dependencies
- Inject repositories, API clients, and ViewModels

### Current State

**Implemented:**
- **MVVM Pattern**: ViewModels (ServerRegistrationViewModel, ConsoleViewModel) with LiveData
- **Repository Pattern**: ServerRepository for persistent storage using SharedPreferences
- **Network Layer**: CliClient using OkHttp for HTTP API communication
- **Server Management**: Add/test/disconnect from OpenWatt servers
- **CLI Console**: Execute console commands remotely and display output
- **Activities**: MainActivity (router), ServerRegistrationActivity, ConsoleActivity

**Architecture:**
- `model/` - Data classes (Server, CliRequest, CliResponse)
- `network/` - HTTP client (CliClient)
- `repository/` - Data persistence (ServerRepository)
- `viewmodel/` - Business logic ViewModels
- `ui/` - Activities and UI components

## Coding Style

Follow Kotlin Android conventions:

**Naming:**
- Classes/Interfaces: `PascalCase` (e.g., `MainActivity`, `DeviceRepository`)
- Functions/Variables: `camelCase` (e.g., `fetchDevices()`, `deviceList`)
- Constants: `UPPER_SNAKE_CASE` (e.g., `API_BASE_URL`, `MAX_RETRIES`)
- Resource IDs: `snake_case` (e.g., `hello_text_view`, `device_list_item`)

**File Organization:**
- One public class per file
- File name matches class name
- Package structure follows feature or layer organization

**Kotlin Idioms:**
- Prefer `val` over `var` for immutability
- Use data classes for models
- Leverage null safety (`?`, `?.`, `?:`, `!!`)
- Use scope functions (`let`, `apply`, `run`, `also`, `with`) appropriately
- Prefer lambda expressions and higher-order functions

**Android Specifics:**
- Use ViewBinding instead of findViewById
- Prefer Jetpack libraries (ViewModel, LiveData, Room, Navigation)
- Handle lifecycle correctly (avoid memory leaks)
- Use coroutines for async operations
- Follow Material Design guidelines

## Data Model Mapping

OpenWatt backend uses a hierarchical data model that should map to Android data classes:

### Backend Structure (D language)
```d
Device (extends Component)
  ├─ Component
  │    ├─ Element (with Variant value)
  │    └─ Component (nested)
  └─ Component
       └─ Element
```

### Android Equivalent (Kotlin)
```kotlin
data class Device(
    val name: String,
    val state: DeviceState,
    val components: List<Component>
)

data class Component(
    val name: String,
    val elements: List<Element>,
    val subcomponents: List<Component>
)

data class Element(
    val name: String,
    val value: Any?,           // Variant type - could be String, Int, Double, etc.
    val timestamp: Long,
    val unit: String?
)

enum class DeviceState {
    VALIDATE, STARTING, RUNNING, FAILURE, STOPPING, DISABLED, DESTROYED
}
```

## API Integration (To Be Implemented)

The OpenWatt backend will need a web API module added. Expected endpoints:

### Console API
- `POST /api/console/execute` - Execute console command, return output
- `GET /api/console/scopes` - List available command scopes
- `GET /api/console/commands?scope=/device` - List commands in scope

### Device API
- `GET /api/devices` - List all devices
- `GET /api/devices/{name}` - Get device details with full hierarchy
- `GET /api/devices/{name}/components/{path}` - Get specific component
- `GET /api/devices/{name}/elements/{path}` - Get element value
- `WebSocket /api/devices/stream` - Subscribe to real-time element updates

### System API
- `GET /api/system/status` - System info (uptime, version, platform)
- `GET /api/interfaces` - List all interfaces
- `GET /api/streams` - List all streams
- `GET /api/collections/{type}` - List objects in collection (e.g., /api/collections/Device)

### Configuration API
- `POST /api/collections/{type}` - Add object to collection
- `DELETE /api/collections/{type}/{name}` - Remove object
- `PATCH /api/collections/{type}/{name}` - Update object properties

**Authentication:** Will need to add authentication to OpenWatt backend (API keys, JWT tokens, or similar)

## Development Workflow

### IDE Setup

**Android Studio** (Recommended):
1. Open Android Studio
2. File → Open → Select `openwatt-droid` folder
3. Wait for Gradle sync to complete
4. Connect Android device or start emulator
5. Click Run (green play button)

**Command Line:**
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test

# Install on connected device
./gradlew installDebug
```

### Testing

- **Unit Tests**: JUnit 4 for business logic and ViewModels
- **Instrumentation Tests**: Espresso for UI testing
- **Mock API**: Use MockWebServer or WireMock for API testing without backend

### Common Tasks

**Adding a new screen:**
1. Create layout XML in `res/layout/`
2. Create Activity or Fragment class
3. Create ViewModel for business logic
4. Register Activity in AndroidManifest.xml
5. Add navigation logic from existing screens

**Adding API endpoint:**
1. Define data classes for request/response in `model/`
2. Add endpoint to API interface (Retrofit)
3. Create repository method calling endpoint
4. Update ViewModel to use repository
5. Update UI to display data

**Adding real-time updates:**
1. Implement WebSocket client
2. Create data stream (Flow or LiveData)
3. Parse incoming messages to data classes
4. Update ViewModels with streamed data
5. UI observes ViewModel state and updates automatically

## Build Configuration

**App ID:** `com.openwatt.droid`
**Min SDK:** 24 (Android 7.0 Nougat)
**Target SDK:** 34 (Android 14)
**Compile SDK:** 34

**Build Tools:**
- Android Gradle Plugin 8.13.1
- Gradle 9.0
- Kotlin 2.0.21
- Requires Java 11+ (Java 21 recommended for Android Studio)

**Build Features:**
- ViewBinding enabled
- Java 8 language features (lambdas, method references)
- ProGuard/R8 for code shrinking in release builds

**Signing:** Release builds will need keystore configuration (not committed to repo)

## Future Enhancements

- [x] Implement basic web API in OpenWatt backend (`/api/health`, `/api/cli/execute`)
- [x] Add OkHttp HTTP client for API communication
- [x] Add console command execution interface
- [x] Add server registration and connection management
- [ ] Implement Device list and detail screens
- [ ] Implement real-time WebSocket data streaming
- [ ] Add authentication (API keys, JWT tokens)
- [ ] Create data visualization (charts for element values over time)
- [ ] Add notifications for device state changes or alarms
- [ ] Implement offline mode with local caching
- [ ] Support multiple OpenWatt instances (switch between servers)
- [ ] Add dark theme support
- [ ] Implement settings screen (timeouts, refresh rates, etc.)
- [ ] Add command history and autocomplete in console

## Relationship to Backend State Machine

OpenWatt's `BaseObject` state machine affects how UI should display object status:

```
Validate → Starting → Running
             ↓          ↓
         InitFailed   Failure
             ↓          ↓
          (backoff)  Stopping → Disabled/Destroyed
```

**UI Considerations:**
- Display state badges/indicators (green=Running, yellow=Starting, red=Failure, gray=Disabled)
- Show retry countdown during exponential backoff
- Disable controls for objects in transitional states
- Show error messages from InitFailed/Failure states
- Update UI when state changes (via WebSocket or polling)

## Important Notes

- This is a companion app to the OpenWatt backend - it cannot function independently
- All data originates from the backend; this app is a view and control interface
- Network connectivity is required (local network or VPN for remote access)
- Consider handling network interruptions gracefully (retry logic, offline indicators)
- OpenWatt runs on industrial equipment - UI should be reliable and not flood backend with requests
- Respect OpenWatt's 20Hz update cycle when polling or subscribing to data
