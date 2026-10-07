// Deploy with server-side TURN_SECRET and TURN_HOST environment bindings.
// Put a per-IP Cloudflare rate-limit rule (20 requests/minute) in front of /ice.
export default {
  async fetch(request, env) {
    const headers = { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
      'Access-Control-Allow-Origin': 'https://appassets.androidplatform.net',
      'Vary': 'Origin' };
    if (request.method === 'OPTIONS') return new Response(null, {status:204,headers});
    if (request.method !== 'GET' || new URL(request.url).pathname !== '/ice') return new Response('Not found',{status:404});
    if (!env.TURN_SECRET || !/^[a-z0-9.-]+$/i.test(env.TURN_HOST || '')) return new Response('Relay unavailable',{status:503,headers});
    const username = `${Math.floor(Date.now()/1000)+86400}:${crypto.randomUUID()}`;
    const key = await crypto.subtle.importKey('raw',new TextEncoder().encode(env.TURN_SECRET),{name:'HMAC',hash:'SHA-1'},false,['sign']);
    const digest = new Uint8Array(await crypto.subtle.sign('HMAC',key,new TextEncoder().encode(username)));
    const credential = btoa(String.fromCharCode(...digest));
    return new Response(JSON.stringify([
      {urls:['stun:stun.cloudflare.com:3478','stun:stun.l.google.com:19302']},
      {urls:[`turn:${env.TURN_HOST}:3478?transport=udp`,`turn:${env.TURN_HOST}:3478?transport=tcp`,`turns:${env.TURN_HOST}:443?transport=tcp`],username,credential}
    ]),{headers});
  }
};
