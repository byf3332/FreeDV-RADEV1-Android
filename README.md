# FreeDV-RADEV1-Android

[![Build Signed Android Release](https://github.com/byf3332/FreeDV-RADEV1-Android/actions/workflows/android-release.yml/badge.svg)](https://github.com/byf3332/FreeDV-RADEV1-Android/actions/workflows/android-release.yml)

An Android port of the **FreeDV RADE V1** modem with real-time RX/TX, audio routing, waterfall display, and USB serial radio control.

This project uses native C code derived from the `radae_nopy` reference implementation and wraps it in a multi-page Android UI built for practical on-device operation. It supports built-in audio devices, USB audio interfaces, and USB serial radio control for CAT / RTS / DTR workflows.

## Project Status

This is an experimental project for personal research and technical exploration.

The implementation is provided as-is and may change frequently. Stability, compatibility, and long-term maintenance are not guaranteed.

## AI-assisted Development

Portions of this project were developed with AI assistance.

## Contributions

This repository is published for reference and educational purposes.

Pull requests and issue reports are currently not accepted.

Please feel free to fork the project if you would like to experiment or build upon it.

## Features

- Real-time RADE V1 encode and decode
- Home / Settings / Logs multi-page UI
- Real-time RX / TX operation
- RX synchronization state, SNR, and frequency offset display
- RX waterfall display with manual offset adjustment
- TX mic level meter
- Configurable TX AGC
- Audio input/output device selection
- USB serial radio control
  - CAT control
  - RTS / DTR line PTT control
- Foreground keep-alive service with wakelock

## Requirements

- Android 8.0 or newer
- ARM64 processor
- Microphone permission
- Audio input/output device
  - built-in audio or external USB sound card
- USB serial adapter if CAT / RTS / DTR control is needed
- Processor performance comparable to or higher than Qualcomm Snapdragon 845
  - The application was developed and tested on this class of device
  - Other devices may also work, but have not been formally tested

## Usage

1. Grant microphone and notification permissions
2. Connect your radio audio interface and, if needed, a USB serial adapter
3. Open **Settings**
4. In **Audio**, press **REFRESH** and select TX / RX audio devices
5. In **Radio Control**, choose the control mode you want
   - `VOX` (CAN CAUSE SYNC AND DELAY ISSUE, IF NOT NEEDED, DON'T USE)
   - `CAT`
   - `RTS`
   - `DTR`
6. If using `CAT`, select a rig profile and serial device
7. Return to **Home**
8. Press **START MODEM**
9. Hold **PTT** to transmit
   - Blue: preparing
   - Green: transmitting
10. Monitor MIC level during transmit and RX sync / SNR / frequency offset during receive, adjust frequency offset if needed
11. Use **RESYNC RX** if synchronization is lost and don't come back in sync

## Third-party code

Third-party attributions and license summaries are maintained in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

This project is licensed under the BSD 2-Clause License.

## Disclaimer

This project is not affiliated with or endorsed by the FreeDV or Codec2 development teams.
