"""Regression tests for the mobile offline preview's JavaScript planner.

Node.js executes the actual inline script from preview.html in a mocked DOM.
No network, credentials, or account writes.
"""
import pathlib
import subprocess
import unittest

HTML=pathlib.Path(__file__).with_name("preview.html")

class BrowserPreviewTests(unittest.TestCase):
    def test_browser_planner_and_syntax(self):
        html=HTML.read_text(encoding="utf-8")
        self.assertIn("<script>",html)
        self.assertIn("</script>",html)
        script=html.split("<script>",1)[1].split("</script>",1)[0]
        runner=r'''
const vm=require("node:vm"), assert=require("node:assert/strict");
const elements=new Map();
const document={createElement(tag){return {tag,textContent:"",children:[],append(...nodes){this.children.push(...nodes)}}},getElementById(id){
 if(!elements.has(id)) elements.set(id,{disabled:true,files:[],textContent:"",children:[],appendChild(node){this.children.push(node)},
   addEventListener(){}});
 return elements.get(id);
}};
const context=vm.createContext({document,console,Blob,URL,setTimeout});
vm.runInContext(SCRIPT,context);
vm.runInContext("globalThis.testPlan=plan;globalThis.testShowRows=showRows;",context);
const plan=context.testPlan;
const row=(id,order,extra={})=>({addon_id:"a",type:"movie",catalog_id:id,
  order,enabled:true,...extra});
const src={items:[row("b",0),row("a",1)]};
const dst={items:[row("a",0),row("b",1)]};
const ok=plan(src,dst);
assert.equal(ok.safe,true);
assert.equal(ok.matched,2);
assert.equal(ok.payload.items[0].catalog_id,"b");
assert.equal(plan({items:[]},dst).safe,false);
assert.equal(plan(src,{items:[]}).safe,false);
assert.equal(plan(src,{items:[row("a",0),row("a",1),row("b",2)]}).safe,false);
assert.equal(plan(src,{items:[row("a",0),row("b",1),{}]}).safe,false);
assert.equal(plan({items:[row("missing",0)]},dst).safe,false);
const collection=(id,title)=>({is_collection:true,collection_id:id,
  custom_title:title,order:0,enabled:true});
const fallback=plan({items:[collection("source-id","Movies")]},
  {items:[collection("destination-id","Movies")]});
assert.equal(fallback.safe,false);
assert.equal(fallback.unverified.length,1);
assert.equal(plan({items:[collection("same","Movies")]},
  {items:[collection("same","Movies")]}).safe,true);
context.testShowRows(ok.payload.items,dst.items);
const shown=elements.get("rows").children;
assert.equal(shown.length,3);
assert.equal(shown[1].children[0].children[0].textContent,"1. b");
assert.equal(shown[2].children[0].children[0].textContent,"2. a");
assert.equal(shown[1].children[1].textContent,"Visible • moved");
context.testShowRows([{...row("hidden",0),enabled:false}],[]);
assert.equal(elements.get("rows").children.at(-1).children[1].textContent,"Hidden");
console.log("Mobile preview JS: planner and visible/hidden row rendering passed");
'''
        runner=runner.replace("SCRIPT",repr(script),1)
        result=subprocess.run(["node","-e",runner],capture_output=True,text=True,timeout=15)
        self.assertEqual(result.returncode,0,result.stderr)

if __name__=="__main__":
    unittest.main()
