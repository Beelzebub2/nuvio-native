import releaseVariant from "@jitl/quickjs-asmjs-mjs-release-sync";
import { newQuickJSWASMModuleFromVariant } from "quickjs-emscripten-core";

// Classic worker asset. All guest code still runs inside an isolated QuickJS
// context and must use the existing scoped fetch bridge and execution quotas.
let modulePromise = null;
globalThis.__STARLIT_LOAD_ASMJS_QUICKJS__ = function () {
  if (!modulePromise) {
    modulePromise = newQuickJSWASMModuleFromVariant(releaseVariant).catch((error) => {
      modulePromise = null;
      throw error;
    });
  }
  return modulePromise;
};
