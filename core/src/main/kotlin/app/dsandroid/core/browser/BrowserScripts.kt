package app.dsandroid.core.browser

import kotlinx.serialization.json.JsonPrimitive

/** Only these fixed programs reach evaluateJavascript. All supplied values are JSON data. */
object BrowserScripts {
    private const val ELEMENTS = "Array.from(document.querySelectorAll('a,input,textarea,button,select,form')).slice(0,500)"
    val read = """(() => { const es=$ELEMENTS; return JSON.stringify({text:(document.body.innerText||'').slice(0,24000),targets:es.map((e,i)=>({target:i,tag:e.tagName,label:(e.innerText||e.getAttribute('aria-label')||e.name||'').slice(0,120),href:e.getAttribute('href')||'',type:e.type||''}))}); })()"""
    fun inspect(target: Int, text: String = ""): String {
        require(target in 0..499 && text.length <= 8000)
        return """(() => {
          const es=$ELEMENTS, e=es[$target], value=${JsonPrimitive(text)};
          if(!e) throw Error('Missing target');
          const f=e.tagName==='FORM'?e:e.form;
          const sensitive=x=>/password|passwd|token|secret|otp|credit|card|cvv|login|username|email/i.test((x.type||'')+' '+(x.name||'')+' '+(x.id||'')+' '+(x.autocomplete||''));
          if(sensitive(e)||(f&&Array.from(f.elements).some(sensitive))) throw Error('Login or sensitive form blocked');
          if(e.disabled||e.readOnly||e.type==='file'||e.type==='hidden') throw Error('Unsupported field');
          const fields=f?Array.from(f.elements).filter(x=>x.name&&!x.disabled&&(!['checkbox','radio'].includes(x.type)||x.checked)&&!['button','submit','reset'].includes(x.type)).map(x=>[x.name,x.value||'']):[];
          if(fields.length>100) throw Error('Too many fields');
          return JSON.stringify({target:$target,tag:e.tagName,kind:e.type||'',label:(e.innerText||e.name||'').slice(0,120),href:e.getAttribute('href')||'',action:f?(f.getAttribute('action')||''):'',method:f?(f.getAttribute('method')||'get').toLowerCase():'',fields:fields,value:value});
        })()"""
    }
    fun type(target: Int, text: String): String {
        require(target in 0..499 && text.length <= 8000)
        return """(() => { const e=($ELEMENTS)[$target]; if(!e||!['INPUT','TEXTAREA'].includes(e.tagName)||!['text','search','url','textarea','number','tel'].includes(e.type)) throw Error('Unsupported field'); e.value=${JsonPrimitive(text)}; return 'ok'; })()"""
    }
    fun scroll(down: Boolean) = "window.scrollBy(0,${if (down) 600 else -600});'ok'"
}
