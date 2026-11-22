/* KEY-MONEY DATA SOLUTIONS - website + M-Pesa STK push server. No dependencies, Node 18+. */
const http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto');
const E=process.env,PORT=E.PORT||3000,SHOP_KEY=E.SHOP_KEY||'',CB_TOKEN=E.CALLBACK_TOKEN||SHOP_KEY;
const BASE=E.DARAJA_BASE||(E.DARAJA_ENV==='production'?'https://api.safaricom.co.ke':'https://sandbox.safaricom.co.ke');
const SHORTCODE=E.SHORTCODE||'174379',TILL=E.TILL||'3563482',TXN=E.TXN_TYPE||'CustomerBuyGoodsOnline';
const DBF=path.join(E.DATA_DIR||__dirname,'data.json');let db={offers:[],orders:{}};
try{db=JSON.parse(fs.readFileSync(DBF,'utf8'))}catch(e){}
const save=()=>{fs.mkdirSync(path.dirname(DBF),{recursive:true});fs.writeFileSync(DBF,JSON.stringify(db))};
const phone=p=>{const d=String(p||'').replace(/\D/g,'');if(/^0[17]\d{8}$/.test(d))return'254'+d.slice(1);if(/^254[17]\d{8}$/.test(d))return d;if(/^[17]\d{8}$/.test(d))return'254'+d;return null};
const local=p=>'0'+String(p).slice(3);
let tok={v:'',x:0};
async function token(){if(tok.x>Date.now())return tok.v;
  const r=await fetch(BASE+'/oauth/v1/generate?grant_type=client_credentials',{headers:{Authorization:'Basic '+Buffer.from(E.DARAJA_KEY+':'+E.DARAJA_SECRET).toString('base64')}});
  const j=await r.json();if(!j.access_token)throw new Error('Daraja login failed');tok={v:j.access_token,x:Date.now()+(+j.expires_in-60)*1000};return tok.v}
const stamp=()=>new Date().toISOString().replace(/\D/g,'').slice(0,14);
async function daraja(p,body){const ts=stamp();
  const r=await fetch(BASE+p,{method:'POST',headers:{Authorization:'Bearer '+await token(),'Content-Type':'application/json'},
    body:JSON.stringify({BusinessShortCode:SHORTCODE,Password:Buffer.from(SHORTCODE+E.PASSKEY+ts).toString('base64'),Timestamp:ts,...body})});
  return r.json()}
