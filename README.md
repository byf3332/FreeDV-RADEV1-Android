# FreeDV-RADEV1-Android



Use your Android device as a **RADE V1 digital voice modem** (encode and decode).

This project implements a real-time RADE V1 modem on Android using native C code
derived from the official `radae_nopy` implementation. It supports audio-based
transmit and receive using the device microphone/speaker or external USB audio
interfaces.

## Project Status

This is an experimental project intended for personal research and technical exploration.

The implementation is provided as-is and may change frequently without notice.
Stability, compatibility, and long-term maintenance are not guaranteed.

## AI-assisted Development

Portions of this project were developed with the assistance of AI tools (ChatGPT).

## Contributions

This repository is published for reference and educational purposes.

Pull requests and issue reports are currently not accepted.

Please feel free to fork the project if you would like to experiment or build upon it.
## Features

- RADE V1 encode and decode
- Real-time TX / RX operation
- PTT logic (Currently no PTT control implemented, use VOX instead)
- RX synchronization and SNR display
- Mic level display during transmit
- Mic level AGC during transmit
- Foreground service with wakelock

## Requirements

- Android 8.0 or newer
- ARM64 Processor (Other Architectures may also work but not tested)
- Microphone permission
- Audio input/output device  
  (built-in audio or external USB sound card recommended)
- Processor performance comparable to or higher than Qualcomm Snapdragon 845.
  - The application was developed and tested on this platform.  
  Other devices may also work, but have not been formally tested.

## Usage

1. Connect your radio or audio interface
2. Grant microphone and notification permissions
3. Connect your radio's audio interface
4. Press **REFRESH AUDIO DEVICES**
5. Select audio devices
6. Press **START SESSION**
7. Hold down **PTT** to transmit (Blue=Preparing, Green=Transmitting)
8. Monitor MIC LEVEL during transmit to prevent overload
9. Monitor sync and SNR status during receive. Press **RESYNC RX** in case of losing synchronization

## Third-party code

This project includes native code derived from:

RADAE reference implementation  
https://github.com/peterbmarks/radae_nopy

Licensed under the BSD 2-Clause License.

See:

app/src/main/cpp/THIRD_PARTY_LICENSES/

for license details.


## License

This project is licensed under the BSD 2-Clause License.

## Disclaimer

This project is not affiliated with or endorsed by the FreeDV or Codec2 development teams.