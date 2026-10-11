"""Independent Relay protocol checks; temporary SQLite and ephemeral loopback only."""
import concurrent.futures
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import unittest
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from base import Problem, handler
from service import Service
from http.server import ThreadingHTTPServer


class Gate:
    def __init__(self, delegate, command_id):
        self.delegate, self.command_id = delegate, command_id
        self.entered, self.release = threading.Event(), threading.Event()

    def route(self, method, path, body):
        if path == '/commands' and body.get('id') == self.command_id:
            self.entered.set()
            if not self.release.wait(8):
                raise RuntimeError('Review gate not released')
        return self.delegate.route(method, path, body)


class RelayReview(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='relay-review-')
        self.addCleanup(self.temp.cleanup)
        self.database = Path(self.temp.name) / 'relay.sqlite'
        self.time = [1_000_000]
        self.a = Service(self.database, clock=lambda: self.time[0])
        self.b = Service(self.database, clock=lambda: self.time[0])
        self.servers = []
        self.addCleanup(self.stop)

    def stop(self):
        for server, thread in self.servers:
            server.shutdown(); server.server_close(); thread.join(2)

    def serve(self, service):
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler(service))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start(); self.servers.append((server, thread))
        return f'http://127.0.0.1:{server.server_port}'

    def envelope(self, action, args=None, identity=None):
        return {'id': identity or str(uuid.uuid4()), 'action': action, 'args': args or {}}

    def send(self, action, args=None, identity=None, owner=None):
        return (owner or self.a).route('POST', '/commands', self.envelope(action,args,identity))[1]

    def view(self, owner=None):
        return (owner or self.a).route('GET', '/view', {})[1]['data']

    def start(self):
        result = self.send('start')['outcome']
        self.assertTrue(result['accepted'])
        return {'tripId': result['trip']['id'], 'epoch': result['epoch']}

    def http(self, base, path, body=None):
        req = Request(base + path, data=None if body is None else json.dumps(body).encode(),
                      headers={'Content-Type':'application/json'})
        with urlopen(req,timeout=4) as response:
            return response.status,json.load(response)

    def test_reordered_fixes_and_duplicate_preserve_both_original_timestamps(self):
        grant = self.start()
        first = self.send('capture', grant)['outcome']['fix']
        self.time[0] += 1000
        second = self.send('capture', grant)['outcome']['fix']
        self.time[0] += 9000
        self.send('publish',dict(grant,sequence=second['sequence']))
        original = self.view()['accepted']
        self.time[0] += 5000
        self.assertFalse(self.send('publish',dict(grant,sequence=first['sequence']))['outcome']['accepted'])
        self.assertFalse(self.send('publish',dict(grant,sequence=second['sequence']))['outcome']['accepted'])
        current = self.view()
        self.assertEqual(original,current['accepted'])
        self.assertEqual(14000,current['fixAgeMs'])
        self.assertEqual(5000,current['receiptAgeMs'])

    def test_acquisition_age_not_recent_receipt_decides_staleness_at_exact_boundary(self):
        grant = self.start()
        fix = self.send('capture',grant)['outcome']['fix']
        self.time[0] += 30000
        self.send('publish',dict(grant,sequence=fix['sequence']))
        self.assertEqual((30000,0,False),tuple(self.view()[k] for k in ('fixAgeMs','receiptAgeMs','stale')))
        self.time[0] += 1
        self.assertEqual((30001,1,True),tuple(self.view()[k] for k in ('fixAgeMs','receiptAgeMs','stale')))

    def test_ended_route_rejects_larger_inflight_sequence_and_new_epoch_starts_unknown(self):
        grant = self.start()
        self.send('next',grant)
        fix = self.send('capture',grant)['outcome']['fix']
        accepted = self.view()['accepted']
        self.send('end',grant)
        self.assertFalse(self.send('publish',dict(grant,sequence=fix['sequence']))['outcome']['accepted'])
        self.assertEqual('ENDED',self.view()['trip']['phase'])
        self.assertEqual(accepted,self.view()['accepted'])
        newer = self.start()
        self.assertEqual(grant['epoch']+1,newer['epoch'])
        self.assertNotEqual(grant['tripId'],newer['tripId'])
        self.assertIsNone(self.view()['accepted'])
        self.assertIsNone(self.view()['fixAgeMs'])
        self.assertFalse(self.send('publish',dict(grant,sequence=fix['sequence']))['outcome']['accepted'])
        self.assertFalse(self.send('end',grant)['outcome']['accepted'])
        self.assertEqual(newer['tripId'],self.view()['trip']['id'])
        self.assertEqual('ACTIVE',self.view()['trip']['phase'])

    def test_delayed_next_from_old_grant_cannot_generate_a_fix_for_new_route(self):
        old = self.start()
        identity = str(uuid.uuid4())
        gate = Gate(self.a,identity)
        url = self.serve(gate)
        with concurrent.futures.ThreadPoolExecutor(1) as pool:
            pending = pool.submit(self.http,url,'/commands',self.envelope('next',old,identity))
            try:
                self.assertTrue(gate.entered.wait(2))
                self.send('end',old,owner=self.b)
                new = self.start()
            finally:
                gate.release.set()
            result = pending.result(4)[1]
        self.assertFalse(result['outcome']['accepted'])
        self.assertEqual(new['tripId'],self.view()['trip']['id'])
        self.assertEqual(1,self.view()['nextSequence'])
        self.assertIsNone(self.view()['accepted'])
        self.assertFalse(self.send('capture',old)['outcome']['accepted'])

    def test_two_owners_start_one_route_and_rejected_start_stays_rejected(self):
        ids = [str(uuid.uuid4()),str(uuid.uuid4())]
        barrier = threading.Barrier(2)
        def start(item):
            identity,owner = item; barrier.wait()
            return identity,self.send('start',identity=identity,owner=owner)
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results = list(pool.map(start,zip(ids,(self.a,self.b))))
        self.assertEqual(1,sum(result['outcome']['accepted'] for _,result in results))
        loser = next(identity for identity,result in results if not result['outcome']['accepted'])
        state = self.view(); grant={'tripId':state['trip']['id'],'epoch':state['epoch']}
        self.send('end',grant)
        reopened = Service(self.database,clock=lambda:self.time[0])
        self.assertFalse(self.send('start',identity=loser,owner=reopened)['outcome']['accepted'])
        self.assertEqual('ENDED',self.view(reopened)['trip']['phase'])

    def test_lost_reply_restarts_and_replays_once_but_receipt_is_not_current_view(self):
        grant = self.start()
        url = self.serve(self.a)
        command = self.envelope('next',grant)
        self.http(url,'/__control',{'drop_reply':True})
        with self.assertRaises(OSError): self.http(url,'/commands',command)
        self.http(url,'/__control',{})
        reopened = Service(self.database,clock=lambda:self.time[0])
        receipt = reopened.route('POST','/commands',command)[1]
        self.assertEqual(2,self.view()['nextSequence'])
        self.assertEqual(1,self.view()['accepted']['sequence'])
        self.send('end',grant,owner=reopened)
        replay = reopened.route('POST','/commands',command)[1]
        self.assertEqual(receipt,replay)
        self.assertEqual('ACTIVE',replay['data']['trip']['phase'])
        self.assertEqual('ENDED',self.view(reopened)['trip']['phase'])

    def test_changed_command_intent_and_untrusted_fix_fields_do_not_mutate_state(self):
        grant = self.start()
        identity = str(uuid.uuid4())
        self.send('next',grant,identity)
        before = self.view()
        with self.assertRaises(Problem): self.send('end',grant,identity)
        with self.assertRaises(Problem): self.send('publish',dict(grant,sequence=1,lat=0))
        self.assertEqual(before,self.view())

    def test_malformed_epochs_and_sequences_are_not_coerced(self):
        grant = self.start()
        for action in ('capture','next','end'):
            for epoch in (True,1.0,'1',0,-1):
                with self.subTest(action=action,epoch=epoch):
                    with self.assertRaises(Problem): self.send(action,dict(grant,epoch=epoch))
        for sequence in (True,1.0,'1',0,101):
            with self.subTest(sequence=sequence):
                with self.assertRaises(Problem): self.send('publish',dict(grant,sequence=sequence))
        self.assertEqual('ACTIVE',self.view()['trip']['phase'])
        self.assertEqual(1,self.view()['nextSequence'])

    def test_fixture_bound_rejects_without_discarding_known_fixes(self):
        grant = self.start()
        for _ in range(100): self.send('capture',grant)
        self.assertFalse(self.send('capture',grant)['outcome']['accepted'])
        self.assertTrue(self.send('publish',dict(grant,sequence=1))['outcome']['accepted'])
        self.assertTrue(self.send('publish',dict(grant,sequence=100))['outcome']['accepted'])
        self.assertEqual(101,self.view()['nextSequence'])
        self.assertEqual(100,self.view()['accepted']['sequence'])

    def test_http_rejects_invalid_json_and_envelope_without_allocating_route(self):
        url = self.serve(self.a)
        for raw in (b'[]',b'{"id":NaN}',b'\xff',b'x'*65537):
            with self.subTest(length=len(raw)):
                with self.assertRaises(HTTPError) as error:
                    urlopen(Request(url+'/commands',data=raw,method='POST'),timeout=4)
                self.assertEqual(400,error.exception.code)
        self.assertIsNone(self.view()['trip'])
        with closing(sqlite3.connect(self.database)) as db:
            self.assertEqual(0,db.execute('SELECT count(*) FROM commands').fetchone()[0])


if __name__ == '__main__': unittest.main()
