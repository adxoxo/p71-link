import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch
import sys

import colmi


class ProtocolTests(unittest.TestCase):
    def test_known_request_bytes(self):
        self.assertEqual(colmi.request('battery')[0].hex(), '03000000000000000000000000000003')
        self.assertEqual(colmi.request('brightness')[0].hex(), '1b01000000000000000000000000001c')

    def test_invalid_and_misdirected_responses(self):
        valid = colmi.frame(3, bytes([73]))
        self.assertEqual(colmi.decode(valid, 3)['battery_percent'], 73)
        for data, expected in [(valid[:-1], 3), (valid[:-1] + b'\x00', 3), (valid, 27)]:
            with self.assertRaises(ValueError):
                colmi.decode(data, expected)
        rejected = bytes([0x83]) + bytes(14)
        with self.assertRaisesRegex(ValueError, 'rejected'):
            colmi.decode(rejected + bytes([sum(rejected) & 255]), 3)

    def test_live_p71_battery_capture(self):
        # Captured from P71J UART notify in nRF Connect on 2026-11-02.
        data = bytes.fromhex('03260000000000000000000000000029')
        self.assertEqual(colmi.decode(data, 3)['battery_percent'], 38)

    def test_read_only_plans_and_notification_race(self):
        for name in colmi.COMMANDS:
            root = ET.fromstring(colmi.suite('AA:BB:CC:DD:EE:FF', name))
            packet = bytes.fromhex(root.find('.//write').attrib['value'])
            self.assertEqual(packet, colmi.request(name)[0])
            self.assertIsNotNone(root.find('.//assert-notification/write'))
            self.assertEqual(len(root.findall('.//write')), 1)
        root = ET.fromstring(colmi.suite('AA:BB:CC:DD:EE:FF', 'info'))
        self.assertIsNone(root.find('.//write'))
        self.assertIsNone(root.find('.//write-descriptor'))

    def test_input_boundaries(self):
        for address in ['bad', 'AA:BB:CC:DD:EE:FF; reboot', '']:
            with self.assertRaises(ValueError):
                colmi.suite(address, 'battery')
        for level in [-1, 256]:
            with self.assertRaises(ValueError):
                colmi.request('brightness', level)
        with self.assertRaises(ValueError):
            colmi.request('battery', 1)

    def test_notifications_and_find(self):
        # Five text bytes leave six padding bytes in the 11-byte text field.
        # The original fixture had seven padding bytes and was 17 bytes long.
        self.assertEqual(colmi.notification_frames('Hello')[0].hex(),
                         '7201010148656c6c6f00000000000069')
        packets = colmi.notification_frames('Watch: ' + '\u2603' * 8)
        self.assertEqual(b''.join(p[4:15] for p in packets).rstrip(b'\x00'),
                         ('Watch: ' + '\u2603' * 8).encode())
        self.assertEqual([p[3] for p in packets], list(range(1, len(packets) + 1)))
        root = ET.fromstring(colmi.suite('AA:BB:CC:DD:EE:FF', 'find'))
        self.assertEqual(root.find('.//write').attrib['value'], '5055aa0000000000000000000000004f')

    def test_writes_require_explicit_apply_before_bridge(self):
        for name in ['notify', 'find']:
            argv = ['colmi.py', 'run', name, '--address', 'AA:BB:CC:DD:EE:FF', '--text', 'Hello']
            with patch.object(sys, 'argv', argv), patch.object(colmi, 'run_bridge') as bridge:
                with self.assertRaises(SystemExit):
                    colmi.main()
                bridge.assert_not_called()


if __name__ == '__main__':
    unittest.main()
