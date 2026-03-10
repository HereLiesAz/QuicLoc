# QuicLoc

QuicLoc (com.hereliesaz.quicloc) is an Android utility application that allows users to quickly and automatically share their fine-grained location with a pre-approved list of contacts (a whitelist).

## Features
- **Whitelist Management**: Add or remove trusted phone numbers using a simple user interface.
- **Automated Location Sharing**: When the app receives an SMS message containing exactly the word "loc" or "quicloc" (case-insensitive) from a whitelisted number, it automatically fetches the device's current high-accuracy location and replies with a Google Maps link.
- **Background Execution**: QuicLoc seamlessly operates in the background, ensuring location requests are fulfilled even when the app is closed.

## Permissions
QuicLoc requires the following permissions to function correctly:
- `RECEIVE_SMS` & `SEND_SMS`: To detect incoming trigger words and send the automated response containing the Google Maps link.
- `ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION`: To accurately determine the device's current location.
- `ACCESS_BACKGROUND_LOCATION`: To enable the app to fetch the location while running in the background. Note: On newer Android versions (Android 11+), this permission must be granted separately after allowing foreground location access. The app guides the user through this process.

## Architecture
QuicLoc is built using Kotlin and utilizes the following core components:
- **`SmsReceiver`**: A `BroadcastReceiver` that intercepts incoming text messages and checks them against the trigger words and the user's whitelist. It utilizes `goAsync()` to safely perform asynchronous background work.
- **`LocationHelper`**: A utility object that interacts with Google Play Services (`LocationServices.getFusedLocationProviderClient`) to retrieve the user's high-accuracy location and format the SMS reply.
- **`WhitelistManager`**: Manages the persistence of trusted phone numbers using `SharedPreferences`.

## Building the App
QuicLoc is a standard Gradle-based Android project.
1. Ensure you have the Android SDK (API 33) installed.
2. Run `./gradlew assembleDebug` to build the debug APK.
