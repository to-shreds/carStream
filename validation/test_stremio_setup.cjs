const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const {parseHTML}=require('linkedom');
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/stremio/setup.html'),'utf8');
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
async function run(mode){
 const {window}=parseHTML(html),{document}=window,requests=[];
 Object.assign(window,{URL,AbortController,Promise,console,setTimeout,clearTimeout});
 window.location={href:'http://192.168.49.1:8877/stremio/abc/setup'};
 window.isSecureContext=false;let copies=0;
 document.execCommand=op=>{assert.equal(op,'copy');copies++;return mode!=='copyfail'};
 const area=document.getElementById('address');area.select=()=>{};area.setSelectionRange=()=>{};
 window.fetch=async url=>{
  requests.push(url.href);assert(url.href.startsWith('http://192.168.49.1:8877/stremio/abc/'));
  if(mode==='offline')throw Error('Disconnected');
  return {ok:true,json:async()=>url.pathname.endsWith('manifest.json')?{id:'com.carstream.local'}:{metas:mode==='empty'?[]:[{id:'a'}]}};
 };
 vm.runInContext(script,vm.createContext(window));await new Promise(r=>setTimeout(r,10));
 assert.equal(area.value,'http://192.168.49.1:8877/stremio/abc/manifest.json');
 assert.equal(requests.length,3);
 const status=document.getElementById('status').textContent;
 assert(status.includes(mode==='offline'?'Cannot reach':mode==='empty'?'Refresh Library':'2 ready download'));
 document.getElementById('copy').click();await new Promise(r=>setTimeout(r,0));
 assert.equal(copies,1);assert(document.getElementById('copy').textContent.includes(mode==='copyfail'?'Address selected':'Copied'));
}
(async()=>{for(const mode of ['normal','empty','offline','copyfail'])await run(mode);console.log('PASS: Stremio setup populated, empty, offline and manual-copy states; simulated DOM, no rendering');})().catch(e=>{console.error(e);process.exitCode=1});
