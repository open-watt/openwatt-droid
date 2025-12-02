# OpenWatt Droid

Mobile companion app for OpenWatt - the open-source energy management and home automation platform.

## What is OpenWatt Droid?

OpenWatt Droid is an Android application that brings the power of OpenWatt to your mobile device. Monitor your home energy usage, control IoT devices, and manage your automation system from anywhere.

**Key Features:**
- **Energy Monitoring**: Real-time visibility into solar production, battery storage, and home consumption
- **Device Control**: Manage smart devices across multiple protocols (Modbus, MQTT, Zigbee, CAN, HTTP)
- **Remote Console**: Execute commands and configure your OpenWatt system remotely
- **Live Telemetry**: Stream data from industrial sensors and home automation devices
- **Multi-Site Support**: Connect to multiple OpenWatt instances for managing different locations

## Use Cases

- **Home Energy Management**: Monitor solar panels, battery systems, and household consumption
- **Home Automation**: Control lights, thermostats, appliances, and other smart home devices
- **Industrial Monitoring**: View telemetry from industrial equipment and sensors
- **Remote Administration**: Configure and troubleshoot your OpenWatt system on the go

## Getting Started

### Prerequisites

1. **OpenWatt Backend**: You need a running OpenWatt instance to connect to. See the [OpenWatt repository](https://github.com/open-watt/openwatt) for installation instructions.
2. **Network Access**: Your Android device must be able to reach your OpenWatt server (same WiFi network, VPN, or port forwarding)

### Installation

**Option 1: Install from Release (Coming Soon)**
- Download the latest APK from [Releases](releases)
- Enable "Install from Unknown Sources" on your Android device
- Install the APK

**Option 2: Build from Source**

Requirements:
- Android Studio (latest stable)
- Android SDK (API level 24+)
- Kotlin 2.0.21+
- Gradle 9.0+

Build steps:
```bash
git clone https://github.com/open-watt/openwatt-droid.git
cd openwatt-droid
./gradlew assembleDebug
```

### First-Time Setup

1. Launch OpenWatt Droid
2. Enter your OpenWatt server details:
   - **Server Name**: A friendly name (e.g., "Home Server")
   - **Hostname/IP**: Your server's IP address (e.g., `192.168.1.100`)
   - **Port**: HTTP port (default: `8080`)
3. Tap "Test Connection" to verify connectivity
4. Tap "Add Server" to save and connect

### Usage

**Console Mode**: Execute OpenWatt console commands directly from your phone
- Type commands like `/system/sysinfo`, `/device/print`
- View output and system responses in real-time

**Disconnect**: Use the menu (⋮) → "Disconnect" to switch servers or log out

## Development Status

**Current Features** (v0.1.0):
- [x] Server registration and connection management
- [x] HTTP API client (OkHttp + Gson)
- [x] Remote console command execution
- [x] Real-time command output display
- [x] Multi-server support (switch between instances)

**Planned Features**:
- [ ] Device monitoring dashboard
- [ ] Energy usage graphs and statistics
- [ ] Real-time telemetry streaming (WebSocket)
- [ ] Device control interface
- [ ] Push notifications for alerts
- [ ] Dark mode
- [ ] Offline mode with data caching
- [ ] Authentication and security

## Architecture

Built with modern Android development practices:
- **MVVM Pattern**: Clean separation of UI and business logic
- **Kotlin Coroutines**: Async operations without blocking
- **LiveData**: Reactive UI updates
- **Repository Pattern**: Abstracted data layer
- **ViewBinding**: Type-safe view access

## Contributing

Contributions are welcome! Please see `CLAUDE.md` for development guidelines and architecture details.

## Related Projects

- **[OpenWatt](https://github.com/open-watt/openwatt)**: The backend server platform
- **OpenWatt Web UI** (Coming Soon): Browser-based dashboard

## License

TBD

## Support

For issues, questions, or feature requests, please use the [GitHub Issues](issues) page.
