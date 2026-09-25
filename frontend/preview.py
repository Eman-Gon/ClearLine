"""Static-only fixture preview. No API routes or processing are implemented here."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
import argparse

ROOT = Path(__file__).resolve().parent


class StaticPreview(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path.startswith('/api/'):
            self.send_error(404, 'Static preview only. Start FastAPI for the live API.')
            return
        if path.startswith('/static/'):
            self.path = self.path.removeprefix('/static')
        elif path == '/':
            self.path = '/index.html'
        super().do_GET()

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=3001)
    args = parser.parse_args()
    print(f'ClearLine synthetic frontend preview: http://localhost:{args.port}', flush=True)
    ThreadingHTTPServer(('127.0.0.1', args.port), StaticPreview).serve_forever()
