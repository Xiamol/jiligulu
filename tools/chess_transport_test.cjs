const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const html = fs.readFileSync('app/src/main/assets/chess-online/transport.html', 'utf8');
const source = html.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
let queue = [], timers = new Map(), clock = 0, nextTimer = 0, nextPeer = 0, peers = new Map(), drop = () => false;
const sent = [];
function drain() { let count = 0; while (queue.length) { assert.ok(++count < 2000); queue.shift()(); } }
function advance(ms) { clock += ms; for (const [id,t] of [...timers]) if (t.at <= clock) { timers.delete(id); t.fn(); } drain(); }
class Events { constructor() { this.handlers = {}; } on(k,f) { (this.handlers[k] ??= []).push(f); } emit(k,v) { for (const f of this.handlers[k] ?? []) f(v); } removeAllListeners() { this.handlers = {}; } }
class Connection extends Events {
  constructor(id, metadata) { super(); this.peer = id; this.metadata = metadata; this.open = false; this.closed = false; }
  send(packet) { assert.ok(this.open); sent.push(packet); if (!drop(packet)) queue.push(() => { if (!this.other.closed) this.other.emit('data', JSON.parse(JSON.stringify(packet))); }); }
  close() { if(this.closed) return; this.closed = true; this.open = false; this.emit('close'); if(this.other) { this.other.closed = true; this.other.open = false; this.other.emit('close'); } }
}
class Peer extends Events {
  constructor(id,options) { super(); this.id = id || `guest${++nextPeer}`; this.options = options; this.destroyed = false; this.disconnected = false; assert.ok(!peers.has(this.id)); peers.set(this.id,this); queue.push(() => this.emit('open',this.id)); }
  connect(id,options) { const other = peers.get(id); const local = new Connection(id,options.metadata); if (!other) { queue.push(() => this.emit('error',{type:'peer-unavailable'})); return local; }
    const remote = new Connection(this.id,options.metadata); local.other = remote; remote.other = local;
    queue.push(() => { other.emit('connection',remote); if(!remote.closed && !local.closed) { local.open = remote.open = true; local.emit('open'); remote.emit('open'); } }); return local; }
  reconnect() { this.disconnected = false; queue.push(() => this.emit('open',this.id)); }
  destroy() { this.destroyed = true; peers.delete(this.id); }
}
function client() { const events = []; const context = vm.createContext({Peer,crypto:crypto.webcrypto,Uint8Array,Map,Number,Array,String,console,
    setTimeout(fn,ms) { const id = ++nextTimer; timers.set(id,{fn,at:clock+ms}); return id; }, clearTimeout(id) { timers.delete(id); },
    window:{GuluTransport:{event(type,value){events.push({type,value});}}}});
  vm.runInContext(source,context); return {context,events,run(code){return vm.runInContext(code,context);}}; }
const host = client(), guest = client();
host.run(`guluStart('ALU2026',true,{iceServers:[]})`); guest.run(`guluStart('ALU2026',false,{iceServers:[]})`); drain();
assert.equal(host.events.filter(e=>e.type==='connected').length,1); assert.equal(guest.events.filter(e=>e.type==='connected').length,1);
guest.run(`guluSend('GO1|MOVE|0|112')`); drain();
assert.equal(host.events.filter(e=>e.value==='GO1|MOVE|0|112').length,1); assert.equal(guest.run('pending.size'),0);
// Simulate a move arriving before its ACK is lost; replay must acknowledge without double-applying it.
drop = p => Object.hasOwn(p,'ack'); guest.run(`guluSend('GO1|MOVE|1|113')`); drain();
assert.equal(guest.run('pending.size'),1); guest.run('connection.close()'); drain();
assert.equal(guest.events.at(-1).type,'recovering'); drop = () => false; advance(1500);
assert.equal(host.events.filter(e=>e.value==='GO1|MOVE|1|113').length,1); assert.equal(guest.run('pending.size'),0);
assert.equal(guest.events.filter(e=>e.type==='connected').length,1); assert.equal(guest.events.at(-1).type,'recovered');
// A different visitor cannot steal an already established room, even if it knows its visible code.
const stranger=client(); stranger.run(`guluStart('ALU2026',false,{iceServers:[]})`); drain();
assert.equal(stranger.events.filter(e=>e.type==='connected').length,0); assert.equal(host.run('remoteIdentity'),'guest1');
// Gaps are held until the missing message arrives; repeated packets are never emitted twice.
const before=host.events.length;
host.run(`receive({v:3,n:4,line:'GO1|PING'});receive({v:3,n:3,line:'GO1|PONG'});receive({v:3,n:3,line:'GO1|PONG'})`); drain();
assert.deepEqual(host.events.slice(before).filter(e=>e.type==='data').map(e=>e.value),['GO1|PONG','GO1|PING']);
// Missing relay readiness produces a bounded useful failure rather than an endless "finding room" screen.
stranger.run('connection=null; const waiting={metadata:{protocol,token},peer:"stranger",open:false,on(){},close(){}}; bind(waiting)');
advance(35000); assert.ok(stranger.events.some(e=>e.value==='relay-unavailable'));
host.run('guluStop()'); guest.run('guluStop()'); stranger.run('guluStop()');
assert.equal(peers.size,0); assert.equal(guest.run('pending.size'),0);
console.log('Transport tests passed: exactly-once replay, same-peer reconnect, private session identity, ordered gaps, bounded readiness, close cleanup.');
