const UPSTREAM = 'https://api.deepseek.com/chat/completions';
const MODELS = new Set(['deepseek-flash', 'deepseek-v4-pro']);
const MAX_BODY = 4 * 1024 * 1024;
const HEADERS = {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'};
const fail = (status, message) => new Response(JSON.stringify({error:{message}}), {status,headers:HEADERS});

async function readBody(request) {
  const reader = request.body?.getReader();
  if (!reader) throw new Error('body');
  const chunks=[]; let bytes=0;
  try {
    for (;;) {
      const {done,value}=await reader.read();
      if (done) break;
      bytes+=value.byteLength;
      if (bytes>MAX_BODY) {await reader.cancel();throw new Error('size');}
      chunks.push(value);
    }
  } finally {reader.releaseLock();}
  const data=new Uint8Array(bytes);let offset=0;
  for (const chunk of chunks) {data.set(chunk,offset);offset+=chunk.byteLength;}
  return JSON.parse(new TextDecoder().decode(data));
}

function cleanPacket(body) {
  if (!body || !MODELS.has(body.model) || body.stream===true || !Array.isArray(body.messages) ||
      body.messages.length<1 || body.messages.length>128) throw new Error('request');
  let textLength=0;
  const messages=body.messages.map(message=>{
    if (!message || !['system','user','assistant'].includes(message.role)) throw new Error('role');
    let content;
    if (typeof message.content==='string') {content=message.content;textLength+=content.length;}
    else if (Array.isArray(message.content) && message.content.length<=8) content=message.content.map(part=>{
      if (part.type==='text' && typeof part.text==='string') {textLength+=part.text.length;return {type:'text',text:part.text};}
      if (part.type==='image_url' && typeof part.image_url?.url==='string' &&
          /^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/.test(part.image_url.url))
        return {type:'image_url',image_url:{url:part.image_url.url}};
      throw new Error('content');
    });
    else throw new Error('content');
    return {role:message.role,content};
  });
  if (textLength>120000) throw new Error('text');
  const requested=body.max_tokens ?? 4096;
  if (!Number.isInteger(requested) || requested<1) throw new Error('tokens');
  const packet={model:body.model,messages,max_tokens:Math.min(requested,4096),stream:false,thinking:{type:'disabled'}};
  if (body.response_format?.type==='json_object') packet.response_format={type:'json_object'};
  if (typeof body.temperature==='number' && body.temperature>=0 && body.temperature<=2) packet.temperature=body.temperature;
  return packet;
}

/** Secret stays in runtime binding; no request/response logging or persistent chat storage. */
export function createGateway(fetchUpstream=fetch, clock=Date.now) {
  const windows=new Map();
  return async (request,env)=>{
    const path=new URL(request.url).pathname;
    if (request.method==='GET' && (path==='/' || path==='/health'))
      return new Response(JSON.stringify({service:'jiligulu-default-ds',ready:!!env.DEEPSEEK_API_KEY}),{headers:HEADERS});
    if (path!=='/v1/chat/completions' || request.method!=='POST') return fail(404,'Not found');
    if (!env.DEEPSEEK_API_KEY?.trim()) return fail(503,'默认 DS 服务暂不可用，请稍后重试。');
    if (request.headers.has('Authorization')) return fail(400,'自行填写密钥时请使用供应商官方接口。');
    if (!request.headers.get('content-type')?.toLowerCase().startsWith('application/json')) return fail(415,'JSON required');
    const now=clock();
    const ip=request.headers.get('cf-connecting-ip') || 'unidentified';
    const bucket=windows.get(ip);
    if (bucket && bucket.until>now && bucket.count>=30) return fail(429,'请求过于频繁，请稍后重试。');
    if (windows.size>=1024) for (const [key,value] of windows) if (value.until<=now) windows.delete(key);
    if (!windows.has(ip) && windows.size>=1024) return fail(429,'默认服务繁忙，请稍后重试。');
    windows.set(ip,{until:bucket?.until>now?bucket.until:now+60000,count:bucket?.until>now?bucket.count+1:1});
    let packet;
    try {packet=cleanPacket(await readBody(request));}
    catch (error) {return fail(error.message==='size'?413:400,'请求格式或大小不符合要求。');}
    const secret=env.DEEPSEEK_API_KEY.trim();
    const abort=new AbortController();
    const timeout=setTimeout(()=>abort.abort(),45000);
    try {
      const response=await fetchUpstream(UPSTREAM,{method:'POST',headers:{Authorization:'Bearer '+secret,
        'Content-Type':'application/json'},body:JSON.stringify(packet),signal:abort.signal});
      if (!response.ok) {await response.body?.cancel();return fail(response.status===429?429:503,'默认 DS 服务暂不可用，请稍后重试。');}
      const raw=await response.text();
      if (raw.includes(secret)) return fail(502,'默认服务返回异常。');
      const data=JSON.parse(raw);
      if (!Array.isArray(data.choices)) return fail(502,'默认服务返回异常。');
      return new Response(JSON.stringify({choices:data.choices,id:data.id,model:data.model}),{headers:HEADERS});
    } catch {return fail(503,'默认 DS 服务暂不可用，请稍后重试。');}
    finally {clearTimeout(timeout);}
  };
}

export default {fetch:createGateway()};
