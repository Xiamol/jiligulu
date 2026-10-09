import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createGateway} from './worker.mjs';
const secret='synthetic-server-only-credential';
const env={DEEPSEEK_API_KEY:secret};
const payload={model:'deepseek-flash',messages:[{role:'user',content:'hi'}]};
const request=(value=payload,extra={})=>new Request('https://gateway.invalid/v1/chat/completions',
 {method:'POST',headers:{'content-type':'application/json',...extra},body:JSON.stringify(value)});
test('secret only reaches fixed official upstream and never client response',async()=>{
 let captured;
 const run=createGateway(async(url,init)=>{captured={url,init};return Response.json({choices:[{message:{content:'ok'}}],usage:{prompt_tokens:9}});});
 const result=await run(request({...payload,upstream:'https://attacker.invalid',max_tokens:99999}),env);
 assert.equal(result.status,200);assert.equal(captured.url,'https://api.deepseek.com/chat/completions');
 assert.equal(captured.init.headers.Authorization,'Bearer '+secret);
 const sent=JSON.parse(captured.init.body);assert.equal(sent.max_tokens,4096);assert.equal(sent.upstream,undefined);
 const body=await result.text();assert.ok(!body.includes(secret));assert.ok(!body.includes('usage'));
});
test('invalid models, remote images and excessive text never call upstream',async()=>{
 let calls=0;const run=createGateway(async()=>{calls++;throw Error();});
 for(const body of [{...payload,model:'other'}, {...payload,messages:[{role:'user',content:[{type:'image_url',image_url:{url:'http://127.0.0.1'}}]}]},
 {...payload,messages:[{role:'user',content:'a'.repeat(120001)}]}, {...payload,stream:true}])
 assert.equal((await run(request(body),env)).status,400);
 assert.equal(calls,0);
});
test('missing binding and client keys cannot expose credentials',async()=>{
 const run=createGateway(()=>{throw Error('must not fetch');});
 assert.equal((await run(request(),{})).status,503);
 assert.equal((await run(request(payload,{Authorization:'Bearer client-secret'}),env)).status,400);
 const health=await run(new Request('https://gateway.invalid/health'),env);
 assert.ok(!(await health.text()).includes(secret));
});
test('upstream failures and successful secret echoes are suppressed',async()=>{
 for(const status of [200,401,402,500]){
 const run=createGateway(async()=>new Response(JSON.stringify({choices:[{message:{content:secret}}],error:secret}),{status}));
 const result=await run(request(),env);assert.ok(result.status>=500);assert.ok(!(await result.text()).includes(secret));
 }
});
test('limiter bounds requests and resets after expiry',async()=>{
 let now=0,calls=0;const run=createGateway(async()=>{calls++;return Response.json({choices:[]});},()=>now);
 for(let i=0;i<30;i++) assert.equal((await run(request(),env)).status,200);
 assert.equal((await run(request(),env)).status,429);assert.equal(calls,30);
 now=60001;assert.equal((await run(request(),env)).status,200);
});
test('oversized payload never forwards',async()=>{
 const run=createGateway(()=>{throw Error('must not fetch');});
 assert.equal((await run(request({...payload,junk:'x'.repeat(4*1024*1024)}),env)).status,413);
});
