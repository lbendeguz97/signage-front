#!/bin/bash

# Path to the built debug APK
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK file not found at $APK_PATH. Please run build/assemble first."
    exit 1
fi

# Check for connected ADB devices/emulators
# Filters out the header line, empty lines, and unauthorized/offline devices
DEVICES=$(adb devices | tail -n +2 | grep -v -E "^\s*$" | grep -v "unauthorized" | grep -v "offline")

if [ -z "$DEVICES" ]; then
    echo "⚠️ No connected virtual or physical devices found. Skipping APK deployment."
    exit 0
fi

# Install/Push the APK to all connected devices
echo "🚀 Connected device(s) found. Deploying build..."
while read -r line; do
    DEVICE_ID=$(echo "$line" | awk '{print $1}')
    echo "Installing APK on device $DEVICE_ID..."
    
    # Standard installation (recommended for running apps on emulators/devices)
    adb -s "$DEVICE_ID" install -r "$APK_PATH"
    
    # ALTERNATIVE: If you specifically need to 'push' the APK file to a custom directory 
    # (e.g., /data/local/tmp or /system/app for system apps), uncomment the line below:
    # adb -s "$DEVICE_ID" push "$APK_PATH" /data/local/tmp/app-debug.apk
    
done <<< "$DEVICES"
