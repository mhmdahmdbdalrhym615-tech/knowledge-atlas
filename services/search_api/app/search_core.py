"""Arabic query normalization, concept expansion and cautious result ranking."""
from __future__ import annotations
import re
from typing import Any
from urllib.parse import urlsplit, urlunsplit

_DIACRITICS=re.compile(r"[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED]")
_TRANSLATION=str.maketrans({"أ":"ا","إ":"ا","آ":"ا","ٱ":"ا","ى":"ي","ؤ":"و","ئ":"ي","ة":"ه"})
_EXPANSIONS=(
(("تربه","ارض","اراضي"),"soil science soil properties land assessment"),
(("ملوحه","ملحيه","املاح"),"soil salinity electrical conductivity salt affected soils"),
(("استصلاح","اراضي متدهوره","ارض بور"),"land reclamation soil remediation land restoration"),
(("خصوبه","انتاجيه","محصول"),"soil fertility crop productivity crop yield plant nutrition"),
(("صرف","غدق","مياه ارضيه"),"agricultural drainage waterlogging groundwater"),
(("ري","مياه الري","الري بالتنقيط"),"irrigation water management drip irrigation"),
(("بحث علمي","ورقه بحثيه","دراسه"),"scientific research scholarly article academic study"),
(("ذكاء اصطناعي","تعلم الي"),"artificial intelligence machine learning"),
(("برمجه","تطوير برامج","كود"),"software engineering programming developer documentation"),
(("مناخ","تغير مناخي"),"climate change climate data adaptation"),
)
_ROOTS={
"fao.org":("منظمة دولية","نطاق منظمة الأغذية والزراعة؛ لا يصدق وحده على محتوى الصفحة."),
"who.int":("منظمة دولية","نطاق منظمة الصحة العالمية؛ راجع تاريخ الدليل ومحتواه."),
"unesco.org":("منظمة دولية","نطاق اليونسكو؛ راجع الوثيقة الأصلية."),
"worldbank.org":("منظمة دولية","نطاق مجموعة البنك الدولي؛ راجع منهجية البيانات."),
"openalex.org":("فهرس بحثي","فهرس بيانات بحثية؛ ليس حكماً على جودة كل دراسة."),
"doaj.org":("دليل أكاديمي","دليل دوريات؛ افحص صفحة المجلة وسياسة التحكيم."),
"arxiv.org":("مستودع أبحاث","مستودع ما قبل النشر؛ الأوراق ليست كلها محكمة."),
"pubmed.ncbi.nlm.nih.gov":("قاعدة بيانات طبية","وجود الدراسة لا يجعلها إرشاداً علاجياً."),
"nih.gov":("جهة حكومية / بحثية","تحقق من المعهد والصفحة المحددين."),
"cdc.gov":("جهة حكومية","راجع تاريخ الإرشادات."),
"usda.gov":("جهة حكومية","تحقق من ملاءمة الإرشاد للبلد والمحصول."),
"nasa.gov":("جهة حكومية / علمية","راجع صفحة المهمة أو مجموعة البيانات."),
"noaa.gov":("جهة حكومية / علمية","راجع مجموعة البيانات وتاريخها."),
"usgs.gov":("جهة حكومية / علمية","راجع الجهة والبيانات الأصلية."),
"epa.gov":("جهة حكومية","راجع الصفحة والسياق التنظيمي."),
"energy.gov":("جهة حكومية","تحقق من الجهة والوثيقة."),
"crossref.org":("بنية بحثية","بيانات وصفية للمنشورات وليست تقييماً علمياً."),
"europepmc.org":("قاعدة بيانات بحثية","راجع الناشر الأصلي."),
"core.ac.uk":("محرك بحث أكاديمي","يجمع سجلات من مستودعات مختلفة."),
"openstax.org":("تعليم مفتوح","تحقق من الطبعة وتاريخ التحديث."),
"mit.edu":("جامعة","تحقق من الجهة والصفحة."),
"stanford.edu":("جامعة","تحقق من الجهة والصفحة."),
"python.org":("مشروع تقني رسمي","الموقع الرسمي للغة Python."),
"developer.android.com":("توثيق تقني رسمي","توثيق Android الرسمي."),
"github.com":("منصة تطوير","افحص مالك المشروع ونشاطه ورخصته."),
"ffmpeg.org":("مشروع مفتوح المصدر","الموقع الرسمي لمشروع FFmpeg."),
"kernel.org":("مشروع مفتوح المصدر","الموقع الرسمي لنواة Linux."),
}
def normalize_query(value:str)->str:
    return _DIACRITICS.sub("",value.replace("\u0640","")).translate(_TRANSLATION).lower().strip()
def expand_query(value:str)->str:
    normalized=normalize_query(value); hints=[]
    for keys, expansion in _EXPANSIONS:
        if any(normalize_query(key) in normalized for key in keys): hints.append(expansion)
    hints=list(dict.fromkeys(hints))
    return value.strip() if not hints else value.strip()+" "+" ".join(hints)
def classify_publisher(hostname:str)->tuple[str,str]:
    host=hostname.lower().strip(".")
    for root, result in _ROOTS.items():
        if host==root or host.endswith("."+root): return result
    if host.endswith(".gov") or ".gov." in host: return ("جهة حكومية","تصنيف نطاق فقط؛ تحقق من الجهة والصفحة.")
    if host.endswith(".edu") or ".edu." in host or host.endswith(".ac.uk"): return ("مؤسسة تعليمية","تحقق من الباحث والوثيقة.")
    return ("غير مصنف","لم يطابق النطاق قائمة التصنيف؛ يلزم تقييم الجهة والمحتوى.")
def canonical_https_url(value:str)->str|None:
    try:
        p=urlsplit(value.strip())
        if p.scheme.lower()!="https" or not p.hostname or p.username or p.password: return None
        return urlunsplit(("https",p.netloc.lower(),p.path or "/",p.query,""))
    except ValueError: return None
def rank_and_shape_results(raw_results:list[dict[str,Any]],query:str,limit:int)->list[dict[str,Any]]:
    tokens={t for t in re.findall(r"[\w\u0600-\u06FF]+",normalize_query(query)) if len(t)>1}
    candidates=[]; seen=set()
    for raw in raw_results:
        url=canonical_https_url(str(raw.get("url","")))
        if not url or url in seen: continue
        seen.add(url); host=urlsplit(url).hostname or ""
        publisher_type,trust_note=classify_publisher(host)
        title=str(raw.get("title","")).strip()[:240] or host
        snippet=str(raw.get("content",raw.get("snippet",""))).strip()[:1200]
        result_tokens=set(re.findall(r"[\w\u0600-\u06FF]+",normalize_query(title+" "+snippet)))
        lexical=len(tokens & result_tokens)/max(1,len(tokens))
        try: provider=max(0.0,float(raw.get("score",0.0)))
        except (TypeError,ValueError): provider=0.0
        candidates.append({"title":title,"url":url,"snippet":snippet,"publisher":host,
          "publisherType":publisher_type,"trustNote":trust_note,
          "_provider":provider,"_lexical":lexical,"_boost":0.08 if publisher_type!="غير مصنف" else 0.0})
    maximum=max((x["_provider"] for x in candidates),default=0.0)
    for item in candidates:
        normalized=item["_provider"]/maximum if maximum else 0.0
        item["relevance"]=round(min(1.0,0.65*normalized+0.35*item["_lexical"]+item["_boost"]),4)
        for key in ("_provider","_lexical","_boost"): del item[key]
    candidates.sort(key=lambda x:x["relevance"],reverse=True)
    return candidates[:max(1,min(limit,50))]
