package dev.plume

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A category as the phone's page sends it back: its id ("" for one made on the phone), its name and its apps in order. */
internal class ManagedRow(val id: String, val name: String, val apps: List<String>)

/**
 * The categories after the phone's edit. A row that names a category keeps what the phone doesn't edit (size, icon,
 * place) and takes the new name, apps and position; a row with a new id is a new category ([newId] numbers it).
 * Only [known] apps come in, each in the first category that lists it. The phone never sees the [fixed] entries
 * (HDMI inputs pinned to a row): they stay at the end of their category. A row left without a name keeps its old
 * one, or is dropped if it is new. Null when no category is left: the home screen always has one.
 */
internal fun managedCategories(
    old: List<Category>, rows: List<ManagedRow>, known: Set<String>, fixed: (String) -> Boolean, newId: (Int) -> String,
): List<Category>? {
    val placed = HashSet<String>()
    val used = HashSet<String>()
    val out = rows.mapIndexedNotNull { i, row ->
        val before = old.firstOrNull { it.id == row.id }?.takeIf { row.id.isNotEmpty() && used.add(row.id) }
        val name = row.name.trim().take(40).ifEmpty { before?.name ?: return@mapIndexedNotNull null }
        val apps = row.apps.filter { it in known && !fixed(it) && placed.add(it) } + (before?.apps?.filter(fixed) ?: emptyList())
        before?.copy(name = name, apps = apps) ?: Category(newId(i), name, apps)
    }
    return out.takeIf { it.isNotEmpty() }
}

/** What the organiser page says, in the TV's language. */
internal class ManageTexts(
    val title: String, val intro: String, val name: String, val add: String, val rest: String, val moveTo: String,
    val save: String, val saved: String, val failed: String, val loading: String, val unreachable: String, val empty: String,
    val notFound: String,
)

/**
 * The page for organising the home screen from a phone, where typing a category's name and sorting apps is easy.
 * The page loads the layout from /state, edits it on the phone, and posts it back whole. [state] gives the layout
 * as JSON ({"cats":[{"id","name","apps":[package…]}],"apps":{package:label}}) and [save] takes the edited one
 * ({"cats":[…]}), false if it could not be applied. Both run on the server thread.
 */
