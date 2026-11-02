"""Editable COLMI P71J companion via Android and nRF Connect. Python 3.10+."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

SERVICE = '6e40fff0-b5a3-f393-e0a9-e50e24dcca9e'
WRITE = '6e400002-b5a3-f393-e0a9-e50e24dcca9e'
NOTIFY = '6e400003-b5a3-f393-e0a9-e50e24dcca9e'
INFO = '0000180a-0000-1000-8000-00805f9b34fb'
COMMANDS = {'battery': 3, 'brightness': 27, 'dnd': 6,
            'heart-rate-interval': 22, 'oxygen-auto': 44}


def frame(command: int, payload: bytes = b'') -> bytes:
    if not 0 <= command < 128 or len(payload) > 14:
        raise ValueError('Invalid command or payload length')
    data = bytes([command]) + payload + bytes(14 - len(payload))
    return data + bytes([sum(data) & 255])


def decode(data: bytes, expected: int) -> dict:
    if len(data) != 16 or sum(data[:15]) & 255 != data[15]:
        raise ValueError('Invalid response length or checksum')
    if data[0] & 127 != expected:
        raise ValueError('Response belongs to a different command')
    if data[0] & 128:
        raise ValueError('Watch rejected the command')
    result = {'command': expected, 'hex': data.hex(), 'payload': list(data[1:15])}
    if expected == 3:
        if data[1] > 100:
            raise ValueError('Battery response is outside 0..100')
        result['battery_percent'] = data[1]
    elif expected in (6, 22, 27, 44):
        if data[1] != 1:
            raise ValueError('Expected a settings read response')
        if expected == 27:
            result['brightness_raw'] = data[2]
    return result


def request(name: str, level: int | None = None) -> tuple[bytes, bool]:
    if name not in COMMANDS:
        raise ValueError('Unknown named command')
    if level is not None:
        if name != 'brightness' or not 0 <= level <= 255:
            raise ValueError('Brightness is a byte value (0..255); device limits are unverified')
        return frame(COMMANDS[name], bytes([2, level])), True
    return frame(COMMANDS[name], b'' if name == 'battery' else b'\x01'), False


def notification_frames(text: str, kind: int = 1) -> list[bytes]:
    if not text or len(text.encode('utf-16-le')) // 2 > 256 or not 0 <= kind <= 9:
        raise ValueError('Text must contain 1..256 UTF-16 units; type must be 0..9')
    data = text.encode('utf-8')
    chunks = [data[i:i + 11] for i in range(0, len(data), 11)]
    return [frame(0x72, bytes([kind, len(chunks), i + 1]) + chunk.ljust(11, b'\x00'))
            for i, chunk in enumerate(chunks)]


def suite(address: str, name: str, level: int | None = None, text: str | None = None) -> str:
    if not re.fullmatch(r'(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}', address):
        raise ValueError('Expected a Bluetooth MAC address')
    root = ET.Element('test-suite')
    ET.SubElement(root, 'target', id='watch', name='COLMI', address=address)
    test = ET.SubElement(root, 'test', id='query', target='watch', description=name)
    ET.SubElement(test, 'connect', timeout='10000')
    ET.SubElement(test, 'discover-services', timeout='10000')
    if name == 'info':
        for label, short in [('firmware', '2a26'), ('hardware', '2a27')]:
            read = ET.SubElement(test, 'read', description=label, **{
                'service-uuid': INFO,
                'characteristic-uuid': f'0000{short}-0000-1000-8000-00805f9b34fb'})
            if label == 'firmware':
                ET.SubElement(read, 'assert-value', **{'value-string': 'M71J_1.00.27_231223'})
    elif name in ('notify', 'find'):
        frames = notification_frames(text or '') if name == 'notify' else [frame(0x50, b'\x55\xaa')]
        for data in frames:
            ET.SubElement(test, 'write', type='WRITE_COMMAND', value=data.hex(), **{
                'service-uuid': SERVICE, 'characteristic-uuid': WRITE})
    else:
        data, _ = request(name, level)
        ET.SubElement(test, 'write-descriptor', uuid='00002902-0000-1000-8000-00805f9b34fb',
                      **{'service-uuid': SERVICE, 'characteristic-uuid': NOTIFY, 'value': '0100'})
        wait = ET.SubElement(test, 'assert-notification', timeout='7000', **{
            'service-uuid': SERVICE, 'characteristic-uuid': NOTIFY})
        ET.SubElement(wait, 'write', type='WRITE_COMMAND', value=data.hex(), **{
            'service-uuid': SERVICE, 'characteristic-uuid': WRITE})
    ET.SubElement(test, 'disconnect')
    ET.SubElement(root, 'run-test', ref='query')
    return ET.tostring(root, encoding='unicode')


def run_bridge(adb: str, serial: str, xml: str, timeout: float = 45) -> str:
    """Run one task-owned test. No root, APK replacement, or firmware operation."""
    if not 1 <= timeout <= 120:
        raise ValueError('Timeout must be 1..120 seconds')
    base = [adb, '-s', serial]
    def call(*args: str, check: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run(base + list(args), capture_output=True, text=True,
                              timeout=15, check=check)
    stem = 'colmi_' + uuid.uuid4().hex
    directory = '/sdcard/Android/data/no.nordicsemi.android.mcp/files/Test'
    remote = f'{directory}/{stem}.xml'
    result = f'{directory}/{stem}_result.txt'
    completed = False
    try:
        call('get-state')
        with tempfile.TemporaryDirectory(prefix='colmi-') as local:
            path = Path(local) / f'{stem}.xml'
            path.write_text(xml)
            call('shell', 'mkdir', '-p', directory)
            call('push', str(path), remote)
            start = call('shell', 'am', 'start-foreground-service', '-a',
                         'no.nordicsemi.android.action.START_TEST', '-p',
                         'no.nordicsemi.android.mcp', '--es',
                         'no.nordicsemi.android.test.extra.EXTRA_FILE_PATH', remote)
            if 'Error' in start.stdout + start.stderr:
                raise RuntimeError('nRF Connect rejected the test service request')
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                report = call('shell', 'cat', result, check=False)
                if 'Permission denied' in report.stderr:
                    raise PermissionError('Android denied report access; outcome is unverified. '
                                          f'Test files remain at {remote}')
                if report.returncode == 0 and report.stdout.strip():
                    completed = True
                    return report.stdout
                time.sleep(1)
            raise TimeoutError(f'No report before timeout. The test may still run. Retained result path: {result}')
    finally:
        # Only these randomly named, task-owned files may be removed.
        try:
            if completed:
                call('shell', 'rm', '-f', remote, result, check=False)
        except (OSError, subprocess.SubprocessError):
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'run', 'decode'])
    parser.add_argument('command', choices=['info', 'notify', 'find', *COMMANDS])
    parser.add_argument('--address', help='Watch Bluetooth MAC; no default target')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', help='Explicit Android ADB serial')
    parser.add_argument('--level', type=int, help='Experimental brightness byte; inspect existing value first')
    parser.add_argument('--apply', action='store_true', help='Permit the selected settings write')
    parser.add_argument('--hex', help='One 16-byte response to validate and decode')
    parser.add_argument('--text', help='Custom watch notification text')
    args = parser.parse_args()
    try:
        if args.action == 'decode':
            if args.command not in COMMANDS or not args.hex:
                raise ValueError('Decode requires a protocol command and --hex')
            print(json.dumps(decode(bytes.fromhex(args.hex), COMMANDS[args.command]), indent=2))
            return 0
        if not args.address:
            raise ValueError('--address is required')
        if args.command == 'info' and args.level is not None:
            raise ValueError('--level applies only to brightness')
        xml = suite(args.address, args.command, args.level, args.text)
        if args.action == 'plan':
            print(xml)
            return 0
        if args.level is not None and not args.apply:
            raise ValueError('Settings writes require --apply; no command was sent')
        if args.level is not None:
            raise ValueError('Live brightness writes are disabled until device limits and readback are verified')
        if args.command in ('notify', 'find') and not args.apply:
            raise ValueError('Notifications and vibration require --apply; no command was sent')
        if not args.serial:
            raise ValueError('--serial is required for a live operation')
        report = run_bridge(args.adb, args.serial, xml)
        print(report)
        # A bridge report is evidence, not automatic proof of protocol success.
        if re.search(r'\b(?:FAIL|FAILED|FAILURE|ERROR|WARNING)\b', report, re.I):
            raise RuntimeError('nRF Connect reported a failed operation')
        if not re.search(r'Disconnect[^\n]*OK', report, re.I):
            raise RuntimeError('No completed disconnect in the report; outcome is unverified')
        if args.command in COMMANDS:
            raise RuntimeError('Transport report received. Decode a captured reply separately; command success is unverified')
        if args.command in ('notify', 'find'):
            print('Bluetooth write completed. Watch display or vibration still requires user confirmation.')
        return 0
    except (ValueError, OSError, subprocess.SubprocessError, RuntimeError, TimeoutError) as exc:
        parser.exit(1, f'Error: {exc}\n')


if __name__ == '__main__':
    raise SystemExit(main())
