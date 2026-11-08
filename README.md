# QuietHUD

<img src="src/main/resources/assets/quiethud/icon.png" width="96" alt="QuietHUD icon">

A configurable telemetry HUD for Minecraft on Linux, with a compact text layout and in-game settings.

## Features

- CPU and GPU usage, temperature and clock speed.
- System RAM, process GPU memory, FPS, local time and fan RPM.
- A draggable HUD with configurable entry order, labels, colors and refresh intervals.
- Text opacity, scale, shadow, line spacing, alignment and per-line background settings.
- Automatic sensor discovery, manual source selection and live previews.
- Multiple fan sensors and custom file or shell-command entries.
- Settings integration with Mod Menu, Sodium and Reese's Sodium Options.
- OpenGL and Vulkan rendering through Minecraft's GUI.

## Requirements

- Linux
- Minecraft 26.3
- Java 25 or newer
- Fabric Loader 0.20.1 or newer
- Fabric API 0.165.2+26.3 or a compatible newer build for Minecraft 26.3

For in-game settings, install Mod Menu 21 or Sodium 0.9.2 or newer for Minecraft 26.3. Reese's Sodium Options is supported alongside Sodium.

## Installation

Download the mod JAR from [Releases](https://github.com/Halhadus/quiet-hud/releases) and place it in your Minecraft instance's `mods/` directory.

## Configuration

Open **Mod Menu → QuietHUD → Configure**, or **Sodium / Reese's Sodium Options → QuietHUD → Open QuietHUD settings**.

- **Entries:** enable, add, remove and reorder metrics. Select an entry to edit its label, refresh interval, source and numeric format.
- **Drag HUD:** move the complete group and snap it to screen edges.
- **Appearance:** adjust text scale, opacity, shadow, spacing, alignment and background.
- **System:** control HUD visibility, sampling, clock format, CPU frequency aggregation and shell-command execution.
- **Diagnostics:** view and copy the selected source, current value and status; rescan available sensors.

An empty source field selects **Auto**. The Source page shows the selected path; hover to view it in full or click to copy it. Use the sensor picker or enter an absolute path for manual selection. Sensor availability depends on the hardware, driver and file permissions.

Unavailable Auto sensors are hidden by default. Their status remains available in settings, and automatic hiding can be changed under System. Manual source errors appear as `N/A`.

Changes are applied with **Save & close**. Entry edits are staged until the main settings screen is saved. Configuration is stored in `config/quiethud.json`.

## Custom entries

**FILE** entries read an absolute file path and display the first line. A unit, multiplier or offset enables numeric formatting. For example, a millidegree temperature file can use multiplier `0.001` and unit `°C`.

**COMMAND** entries execute `/bin/sh -c` after **System → Allow local shell commands** is enabled. Commands run with the Minecraft process's permissions and environment. The HUD displays the first output line, up to 160 characters. Execution is limited to 1500 ms, stdout to 4096 bytes, and the refresh interval to at least one second. Diagnostics shows entry errors, and command stderr is sent to the launcher log.

## Building

Use the included Gradle wrapper with JDK 25:

```sh
./gradlew build
```

Built JARs are written to `build/libs/`. Dependency and project versions are configured in `gradle.properties`.

Pushes and pull requests run the build and tests through GitHub Actions. A tag matching `v<project version>`, such as `v1.0.0+26.3`, publishes the mod JAR, sources JAR and checksums after the build succeeds.

## Reporting issues

Use the [issue tracker](https://github.com/Halhadus/quiet-hud/issues) with reproduction steps, version information and relevant diagnostics.

## License

[MIT](LICENSE) © Halhadus.