const CORS={'Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'Content-Type,x-shop-key','Access-Control-Allow-Methods':'GET,POST,PUT,OPTIONS'};
const send=(res,c,o)=>{res.writeHead(c,{'Content-Type':'application/json','Cache-Control':'no-store',...CORS});res.end(JSON.stringify(o))};
const body=req=>new Promise(r=>{let b='';req.on('data',d=>{b+=d;if(b.length>2e4)req.destroy()});req.on('end',()=>{try{r(JSON.parse(b||'{}'))}catch(e){r({})}})});
const openData=()=>{const all=Object.values(db.orders);
  return{pending:all.filter(o=>o.status==='pending'&&Date.now()-o.t<3e5).map(o=>({id:o.id,buyer:local(o.buyer),amount:o.amount})),
    paid:all.filter(o=>o.status==='paid'&&!o.acked).map(o=>({id:o.id,offerId:o.offerId,name:o.name,amount:o.amount,to:local(o.to),buyer:local(o.buyer),receipt:o.receipt||''}))}};
const waiters=[];
const flush=()=>{const d=openData();if(!d.paid.length)return;while(waiters.length){const w=waiters.pop();clearTimeout(w.t);send(w.res,200,d)}};
const isShop=req=>SHOP_KEY&&req.headers['x-shop-key']===SHOP_KEY;
const pub=o=>({id:o.id,status:o.status,reason:o.reason||'',name:o.name,amount:o.amount,to:o.to});
async function lazyCheck(o){ /* callback lost? ask Safaricom directly */
  if(o.status!=='pending'||Date.now()-o.t<25000||!o.checkout)return;
  try{const j=await daraja('/mpesa/stkpushquery/v1/query',{CheckoutRequestID:o.checkout});
    if(j.ResultCode==='0'){o.status='paid';save();flush()}else if(j.ResultCode&&j.ResultCode!=='0'&&!/processing/i.test(j.ResultDesc||'')){o.status='failed';o.reason=j.ResultDesc||'Payment not completed';save()}}catch(e){}}
const types={'.html':'text/html','.css':'text/css','.js':'text/javascript','.svg':'image/svg+xml','.png':'image/png'};
http.createServer(async(req,res)=>{try{
  const u=new URL(req.url,'http://x'),p=u.pathname;
  if(req.method==='OPTIONS'){res.writeHead(204,CORS);return res.end()}
  if(req.method==='GET'&&p==='/api/offers')return send(res,200,db.offers.map(o=>({id:o.id,name:o.name,amount:o.amount})));
  if(req.method==='PUT'&&p==='/api/offers'){if(!isShop(req))return send(res,401,{error:'Bad shop key'});
    const a=await body(req);if(!Array.isArray(a))return send(res,400,{error:'Bad data'});
    db.offers=a.slice(0,200).map(o=>({id:String(o.id).slice(0,40),name:String(o.name).slice(0,80),amount:Math.round(+o.amount)})).filter(o=>o.amount>0);save();return send(res,200,{ok:1})}
  if(req.method==='POST'&&p==='/api/pay'){
    const b=await body(req),o=db.offers.find(x=>x.id===b.offerId),buyer=phone(b.buyer),to=phone(b.to||b.buyer);
    if(!o)return send(res,400,{error:'That offer is no longer available'});
    if(!buyer||!to)return send(res,400,{error:'Enter a valid number like 0712345678'});
    const recent=Object.values(db.orders).filter(x=>x.buyer===buyer&&x.status==='pending'&&Date.now()-x.t<6e5).length;
    if(recent>=3)return send(res,429,{error:'Too many pending requests. Please wait a few minutes.'});
    const ord={id:crypto.randomBytes(6).toString('hex'),offerId:o.id,name:o.name,amount:o.amount,buyer,to,status:'pending',t:Date.now()};
    const j=await daraja('/mpesa/stkpush/v1/processrequest',{TransactionType:TXN,Amount:o.amount,PartyA:buyer,PartyB:TILL,PhoneNumber:buyer,
      CallBackURL:(E.PUBLIC_URL||'')+'/api/mpesa/callback?t='+encodeURIComponent(CB_TOKEN),AccountReference:'KEYMONEY',TransactionDesc:'Data bundle'});
    if(j.ResponseCode!=='0')return send(res,502,{error:j.errorMessage||j.ResponseDescription||'Could not send the M-Pesa prompt. Try again.'});
    ord.checkout=j.CheckoutRequestID;db.orders[ord.id]=ord;save();return send(res,200,{orderId:ord.id})}
  if(req.method==='POST'&&p==='/api/mpesa/callback'){
    if(u.searchParams.get('t')!==CB_TOKEN)return send(res,401,{});
    const c=((await body(req)).Body||{}).stkCallback||{},o=Object.values(db.orders).find(x=>x.checkout===c.CheckoutRequestID);
    if(o&&o.status==='pending'){if(c.ResultCode===0){const m=(c.CallbackMetadata&&c.CallbackMetadata.Item)||[];
      const r=m.find(i=>i.Name==='MpesaReceiptNumber');o.status='paid';o.receipt=r?r.Value:'';}else{o.status='failed';o.reason=c.ResultDesc||'Payment cancelled'}save();flush()}
    return send(res,200,{ResultCode:0,ResultDesc:'OK'})}
  let m=p.match(/^\/api\/order\/(\w+)$/);
  if(req.method==='GET'&&m){const o=db.orders[m[1]];if(!o)return send(res,404,{error:'Not found'});await lazyCheck(o);return send(res,200,pub(o))}
  if(req.method==='GET'&&p==='/api/orders/open'){if(!isShop(req))return send(res,401,{error:'Bad shop key'});
    const d=openData();if(u.searchParams.get('wait')!=='1'||d.paid.length)return send(res,200,d);
    const w={res,t:setTimeout(()=>{waiters.splice(waiters.indexOf(w),1);send(res,200,openData())},25000)};waiters.push(w);
    req.on('close',()=>{clearTimeout(w.t);const i=waiters.indexOf(w);if(i>=0)waiters.splice(i,1)});return}
  m=p.match(/^\/api\/orders\/(\w+)\/ack$/);
  if(req.method==='POST'&&m){if(!isShop(req))return send(res,401,{error:'Bad shop key'});const o=db.orders[m[1]];if(o){o.acked=1;save()}return send(res,200,{ok:1})}
  const f=path.join(__dirname,'public',p==='/'?'index.html':p);
  if(f.startsWith(path.join(__dirname,'public'))&&fs.existsSync(f)&&fs.statSync(f).isFile()){res.writeHead(200,{'Content-Type':types[path.extname(f)]||'application/octet-stream'});return fs.createReadStream(f).pipe(res)}
  send(res,404,{error:'Not found'})
}catch(e){console.error(e);send(res,500,{error:'Something went wrong. Please try again.'})}}).listen(PORT,()=>console.log('KEY-MONEY site on :'+PORT));
