#!/usr/bin/env python3
"""
Parse a btsnoop_hci.log file and extract BLE ATT interactions.
Outputs a human-readable summary of Write/Read/Notify operations.

Usage: python3 docs/parse_btsnoop.py <btsnoop_hci.log>

Requires: pip install pyshark  (or use Wireshark CLI: tshark)
"""

import sys
import struct
import os

def parse_btsnoop(filename):
    """Parse btsnoop file format and extract ATT packets."""
    if not os.path.exists(filename):
        print(f"ERROR: File not found: {filename}")
        sys.exit(1)

    with open(filename, "rb") as f:
        # btsnoop header (16 bytes)
        header = f.read(16)
        if len(header) < 16:
            print("ERROR: Invalid btsnoop file")
            sys.exit(1)

        magic = header[:8]
        if magic != b"btsnoop\x00":
            print(f"ERROR: Not a btsnoop file (magic: {magic})")
            sys.exit(1)

        print(f"=== Parsing {filename} ===")
        print()

        packet_num = 0
        att_ops = []

        while True:
            # Packet record header (24 bytes)
            rec_header = f.read(24)
            if len(rec_header) < 24:
                break

            orig_len, inc_len, flags, drops, ts_hi, ts_lo = struct.unpack(">IIIIII", rec_header[:24])

            # Read packet data
            data = f.read(inc_len)
            if len(data) < inc_len:
                break

            packet_num += 1

            # Try to find ATT data in the packet
            # HCI ACL data packet: type=0x02, handle+flags(2), data_len(2), ACL header...
            if len(data) > 5 and data[0] == 0x02:
                # ACL data
                acl_handle = struct.unpack("<H", data[1:3])[0] & 0x0FFF
                acl_len = struct.unpack("<H", data[3:5])[0]

                if len(data) > 9:
                    # L2CAP header: length(2), CID(2)
                    l2cap_len = struct.unpack("<H", data[5:7])[0]
                    l2cap_cid = struct.unpack("<H", data[7:9])[0]

                    # ATT CID = 0x0004
                    if l2cap_cid == 0x0004 and len(data) > 9:
                        att_data = data[9:]
                        op_code = att_data[0] if att_data else 0

                        # ATT opcodes we care about
                        op_names = {
                            0x0A: "READ_REQ",
                            0x0B: "READ_RSP",
                            0x12: "WRITE_REQ",
                            0x13: "WRITE_RSP",
                            0x1B: "HANDLE_VALUE_NTF",
                            0x52: "WRITE_CMD",
                        }

                        if op_code in op_names:
                            op_name = op_names[op_code]
                            handle = None
                            value = None

                            if op_code in (0x12, 0x52):  # WRITE_REQ, WRITE_CMD
                                if len(att_data) >= 3:
                                    handle = struct.unpack("<H", att_data[1:3])[0]
                                    value = att_data[3:]
                            elif op_code == 0x1B:  # NOTIFY
                                if len(att_data) >= 3:
                                    handle = struct.unpack("<H", att_data[1:3])[0]
                                    value = att_data[3:]
                            elif op_code == 0x0B:  # READ_RSP
                                value = att_data[1:]
                            elif op_code == 0x0A:  # READ_REQ
                                if len(att_data) >= 3:
                                    handle = struct.unpack("<H", att_data[1:3])[0]

                            value_hex = value.hex() if value else ""
                            handle_str = f"0x{handle:04X}" if handle is not None else "----"
                            att_ops.append((packet_num, op_name, handle_str, value_hex))

        # Print summary
        if not att_ops:
            print("No ATT operations found. The file might not contain BLE data,")
            print("or the earphone may use a different transport.")
            print(f"\nTotal packets parsed: {packet_num}")
            return

        print(f"Total packets: {packet_num}")
        print(f"ATT operations: {len(att_ops)}")
        print()
        print(f"{'#':>5}  {'OP':<20} {'HANDLE':<8} {'VALUE (hex)'}")
        print("-" * 70)

        for num, op, handle, value in att_ops:
            # Truncate long values
            display_val = value[:48] + ("..." if len(value) > 48 else "")
            print(f"{num:>5}  {op:<20} {handle:<8} {display_val}")

        # Group by handle
        print()
        print("=== Grouped by Handle ===")
        handles = {}
        for _, op, handle, value in att_ops:
            if handle not in handles:
                handles[handle] = []
            handles[handle].append((op, value))

        for handle in sorted(handles.keys()):
            ops = handles[handle]
            ops_summary = {}
            for op, val in ops:
                if op not in ops_summary:
                    ops_summary[op] = set()
                if val:
                    ops_summary[op].add(val[:24])

            print(f"\nHandle {handle}:")
            for op, values in ops_summary.items():
                print(f"  {op}: {len(values)} unique values")
                for v in sorted(values)[:5]:
                    print(f"    {v}")

        print()
        print("=== Next Steps ===")
        print("1. Look for WRITE_REQ handles — these are command characteristics")
        print("2. Look for HANDLE_VALUE_NTF handles — these push status updates")
        print("3. Correlate write values with the actions you performed")
        print("4. Share this output + your GATT service UUID list")
        print("   and I will write the VivoProtocol implementation")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 parse_btsnoop.py <btsnoop_hci.log>")
        print("       python3 parse_btsnoop.py bugreport_extracted_log.log")
        sys.exit(1)

    parse_btsnoop(sys.argv[1])