internal class ManageServer(
    code: String, private val texts: ManageTexts, private val state: () -> String, private val save: (String) -> Boolean,
) : PhoneServer(code, "home-organiser") {
    override val notFound get() = texts.notFound

    init {
        start()
    }

    override fun route(method: String, sub: String, headers: Map<String, String>, input: InputStream, out: OutputStream) {
        when {
            sub == "" && method == "GET" -> reply(out, 200, "text/html; charset=utf-8", page(texts))
            sub == "/state" && method == "GET" -> reply(out, 200, "application/json; charset=utf-8", state())
            sub == "/state" && method == "POST" -> {
                val length = headers["content-length"]?.toLongOrNull() ?: -1
                if (length !in 1..MAX_BYTES) return reply(out, 413, "text/plain; charset=utf-8", texts.failed)
                val body = ByteArrayOutputStream().also { readBody(input, length, it) }.toString("UTF-8")
                if (save(body)) reply(out, 200, "text/plain; charset=utf-8", texts.saved)
                else reply(out, 400, "text/plain; charset=utf-8", texts.failed)
            }
            else -> reply(out, 404, "text/plain; charset=utf-8", texts.notFound)
        }
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024 // a few hundred apps at most: far under this

        // No library and no build step: one page, plain DOM calls. Every text goes in through textContent or a
        // value, never as HTML, so an app's label can't inject markup.
        fun page(t: ManageTexts) = """
            <!doctype html>
            <html dir="auto"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${pageHtml(t.title)}</title>
            <style>
              body{font:16px system-ui,sans-serif;margin:0;padding:24px 16px 96px;background:#f7f8fa;color:#111214}
              @media (prefers-color-scheme:dark){body{background:#09090b;color:#f5f5f7}}
              h1{font-size:22px;margin:0 0 6px} p{opacity:.7;margin:0 0 20px}
              section{border:1px solid rgba(128,128,128,.35);border-radius:14px;padding:12px;margin-bottom:14px}
              .head,.app{display:flex;align-items:center;gap:6px} .app{padding:6px 0;border-top:1px solid rgba(128,128,128,.2)}
              .head input{flex:1;min-width:0;font:inherit;font-weight:600;padding:10px;border-radius:10px;border:1px solid rgba(128,128,128,.5);background:transparent;color:inherit}
              h2{font-size:16px;margin:4px 0 8px;opacity:.75} .app span{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
              button,select{font:inherit;border-radius:10px;border:1px solid rgba(128,128,128,.5);background:transparent;color:inherit;padding:8px 10px}
              select{max-width:38%} button:disabled{opacity:.25} .none{opacity:.5;padding:8px 0 2px}
              #add{display:block;width:100%;padding:14px;border-style:dashed}
              #bar{position:fixed;left:0;right:0;bottom:0;padding:12px 16px;background:inherit;border-top:1px solid rgba(128,128,128,.35)}
              #save{display:block;width:100%;padding:14px;border:0;background:#111214;color:#fff;font-weight:600}
              @media (prefers-color-scheme:dark){#save{background:#f5f5f7;color:#111214}}
              #s{min-height:1.4em;text-align:center;margin-bottom:8px;opacity:.8}
            </style></head><body>
            <h1>${pageHtml(t.title)}</h1>
            <p>${pageHtml(t.intro)}</p>
            <div id="root"></div>
            <button id="add">+ ${pageHtml(t.add)}</button>
            <div id="bar"><div id="s">${pageHtml(t.loading)}</div><button id="save" disabled>${pageHtml(t.save)}</button></div>
            <script>
              const T={name:'${pageJs(t.name)}',rest:'${pageJs(t.rest)}',moveTo:'${pageJs(t.moveTo)}',saved:'${pageJs(t.saved)}',
                failed:'${pageJs(t.failed)}',unreachable:'${pageJs(t.unreachable)}',empty:'${pageJs(t.empty)}'};
              const root=document.getElementById('root'),s=document.getElementById('s'),saveB=document.getElementById('save');
              let st=null;
              function el(tag,parent,cls){const e=document.createElement(tag);if(cls)e.className=cls;parent.appendChild(e);return e}
              function btn(parent,text,act,off){const b=el('button',parent);b.textContent=text;b.disabled=!!off;b.onclick=act;return b}
              function dirty(){saveB.disabled=false;s.textContent=''}
              function swap(a,i,d){const j=i+d;if(j<0||j>=a.length)return;const x=a[i];a[i]=a[j];a[j]=x;render();dirty()}
              function appRow(card,pkg,ci,ai){
                const r=el('div',card,'app');el('span',r).textContent=st.apps[pkg]||pkg;
                if(ci>=0){btn(r,'↑',()=>swap(st.cats[ci].apps,ai,-1),ai==0);btn(r,'↓',()=>swap(st.cats[ci].apps,ai,1),ai==st.cats[ci].apps.length-1)}
                const sel=el('select',r);const o=(v,text)=>{const x=el('option',sel);x.value=v;x.textContent=text};
                o('',T.moveTo);st.cats.forEach((c,i)=>{if(i!=ci)o(i,c.name||T.name)});if(ci>=0)o('-1',T.rest);
                sel.onchange=()=>{const v=sel.value;if(v==='')return;if(ci>=0)st.cats[ci].apps.splice(ai,1);
                  if(v!=='-1')st.cats[+v].apps.push(pkg);render();dirty()};
              }
              function render(){
                root.textContent='';
                st.cats.forEach((c,ci)=>{
                  const card=el('section',root),head=el('div',card,'head'),inp=el('input',head);
                  inp.value=c.name;inp.placeholder=T.name;inp.maxLength=40;inp.oninput=()=>{c.name=inp.value;dirty()};inp.onchange=render;
                  btn(head,'↑',()=>swap(st.cats,ci,-1),ci==0);btn(head,'↓',()=>swap(st.cats,ci,1),ci==st.cats.length-1);
                  btn(head,'✕',()=>{st.cats.splice(ci,1);render();dirty()},st.cats.length<2);
                  c.apps.forEach((p,ai)=>appRow(card,p,ci,ai));
                  if(!c.apps.length)el('div',card,'none').textContent=T.empty;
                });
                const used=new Set();st.cats.forEach(c=>c.apps.forEach(p=>used.add(p)));
                const rest=Object.keys(st.apps).filter(p=>!used.has(p)).sort((a,b)=>st.apps[a].localeCompare(st.apps[b]));
                if(rest.length){const card=el('section',root);el('h2',card).textContent=T.rest;rest.forEach(p=>appRow(card,p,-1,0))}
              }
              document.getElementById('add').onclick=()=>{st.cats.push({id:'',name:'',apps:[]});render();dirty();
                const all=root.querySelectorAll('.head input');all[all.length-1].focus()};
              saveB.onclick=async()=>{
                saveB.disabled=true;
                try{const r=await fetch(location.pathname+'/state',{method:'POST',body:JSON.stringify({cats:st.cats})});
                  s.textContent=await r.text();if(r.ok)await load();else saveB.disabled=false}
                catch(e){s.textContent=T.unreachable;saveB.disabled=false}
              };
              async function load(){const r=await fetch(location.pathname+'/state');st=await r.json();render()}
              load().then(()=>{s.textContent=''}).catch(()=>{s.textContent=T.unreachable});
            </script></body></html>
        """.trimIndent()
    }
}
