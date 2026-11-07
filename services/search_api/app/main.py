"""SearXNG metasearch adapter for Knowledge Atlas."""
from __future__ import annotations
import os
from datetime import datetime,timezone
from typing import Literal
import httpx
from fastapi import FastAPI,HTTPException
from pydantic import BaseModel,Field
from .search_core import expand_query,rank_and_shape_results

app=FastAPI(title="Knowledge Atlas Search API",version="0.1.0",
 description="Query expansion and metasearch. Domain labels are not factual verification.")
SEARXNG_BASE_URL=os.getenv("SEARXNG_BASE_URL","").rstrip("/")
SEARCH_TIMEOUT_SECONDS=float(os.getenv("SEARCH_TIMEOUT_SECONDS","18"))
class SearchRequest(BaseModel):
 query:str=Field(min_length=2,max_length=500)
 language:Literal["ar","en","auto"]="auto"
 mode:Literal["hybrid","web"]="hybrid"
 limit:int=Field(default=20,ge=1,le=50)
@app.get("/health")
async def health():
 return {"status":"ok","search_provider":"configured" if SEARXNG_BASE_URL else "not_configured"}
@app.post("/search")
async def search(request:SearchRequest):
 if not SEARXNG_BASE_URL: raise HTTPException(status_code=503,detail="Search provider is not configured.")
 expanded=expand_query(request.query)
 params={"q":expanded,"format":"json","language":request.language,"safesearch":1}
 try:
  async with httpx.AsyncClient(timeout=SEARCH_TIMEOUT_SECONDS,follow_redirects=False,
      headers={"User-Agent":"KnowledgeAtlas-Search/0.1"}) as client:
   response=await client.get(SEARXNG_BASE_URL+"/search",params=params)
   response.raise_for_status(); payload=response.json()
 except httpx.TimeoutException as exc: raise HTTPException(status_code=504,detail="Search provider timed out.") from exc
 except (httpx.HTTPError,ValueError) as exc: raise HTTPException(status_code=502,detail="Search provider returned an invalid response.") from exc
 raw=payload.get("results",[]) if isinstance(payload,dict) else []
 if not isinstance(raw,list): raw=[]
 results=rank_and_shape_results(raw,request.query,request.limit)
 retrieved=datetime.now(timezone.utc).isoformat()
 for item in results: item["retrievedAt"]=retrieved
 return {"query":request.query,"expandedQuery":expanded,"mode":"lexical-meta-search-with-query-expansion",
  "results":results,"notice":"تصنيف الناشر مبني على النطاق فقط، ولا يثبت صحة محتوى الصفحة أو تحكيمها العلمي."}
