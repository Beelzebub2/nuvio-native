"use strict";
var http = require("http"), https = require("https"), crypto = require("crypto"), url = require("url");
var network = require("./proxy-network.cjs");
var portable = /^(accept|accept-language|range|if-range|user-agent)$/i;
var forbidden = /^(host|connection|content-length|transfer-encoding|proxy-authorization|x-starlit-token)$/i;
function constantEqual(a,b) { var x = new Buffer(String(a || "")), y = new Buffer(String(b || "")); if(x.length !== y.length)return false;var diff=0;for(var i=0;i<x.length;i++)diff |= x[i]^y[i];return diff===0; }
function cleanHeaders(headers) {
  var result={};Object.keys(headers || {}).forEach(function(name){var value=String(headers[name]);if(!/^[A-Za-z0-9-]{1,100}$/.test(name)||/[\r\n]/.test(value)||value.length>8192)throw new Error("Invalid source header");if(!forbidden.test(name))result[name.toLowerCase()]=value;});return result;
}
function rewriteHls(text, base, ticket) {
  return text.split(/\r?\n/).map(function(line){
    if(!line.trim())return line;
    if(line.charAt(0)!=="#")return ticket(url.resolve(base,line.trim()));
    return line.replace(/URI="([^"]+)"/g,function(_,child){return 'URI="'+ticket(url.resolve(base,child))+'"';});
  }).join("\n");
}
function rewriteDash(text, base, ticket) {
  if (/<!DOCTYPE|<!ENTITY/i.test(text)) throw new Error("Unsupported DASH document");
  function escaped(value){return value.replace(/&/g,"&amp;").replace(/"/g,"&quot;");}
  text=text.replace(/<BaseURL([^>]*)>([^<]+)<\/BaseURL>/g,function(_,attrs,child){return '<BaseURL'+attrs+'>'+escaped(ticket(url.resolve(base,child.replace(/&amp;/g,"&")),true))+'</BaseURL>';});
  text=text.replace(/(media|initialization|sourceURL)="([^"]+)"/g,function(_,attr,child){return attr+'="'+escaped(ticket(url.resolve(base,child.replace(/&amp;/g,"&"))))+'"';});
  return text;
}
module.exports=function createProtectedMediaProxy(options) {
  var sessions=Object.create(null), count=0, port=options.port || 2710;
  var agents={"http:":new http.Agent({lookup:network.safeLookup}),"https:":new https.Agent({lookup:network.safeLookup,rejectUnauthorized:true})};
  function expire(){var now=Date.now();Object.keys(sessions).forEach(function(key){if(sessions[key].expires<now){delete sessions[key];count--;}});}
  function ticket(source,headers,credentialOrigin,base) {
    network.origin(source);expire();if(count>=1024)throw new Error("Media session limit reached");
    var key=crypto.randomBytes(32).toString("hex"), variables=String(source).match(/\$[A-Za-z]+(?:%0?\d+d)?\$/g) || [];
    variables=variables.filter(function(value,index,all){return all.indexOf(value)===index;});
    sessions[key]={source:source,headers:headers,credentialOrigin:credentialOrigin,variables:variables,base:!!base,expires:Date.now()+1800000};count++;
    return "http://127.0.0.1:"+port+"/proxy/"+key+(variables.length?"/"+variables.join("/"):base?"/":"");
  }
  function fail(res,code){if(res.finished || res.destroyed)return;if(!res.headersSent)res.writeHead(code,{"Content-Type":"text/plain","Cache-Control":"no-store"});res.end("STARLIT_MEDIA_PROXY_UNAVAILABLE");}
  function fetchMedia(source,session,req,res,hops) {
    var parsed;try { parsed=url.parse(source);network.origin(parsed); } catch(_){fail(res,403);return;}
    var headers={};Object.keys(session.headers).forEach(function(name){if(network.origin(parsed)===session.credentialOrigin || portable.test(name))headers[name]=session.headers[name];});
    headers["accept-encoding"]="identity";
    if(req.headers.range && /^bytes=\d*-\d*$/.test(req.headers.range))headers.range=req.headers.range;
    var transport=parsed.protocol==="https:"?https:http;
    var upstream=transport.request({protocol:parsed.protocol,hostname:parsed.hostname,port:parsed.port,path:parsed.path,method:req.method,headers:headers,agent:agents[parsed.protocol]},function(response){
      if([301,302,303,307,308].indexOf(response.statusCode)>=0){
        var destination=url.resolve(source,response.headers.location || "");response.resume();
        if(hops>=5 || (parsed.protocol==="https:" && url.parse(destination).protocol!=="https:")){fail(res,502);return;}
        fetchMedia(destination,session,req,res,hops+1);return;
      }
      if(response.statusCode>=400){response.resume();fail(res,response.statusCode);return;}
      var mime=String(response.headers["content-type"] || ""), pathname=url.parse(source).pathname || "";
      var hls=/mpegurl/i.test(mime)||/\.m3u8$/i.test(pathname),dash=/dash\+xml/i.test(mime)||/\.mpd$/i.test(pathname);
      if((hls||dash) && req.method!=="HEAD") {
        var chunks=[],size=0;
        response.on("data",function(chunk){size+=chunk.length;if(size>1048576){response.destroy();fail(res,502);}else chunks.push(chunk);});
        response.on("end",function(){if(res.finished || res.destroyed)return;try {
          if(response.headers["content-encoding"] && response.headers["content-encoding"]!=="identity")throw new Error("Compressed manifest unsupported");
          var create=function(child,base){return ticket(child,session.headers,session.credentialOrigin,base);};
          var text=Buffer.concat(chunks).toString("utf8"),body=hls?rewriteHls(text,source,create):rewriteDash(text,source,create);
          res.writeHead(200,{"Content-Type":hls?"application/vnd.apple.mpegurl":"application/dash+xml","Content-Length":Buffer.byteLength(body),"Cache-Control":"no-store"});res.end(body);
        } catch(_){fail(res,502);} });
        response.on("error",function(){fail(res,502);});return;
      }
      var outgoing={"Cache-Control":"no-store"};
      ["content-type","content-length","content-range","accept-ranges","etag","last-modified","content-encoding"].forEach(function(name){if(response.headers[name]!=null)outgoing[name.replace(/(^|-)([a-z])/g,function(_,dash,letter){return dash+letter.toUpperCase();})]=response.headers[name];});
      res.writeHead(response.statusCode,outgoing);response.on("error",function(){res.destroy();});response.pipe(res);
    });
    upstream.setTimeout(15000,function(){upstream.destroy();fail(res,504);});
    upstream.on("error",function(){fail(res,502);});
    req.on("aborted",function(){upstream.destroy();});res.on("close",function(){upstream.destroy();});upstream.end();
  }
  function handler(req,res) {
    var path=url.parse(req.url).pathname || "";if(path.indexOf("/proxy/")!==0)return false;
    if(path==="/proxy/session") {
      if(req.method!=="POST" || !constantEqual(req.headers["x-starlit-token"],options.controlToken)){fail(res,403);return true;}
      var body="",size=0;req.on("data",function(chunk){size+=chunk.length;if(size>65536){req.destroy();}else body+=chunk;});req.on("end",function(){try {
        var input=JSON.parse(body),source=String(input.url || ""),headers=cleanHeaders(input.headers);
        var origin=network.origin(source);if(url.parse(source).protocol!=="https:" && Object.keys(headers).some(function(name){return !portable.test(name);}))throw new Error("HTTPS required for protected media");
        var output=ticket(source,headers,origin,false);res.writeHead(200,{"Content-Type":"application/json","Cache-Control":"no-store"});res.end(JSON.stringify({url:output}));
      }catch(_){fail(res,400);}});return true;
    }
    if(path==="/proxy/clear" && req.method==="POST") { if(!constantEqual(req.headers["x-starlit-token"],options.controlToken)){fail(res,403);return true;}sessions=Object.create(null);count=0;res.writeHead(200);res.end("{}");return true; }
    if(req.method!=="GET" && req.method!=="HEAD"){fail(res,405);return true;}
    var match=/^\/proxy\/([a-f0-9]{64})(?:\/(.*))?$/.exec(path);expire();var session=match && sessions[match[1]];
    if(!session){fail(res,404);return true;}
    var source=session.source,suffix=match[2] || "";
    if(session.variables.length){var values=suffix.split("/");if(values.length!==session.variables.length || values.some(function(value){return !/^[A-Za-z0-9_.-]{1,100}$/.test(value);})){fail(res,400);return true;}session.variables.forEach(function(variable,index){source=source.split(variable).join(values[index]);});}
    else if(session.base && suffix){if(suffix.indexOf(":")>=0 || suffix.indexOf("\\")>=0){fail(res,400);return true;}source=url.resolve(source,suffix);}
    else if(suffix){fail(res,400);return true;}
    session.expires=Date.now()+1800000;fetchMedia(source,session,req,res,0);return true;
  }
  handler.close=function(){sessions=Object.create(null);count=0;Object.keys(agents).forEach(function(key){agents[key].destroy();});};
  return handler;
};
module.exports.rewriteHls=rewriteHls;
module.exports.rewriteDash=rewriteDash;
module.exports.cleanHeaders=cleanHeaders;
