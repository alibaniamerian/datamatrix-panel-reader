"""Loopback-only calibration UI and serialized ADB file bridge. No cloud service."""
import argparse
import json
import subprocess
import threading
import time
import uuid
import shutil
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from panel import ROOT, read_panel, calibrate

PACKAGE = 'org.datamatrix.rawcapture'
STATIC = Path(__file__).resolve().parent / 'web'


class Bridge:
    def __init__(self, serial, data):
        self.serial, self.data = serial, Path(data)
        self.data.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.adb = Path(os.environ.get('ADB_PATH') or shutil.which('adb') or str(ROOT / 'tools/android/platform-tools/adb.exe'))

    def run(self, *args, timeout=12):
        p = subprocess.run([str(self.adb), *(['-s', self.serial] if self.serial else []), *args], capture_output=True,
                           timeout=timeout, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        if p.returncode:
            raise RuntimeError(p.stderr.decode('utf-8', 'replace').strip() or p.stdout.decode('utf-8', 'replace').strip())
        return p.stdout

    def read(self, name):
        # All names are constants from the application, never user-supplied paths.
        return self.run('exec-out', 'run-as', PACKAGE, 'cat', 'files/' + name)

    def status(self):
        try:
            return dict(connected=True, serial=self.serial,
                        app=json.loads(self.read('registration_status.json')))
        except Exception as e:
            return dict(connected=False, serial=self.serial, error=str(e))

    def command(self, operation, payload=None):
        with self.lock:
            request_id = uuid.uuid4().hex
            command = dict(operation=operation, request_id=request_id, payload=payload or {})
            local = self.data / 'command.json'
            local.write_text(json.dumps(command), encoding='utf-8')
            remote = '/data/local/tmp/dm_registration_' + request_id + '.json'
            self.run('push', str(local), remote)
            try:
                self.run('shell', 'run-as', PACKAGE, 'cp', remote, 'files/registration_command.pending')
                self.run('shell', 'run-as', PACKAGE, 'mv', 'files/registration_command.pending', 'files/registration_command.json')
            finally:
                self.run('shell', 'rm', '-f', remote)
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline:
                try:
                    reply = json.loads(self.read('registration_status.json'))
                except (RuntimeError, json.JSONDecodeError):
                    reply = {}
                if reply.get('request_id') == request_id:
                    if not reply.get('ok'):
                        raise ValueError(reply.get('error', 'Phone rejected the request'))
                    return reply
                time.sleep(.25)
            try:
                pending = json.loads(self.read('registration_command.json'))
                if pending.get('request_id') == request_id:
                    self.run('shell', 'run-as', PACKAGE, 'rm', '-f', 'files/registration_command.json')
            except Exception:
                pass
            raise TimeoutError('گوشی پاسخ نداد؛ اپ باز و دوربین فعال باشد.')


class Application:
    def device(self):
        status=self.bridge.status()
        with self.lock:
            capture_id=self.capture['request_id'] if self.capture else None
            saved=self.results if self.results.get('capture_id')==capture_id else dict(capture_id=capture_id,values={})
            phone=status.get('app',{})
            if capture_id and phone.get('results_capture_id')==capture_id:
                allowed={p['designator'] for p in self.panel['parts']}
                values={k:v for k,v in phone.get('read_results',{}).items() if k in allowed and isinstance(v,str) and v}
                merged=dict(saved['values'],**values)
                if merged!=saved['values']:
                    saved=dict(capture_id=capture_id,values=merged)
                    (self.bridge.data/'read_results.json').write_text(json.dumps(saved,ensure_ascii=False,indent=2),encoding='utf-8')
                    if self.registration and self.registration.get('capture_id')==capture_id:
                        self.registration['read_results']=merged
                        (self.bridge.data/'calibration.json').write_text(json.dumps(self.registration,ensure_ascii=False,indent=2),encoding='utf-8')
            self.results=saved
            status['results']=saved
        return status

    def reading_options(self, registration):
        registration['auto_cycle'] = not registration.get('body_outline_only', False) and self.settings.get('auto_cycle', True)
        registration['fresh_geometry_per_attempt'] = True
        if registration['auto_cycle'] and 'all_parts' in registration:
            registration['parts'] = registration['all_parts']
        return registration

    def __init__(self, serial, data):
        self.panel = read_panel()
        self.bridge = Bridge(serial, data)
        self.capture = None
        self.registration = None
        self.lock = threading.Lock()
        results_path=self.bridge.data/'read_results.json'
        self.results=json.loads(results_path.read_text(encoding='utf-8')) if results_path.exists() else {}
        settings_path = self.bridge.data/'scan_settings.json'
        self.settings = json.loads(settings_path.read_text(encoding='utf-8')) if settings_path.exists() else {}
        for name, attr in [('capture.json', 'capture'), ('calibration.json', 'registration')]:
            path = self.bridge.data / name
            if path.exists():
                setattr(self, attr, json.loads(path.read_text(encoding='utf-8')))

    def post(self, route, body):
        with self.lock:
            if route == '/api/capture':
                reply = self.bridge.command('capture')
                capture = json.loads(self.bridge.read('registration_frame.json'))
                if capture['request_id'] != reply['request_id']:
                    raise ValueError('Capture response is stale')
                png = self.bridge.read('registration_frame.png')
                if not png.startswith(b'\x89PNG\r\n\x1a\n'):
                    raise ValueError('Invalid camera image')
                (self.bridge.data/'capture.png').write_bytes(png)
                (self.bridge.data/'capture.json').write_text(json.dumps(capture), encoding='utf-8')
                self.capture, self.registration = capture, None
                return dict(capture=capture)
            if route == '/api/register':
                if not self.capture or body.get('capture_id') != self.capture['request_id']:
                    raise ValueError('Take a new image before registration')
                result = calibrate(self.panel, self.capture, body.get('anchors', []),
                                   body.get('roi_width_mm', 15), body.get('roi_height_mm', 9))
                result['calibration_id'] = uuid.uuid4().hex
                from body_outline import refine
                if (self.bridge.data/'capture.png').exists():
                    result = refine(result, self.bridge.data/'capture.png')
                if self.settings.get('reading_enabled'):
                    result['body_outline_only'] = False
                selected = self.settings.get('selected_component')
                self.reading_options(result)
                if selected and not result['auto_cycle']:
                    result['all_parts'] = result['parts']
                    result['parts'] = [p for p in result['parts'] if p['designator'] == selected]
                    if not result['parts']:
                        raise ValueError('Selected component missing from new registration')
                self.registration = result
                (self.bridge.data/'calibration.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
                return dict(registration=result)
            if route == '/api/apply':
                if not self.registration or body.get('calibration_id') != self.registration['calibration_id'] or body.get('capture_id') != self.registration['capture_id']:
                    raise ValueError('Registration changed; review it again')
                self.reading_options(self.registration)
                (self.bridge.data/'calibration.json').write_text(json.dumps(self.registration, ensure_ascii=False, indent=2), encoding='utf-8')
                return self.bridge.command('apply', self.registration)
            if route == '/api/start':
                if not self.registration or body.get('calibration_id') != self.registration['calibration_id'] or body.get('capture_id') != self.registration['capture_id']:
                    raise ValueError('Review the current body outlines first')
                self.registration['body_outline_only'] = False
                self.registration['manual_alignment'] = True
                self.settings['reading_enabled'] = True
                self.settings['auto_cycle'] = True
                self.reading_options(self.registration)
                (self.bridge.data/'scan_settings.json').write_text(json.dumps(self.settings), encoding='utf-8')
                (self.bridge.data/'calibration.json').write_text(json.dumps(self.registration, ensure_ascii=False, indent=2), encoding='utf-8')
                return self.bridge.command('apply', self.registration)
            if route == '/api/select':
                if not self.registration:
                    raise ValueError('Register the panel first')
                all_parts = self.registration.get('all_parts', self.registration['parts'])
                selected = [p for p in all_parts if p['designator'] == body.get('designator')]
                if len(selected) != 1:
                    raise ValueError('Component not found')
                self.registration['all_parts'] = all_parts
                self.registration['parts'] = selected
                self.registration['body_outline_only'] = False
                self.registration['manual_alignment'] = True
                self.settings.update(reading_enabled=True, selected_component=body['designator'],auto_cycle=False)
                self.reading_options(self.registration)
                (self.bridge.data/'scan_settings.json').write_text(json.dumps(self.settings), encoding='utf-8')
                self.registration['calibration_id'] = uuid.uuid4().hex
                (self.bridge.data/'calibration.json').write_text(json.dumps(self.registration, ensure_ascii=False, indent=2), encoding='utf-8')
                return self.bridge.command('apply', self.registration)
            if route == '/api/clear':
                return self.bridge.command('clear')
            raise ValueError('Unknown operation')


def handler_for(app):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            print(format % args, flush=True)

        def send(self, code, data, content_type='application/json; charset=utf-8'):
            if not isinstance(data, bytes):
                data = json.dumps(data, ensure_ascii=False).encode('utf-8')
            self.send_response(code)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            route = urlsplit(self.path).path
            if route == '/api/state':
                return self.send(200, dict(panel=app.panel, capture=app.capture, registration=app.registration))
            if route == '/api/device':
                return self.send(200, app.device())
            if route == '/api/capture.png':
                path = app.bridge.data/'capture.png'
                return self.send(200, path.read_bytes(), 'image/png') if path.exists() else self.send(404, dict(error='No captured image'))
            names = {'/': ('index.html', 'text/html; charset=utf-8'), '/app.js': ('app.js', 'text/javascript; charset=utf-8'), '/style.css': ('style.css', 'text/css; charset=utf-8')}
            if route not in names:
                return self.send(404, dict(error='Not found'))
            name, mime = names[route]
            self.send(200, (STATIC/name).read_bytes(), mime)

        def do_POST(self):
            # No cross-origin browser commands to the USB bridge.
            origin = self.headers.get('Origin')
            if origin and origin != 'http://' + self.headers.get('Host', ''):
                return self.send(403, dict(error='Origin mismatch'))
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if size < 0 or size > 200000:
                    raise ValueError('Request too large')
                body = json.loads(self.rfile.read(size) or b'{}')
                self.send(200, app.post(urlsplit(self.path).path, body))
            except (ValueError, KeyError, TypeError) as e:
                self.send(400, dict(error=str(e)))
            except Exception as e:
                self.send(503, dict(error=str(e)))
    return Handler


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--data', type=Path, default=ROOT/'outputs/panel_registration/session')
    args = parser.parse_args()
    application = Application(args.serial, args.data)
    server = ThreadingHTTPServer(('127.0.0.1', args.port), handler_for(application))
    print('Panel registration: http://127.0.0.1:' + str(args.port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
