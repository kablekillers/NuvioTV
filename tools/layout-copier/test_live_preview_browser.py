"""Execute the experimental phone page against a mocked DOM and Nuvio API.

No real credentials, network, or account writes are involved.
"""
import pathlib
import subprocess
import unittest
import json

HTML=pathlib.Path(__file__).with_name("live_preview_experimental.html")

class LivePreviewBrowserTests(unittest.TestCase):
    def test_two_account_signin_and_readonly_comparison(self):
        html=HTML.read_text(encoding="utf-8")
        script=html.split("<script>",1)[1].split("</script>",1)[0]
        runner=r'''
const vm=require("node:vm"),assert=require("node:assert/strict");
const elements=new Map(),calls=[];
function element(){return {value:"",disabled:false,textContent:"",children:[],
 addEventListener(){},replaceChildren(){this.children=[];this.value=""},
 append(child){this.children.push(child);if(!this.value)this.value=child.value},
 appendChild(child){this.children.push(child)}}}
const document={getElementById(id){if(!elements.has(id))elements.set(id,element());return elements.get(id)},
 createElement(){return element()}};
const get=id=>document.getElementById(id);
get("key").value="test-publishable-key";
get("platform").value="tv";
get("srcEmail").value="source@example.com";get("dstEmail").value="dest@example.com";
get("srcPassword").value="fake-password";get("dstPassword").value="fake-password";
const rows={src:[{addon_id:"a",type:"movie",catalog_id:"x",order:0,enabled:true}],
 dst:[{addon_id:"a",type:"movie",catalog_id:"x",order:0,enabled:false},
 {addon_id:"a",type:"movie",catalog_id:"destination-only",order:1,enabled:true}]};
const fetch=async(url,options)=>{
 const body=JSON.parse(options.body),token=options.headers.Authorization||"";
 calls.push({url,body,token});
 assert.equal(options.method,"POST");
 assert.equal(options.credentials,"omit");
 assert.equal(options.cache,"no-store");
 if(url.includes("grant_type=password")){
  const src=body.email.startsWith("source");
  return {ok:true,json:async()=>({access_token:src?"source-token":"dest-token",user:{id:src?"source-id":"dest-id"}})};
 }
 if(url.includes("sync_pull_profiles"))return {ok:true,json:async()=>[{profile_id:1,name:"Main"}]};
 if(url.includes("sync_pull_home_catalog_settings")){
  return {ok:true,json:async()=>({settings_json:{items:token.includes("source-token")?rows.src:rows.dst}})};
 }
 throw Error("Unexpected API endpoint "+url);
};
const ctx=vm.createContext({document,fetch,console,URL,JSON});
vm.runInContext(SCRIPT,ctx);
vm.runInContext("globalThis.loginTest=signIn;globalThis.compareTest=compare;",ctx);
(async()=>{
 await Promise.all([ctx.loginTest("src"),ctx.loginTest("dst")]);
 assert.equal(get("compare").disabled,false,"Both logins must work concurrently");
 assert.equal(get("srcPassword").value,"");
 assert.equal(get("dstPassword").value,"");
 await ctx.compareTest();
 assert.match(get("report").textContent,/Matched: 1/);
 assert.equal(get("rows").children.length,2);
 assert.match(get("report").textContent,/Destination-only: 1/);
 assert.match(get("rows").children[1].textContent,/DESTINATION ONLY/);
 assert.match(get("rows").children[0].textContent,/visibility change/);
 assert.equal(calls.filter(c=>c.url.includes("sync_push")).length,0);
 rows.src.push({addon_id:"a",type:"movie",catalog_id:"source-only",order:1,enabled:true});
 await ctx.compareTest();
 assert.match(get("report").textContent,/Missing or differently identified: 1/);
 assert.equal(get("rows").children.length,3);
 assert.match(get("rows").children[1].textContent,/UNMATCHED SOURCE/);
 assert.match(get("rows").children[2].textContent,/DESTINATION ONLY/);
 assert.equal(calls.filter(c=>c.url.includes("sync_push")).length,0);
 console.log("Read-only phone sign-in and preview passed");
})().catch(e=>{console.error(e);process.exitCode=1});
'''
        runner=runner.replace("SCRIPT",json.dumps(script),1)
        result=subprocess.run(["node","-e",runner],capture_output=True,text=True,timeout=15)
        self.assertEqual(result.returncode,0,result.stderr)

if __name__=="__main__":
    unittest.main()
