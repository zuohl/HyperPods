#!/bin/bash
# vivo TWS Air3 Pro BLE snoop log capture helper
# Usage: bash docs/capture_ble.sh
# Requires: adb in PATH, phone connected via USB debugging

set -e

echo "=== vivo TWS BLE Snoop Log Capture ==="
echo ""

# Check adb
if ! command -v adb &> /dev/null; then
    echo "ERROR: adb not found. Install Android Platform Tools first."
    echo "  https://developer.android.com/tools/releases/platform-tools"
    exit 1
fi

# Check device
DEVICES=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "ERROR: No device connected. Enable USB debugging and connect via USB."
    exit 1
fi
echo "Device connected."

# Enable snoop log
echo ""
echo "[1/5] Enabling Bluetooth HCI snoop log..."
adb shell setprop persist.bluetooth.btsnooplog true
echo "  Done. Bluetooth HCI logging is now ON."

# Restart bluetooth to activate
echo ""
echo "[2/5] Restarting Bluetooth to activate logging..."
adb shell svc bluetooth disable
sleep 2
adb shell svc bluetooth enable
sleep 3
echo "  Bluetooth restarted."

# Clear old logs
echo ""
echo "[3/5] Clearing old snoop logs..."
adb shell "rm -f /data/misc/bluetooth/logs/btsnoop_hci.log" 2>/dev/null || true
echo "  Old logs cleared."

echo ""
echo "[4/5] NOW DO THE FOLLOWING ON YOUR PHONE:"
echo "  1. Connect your vivo TWS Air3 Pro"
echo "  2. Open vivo/iQOO earphone App"
echo "  3. Enter earphone detail page (triggers battery query)"
echo "  4. Toggle ANC: Off -> Noise -> Transparency -> Off"
echo "  5. Toggle Game Mode ON then OFF"
echo "  6. Toggle Dual Device ON then OFF"
echo "  7. Switch EQ presets (if available)"
echo "  8. Remove earbuds, then put them back on"
echo ""
echo "  Press ENTER when done..."
read -r

# Pull log
echo ""
echo "[5/5] Pulling snoop log..."
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTFILE="btsnoop_vivo_tws_${TIMESTAMP}.log"

# Try direct pull first
if adb pull /data/misc/bluetooth/logs/btsnoop_hci.log "$OUTFILE" 2>/dev/null; then
    echo "  Log saved to: $OUTFILE"
else
    echo "  Direct pull failed, trying bugreport..."
    adb bugreport "bugreport_${TIMESTAMP}.zip" 2>/dev/null
    echo "  Bugreport saved. Extract and look for btsnoop_hci.log in:"
    echo "    FS/data/misc/bluetooth/logs/btsnoop_hci.log"
fi

# Disable snoop log
echo ""
echo "Disabling snoop log..."
adb shell setprop persist.bluetooth.btsnooplog false
echo "  Done."

echo ""
echo "=== Capture Complete ==="
echo "Next: parse the log with: python3 docs/parse_btsnoop.py $OUTFILE"