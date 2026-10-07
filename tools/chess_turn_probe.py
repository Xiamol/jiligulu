"""Authenticated TURN allocation smoke test. Never prints credentials or shared secrets."""
import argparse
import base64
import hashlib
import hmac
import json
import os
import socket
import ssl
import struct
import time
import urllib.request
from urllib.parse import urlsplit


def attribute(kind, value):
    return struct.pack('!HH', kind, len(value)) + value + bytes((-len(value)) % 4)


def exact(sock, length):
    result = b''
    while len(result) < length:
        block = sock.recv(length - len(result))
        if not block:
            raise RuntimeError('TURN connection closed before a complete response')
        result += block
    return result


def read_response(sock):
    kind, length, cookie, transaction = struct.unpack('!HHI12s', exact(sock, 20))
    if cookie != 0x2112A442 or length > 8192:
        raise RuntimeError('Invalid TURN response')
    body = exact(sock, length)
    attrs, offset = {}, 0
    while offset < len(body):
        key, size = struct.unpack('!HH', body[offset:offset+4])
        attrs[key] = body[offset+4:offset+4+size]
        offset += 4 + size + (-size) % 4
    return kind, transaction, attrs


def allocation_packet(attributes, key=None):
    transaction = os.urandom(12)
    body = b''.join(attributes)
    header = struct.pack('!HHI12s', 3, len(body) + (24 if key else 0), 0x2112A442, transaction)
    integrity = attribute(8, hmac.new(key, header+body, hashlib.sha1).digest()) if key else b''
    return transaction, header + body + integrity


def credentials(args):
    if args.credential_url:
        if not args.credential_url.startswith('https://'):
            raise ValueError('Credential endpoint must use HTTPS')
        with urllib.request.urlopen(args.credential_url, timeout=15) as response:
            value = json.loads(response.read(65536))
        for server in value:
            for url in server['urls'] if isinstance(server['urls'], list) else [server['urls']]:
                if url.startswith('turns:'):
                    parsed = urlsplit(url.replace('turns:', 'https://', 1))
                    return parsed.hostname, parsed.port or 5349, True, server['username'], server['credential']
        raise ValueError('Credential endpoint returned no TLS TURN server')
    secret = os.environ.get(args.secret_env, '')
    if not args.host or not secret:
        raise ValueError('Provide --credential-url, or --host with a nonempty shared-secret environment binding')
    username = f'{int(time.time())+3600}:smoke'
    password = base64.b64encode(hmac.new(secret.encode(), username.encode(), hashlib.sha1).digest()).decode()
    return args.host, args.port, args.tls, username, password


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--credential-url')
    parser.add_argument('--host')
    parser.add_argument('--port', type=int, default=443)
    parser.add_argument('--tls', action='store_true')
    parser.add_argument('--secret-env', default='TURN_SECRET')
    args = parser.parse_args()
    host, port, tls, username, password = credentials(args)
    with socket.create_connection((host, port), timeout=15) as tcp:
        with ssl.create_default_context().wrap_socket(tcp, server_hostname=host) if tls else tcp as sock:
            transaction, packet = allocation_packet([attribute(0x19, bytes([17, 0, 0, 0]))])
            sock.sendall(packet)
            kind, received_transaction, attrs = read_response(sock)
            if received_transaction != transaction or 0x14 not in attrs or 0x15 not in attrs:
                raise RuntimeError('TURN authentication challenge absent or unrelated')
            key = hashlib.md5(username.encode()+b':'+attrs[0x14]+b':'+password.encode()).digest()
            transaction, packet = allocation_packet([attribute(0x19, bytes([17,0,0,0])), attribute(6, username.encode()),
                attribute(0x14, attrs[0x14]), attribute(0x15, attrs[0x15])], key)
            sock.sendall(packet)
            kind, received_transaction, attrs = read_response(sock)
            if kind != 0x0103 or received_transaction != transaction or 0x16 not in attrs:
                raise RuntimeError('Authenticated TURN allocation failed')
            print(json.dumps({'server': host, 'port': port, 'tls': tls, 'authenticated_allocation': True,
                'next_check': 'Two phones on different cellular networks must exchange game moves.'}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Network and validation text contain no credentials; omit full request URLs and traceback.
        print(json.dumps({'authenticated_allocation': False, 'error_type': type(error).__name__}))
        raise SystemExit(1)
