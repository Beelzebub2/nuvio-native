// Rebuild only dist/css/bundle.css (stylesheet iteration against an existing build).
process.env.STARLIT_BUILD_CSS_ONLY = "1";
await import("./build.mjs");
