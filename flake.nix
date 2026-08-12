{
  description = "gathedge — ZIO HTTP backend + Scala.js/Laminar SPA";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";
    sbt-derivation = {
      url = "github:zaninime/sbt-derivation";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, sbt-derivation }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system:
        f (import nixpkgs {
          inherit system;
          overlays = [ sbt-derivation.overlays.default self.overlays.default ];
        }));

      lib = nixpkgs.lib;

      # Two source filters rather than one: the sbt build is expensive, and sharing a
      # single filtered root would rebuild it whenever a CSS file changed.
      mkSrc = name: roots: lib.cleanSourceWith {
        inherit name;
        src = ./.;
        filter = path: type:
          let
            rel = lib.removePrefix "${toString ./.}/" (toString path);
            base = baseNameOf rel;
            included = lib.any
              (p: rel == p
                || lib.hasPrefix "${p}/" rel # inside a wanted path
                || lib.hasPrefix "${rel}/" p # ancestor of a wanted path
              )
              roots;
            excluded = lib.elem base [
              "target"
              "node_modules"
              "dist"
              ".metals"
              ".bloop"
              ".bsp"
              "logs"
              "data"
              # Metals writes project/metals.sbt and project/project/metals.sbt locally.
              # Letting them in would add sbt-metals + sbt-debug-adapter to the build and,
              # worse, invalidate depsSha256 every time Metals updates itself.
              "metals.sbt"
            ]
            # basename is "project" here too, so this has to match on the path
            || rel == "project/project";
          in
          included && !excluded;
      };

      # `.jvmopts` carries -Xmx4G, which the sbt build needs.
      scalaSrc = mkSrc "gathedge-scala-src" [
        "build.sbt"
        "project"
        "modules"
        ".jvmopts"
        ".scalafmt.conf"
      ];

      # modules/frontend/src is here because web/main.css declares
      # `@source "../modules/frontend/src"` — Tailwind scans the Scala sources.
      webSrc = mkSrc "gathedge-web-src" [
        "web"
        "modules/frontend/src"
      ];
    in
    {
      overlays.default = final: prev: {
        gathedge-backend = final.callPackage ./nix/scala.nix {
          src = scalaSrc;
          jdk = final.jdk21;
          logbackConfig = ./docker/logback.xml;
        };

        gathedge-web = final.callPackage ./nix/web.nix {
          src = webSrc;
          nodejs = final.nodejs_22;
          scalaJs = final.gathedge-backend.scala.js;
        };
      };

      packages = forAllSystems (pkgs: {
        inherit (pkgs) gathedge-backend gathedge-web;
        backend = pkgs.gathedge-backend;
        web = pkgs.gathedge-web;
        default = pkgs.gathedge-backend;
      });

      nixosModules.default = { ... }: {
        imports = [ ./nix/module.nix ];
        nixpkgs.overlays = [ sbt-derivation.overlays.default self.overlays.default ];
      };

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          # Same versions the packages build with: the Dockerfile's JDK 21 / Node 22,
          # not whatever the host happens to have.
          packages = [
            pkgs.jdk21
            pkgs.sbt
            pkgs.nodejs_22
            pkgs.postgresql # psql, for poking at the database
          ];
          JAVA_HOME = "${pkgs.jdk21}";
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixpkgs-fmt);
    };
}
