# The sbt half of the build: the backend's JavaAppPackaging stage directory and the
# Scala.js linker output, produced by a single sbt invocation so there is only one
# dependency hash to maintain.
{ lib
, mkSbtDerivation
, sbt
, jdk
, makeWrapper
, runCommand
, coreutils
, gawk
, gnugrep
, gnused
, src
, logbackConfig
, version ? "0.1.0"
}:
let
  # nixpkgs' sbt bakes `-java-home ${jre.home}` into conf/sbtopts, which takes precedence
  # over JAVA_HOME — so overriding the package's jre is the only way to actually pin the
  # JDK. withOverrides feeds the result to both the build and the dependency derivation.
  mkSbt = mkSbtDerivation.withOverrides {
    sbt = sbt.override { jre = jdk; };
  };

  scala = mkSbt {
    pname = "gathedge-scala";
    inherit version src;

    # out = modules/backend/target/universal/stage  (bin/backend + lib/*.jar)
    # js  = modules/frontend/target/scala-*/frontend-opt  (consumed by nix/web.nix)
    outputs = [ "out" "js" ];

    # Refresh whenever build.sbt or project/plugins.sbt dependencies change:
    # set to lib.fakeSha256, run `nix build .#backend`, copy the `got:` value.
    depsSha256 = "sha256-RiA3jaG10ndZ5wDgD3zCx0OKX+12Xv7hrHP14ic9L4k=";

    # sbt-derivation prepends its own sbt; this is for anything the build shells out to.
    nativeBuildInputs = [ jdk ];

    # The dependency derivation is the only phase with network access, so it has to
    # resolve everything the real build needs: the sbt launcher for the version pinned
    # in project/build.properties, sbt-scalajs, sbt-native-packager, and every compile
    # and link dependency. Running the actual tasks is the only thing that guarantees
    # the build phase is offline-clean.
    depsWarmupCommand = "sbt backend/stage frontend/fullLinkJS";

    JAVA_HOME = "${jdk}";

    buildPhase = ''
      runHook preBuild
      sbt backend/stage frontend/fullLinkJS
      runHook postBuild
    '';

    installPhase = ''
      runHook preInstall
      cp -a modules/backend/target/universal/stage $out
      cp -a modules/frontend/target/scala-*/frontend-opt $js
      runHook postInstall
    '';

    meta = {
      description = "gathedge backend (staged) and Scala.js linker output";
      platforms = lib.platforms.unix;
    };
  };

  # Mirrors the Dockerfile ENTRYPOINT: the in-repo logback.xml writes to a relative
  # logs/ dir, which is unwritable under a hardened systemd unit, so point at the
  # stdout-only config instead. Journald takes it from there.
  #
  # The PATH prefix is not optional. sbt-native-packager's launcher is a bash script that
  # shells out to ordinary Unix tools, and its `java_version_check` reads the version out of
  # `java -version` through awk. Under a systemd unit the PATH is systemd's own default
  # (coreutils, findutils, grep, sed) with no awk in it, so that pipeline yields an empty
  # string and the script exits with the singularly misleading
  #
  #   No java installations was detected.
  #
  # even though JAVA_HOME below points at a perfectly good JDK. Setting JAVA_HOME alone is
  # therefore not enough to make this runnable outside an interactive shell.
  backend = runCommand "gathedge-backend-${version}"
    {
      nativeBuildInputs = [ makeWrapper ];
      meta.mainProgram = "gathedge-backend";
      passthru = { inherit scala; };
    }
    ''
      mkdir -p $out/bin
      makeWrapper ${scala}/bin/backend $out/bin/gathedge-backend \
        --set JAVA_HOME ${jdk} \
        --prefix PATH : ${lib.makeBinPath [ jdk coreutils gawk gnugrep gnused ]} \
        --add-flags "-Dlogback.configurationFile=${logbackConfig}"

      # The dictionary loader, off the same staged classpath: sbt-native-packager's launcher takes
      # `-main <classname>`, so this needs no second sbt output. It exists because the deployment has
      # no repository and no dump — data/ is excluded from the source filter in flake.nix — so the
      # only way words reach the server's database is a seed file built elsewhere and named with
      # `--seed <path>`. See data/dictionary/README.md.
      #
      # Not a systemd unit: it is run by hand, once, with the DB_* variables the backend's unit sets.
      makeWrapper ${scala}/bin/backend $out/bin/gathedge-dictionary-import \
        --set JAVA_HOME ${jdk} \
        --prefix PATH : ${lib.makeBinPath [ jdk coreutils gawk gnugrep gnused ]} \
        --add-flags "-Dlogback.configurationFile=${logbackConfig}" \
        --add-flags "-main gathedge.backend.tools.DictionaryImport"
    '';
in
# `backend.scala.js` is the Scala.js linker output, consumed by nix/web.nix.
backend
