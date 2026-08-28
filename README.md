# Moby Wisp

Fork of the Moby paper. This repository holds an Android application with an experimental implementation of Moby over Bluetooth Low Energy (BLE) L2CAP Connection Oriented Channels (CoC) rather than Bluetooth Classic.

## Functions
1. Sends a predetermined payload via Blutooth Classic (Moby)
2. Sends a predetermined payload via Bluetooth Low Energy L2CAP CoC with static UUID and unencrypted PSM (Wisp Public Mode)
3. Sends a predetermined payload via Bluetooth Low Energy L2CAP CoC with Random Resolvable UUID and encrypted PSM (Wisp Private Mode)

## Running Instructions
1. Clone the repository using git clone
2. Open the project in Android Studio
3. Sync Gradle
4. Connect two Android devices via USB or WiFi Debugging
5. Run the application (Do not use the option to run on both devices at the same time. Run on one device, then the other.)
6. Allow all permissions
7. Leave the Moby app and open a second installed app called "BLE Link Test"

## Sending via Bluetooth Classic (Moby)
1. Find the MAC Address of both devices by going to Settings -> About Phone -> Status Information -> Bluetooth Address
2. Go to Settings -> About Phone and change the device name to "MOBY-" + OWN MAC address (eg. MOBY-28:9F:04:9B:23:C2)
3. Input the MAC Address of OWN device into the "Server MAC for RFCOMM" address field
4. Click "Save Own MAC for Moby Proper"
6. Click "Testing Moby Server" on one device
7. Click "Testing Moby Client" on the other device

## Sending via Bluetooth Low Energy Public Mode (Wisp)
1. Click "Toggle Public Mode"
2. Click "Listen (Server)" on one device
3. Click "Connect (Client)" on the other device

## Sending via Bluetooth Low Energy Private Mode (Wisp)
1. Click "Generate IRK" on one device
2. Enter this generated IRK on the other device
3. Click "Save IRK"
4. Click "Listen (Server)" on one device
5. CLick "Connect (Client)" on the other device
