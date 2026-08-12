# The SPA: `vite build`, with the plugin's shell-out to sbt short-circuited.
#
# @scala-js/vite-plugin-scalajs spawns `sbt --batch ... "print frontend/fullLinkJSOutput"`
# from the repo root and uses the LAST LINE of stdout as the directory to resolve
# `scalajs:main.js` against. Putting a stub `sbt` on PATH that prints the already-built
# store path makes the build hermetic without patching web/vite.config.ts.
{ lib
, buildNpmPackage
, writeShellScriptBin
, nodejs
, src
, scalaJs # the `js` output of nix/scala.nix
, version ? "0.1.0"
}:
let
  fakeSbt = writeShellScriptBin "sbt" ''
    echo "${scalaJs}"
  '';
in
buildNpmPackage {
  pname = "gathedge-web";
  inherit version src nodejs;

  # src is the repo root, not web/ — web/main.css declares
  # `@source "../modules/frontend/src"`, so Tailwind scans the Scala sources for class
  # names. A web/-only src produces a CSS file missing most utilities, with no error.
  sourceRoot = "${src.name}/web";

  # nix run nixpkgs#prefetch-npm-deps -- web/package-lock.json
  npmDepsHash = lib.fakeHash;

  nativeBuildInputs = [ fakeSbt ];

  # `npm run build` => vite build => mode=production => fullLinkJSOutput => fakeSbt.
  npmBuildScript = "build";

  installPhase = ''
    runHook preInstall
    cp -a dist $out
    runHook postInstall
  '';

  meta = {
    description = "gathedge single-page app (Scala.js + Laminar), built static assets";
    platforms = lib.platforms.all;
  };
}
