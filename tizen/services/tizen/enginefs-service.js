/* Starlit packaged streaming Web Service. ES5 for legacy Samsung runtimes. */
"use strict";
var runtime=null,control=null,proxy=null;
function stop(){if(proxy){proxy.close();proxy=null;}if(runtime){runtime.stop();runtime=null;}if(control){control.close();control=null;}}
module.exports.onStart=function(){
  if(runtime)return;
  try {
    control=require("./runtime/trusted-control.cjs")({uiAppId:"StarlitTV1.StarlitTV"});
    proxy=require("./runtime/protected-media-proxy.cjs")({controlToken:control.token,port:2710});
    runtime=require("./runtime/media-http.cjs").start({port:2710,controlToken:control.token,allowNullOrigin:true,proxyHandler:proxy});
  }catch(_){stop();console.error("STARLIT_LOCAL_STREAMING_UNAVAILABLE");}
};
module.exports.onExit=stop;
module.exports.onStop=stop;
