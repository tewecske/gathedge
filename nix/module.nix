# NixOS module for gathedge.
#
# Deliberately does NOT enable services.postgresql or services.nginx — it only contributes
# a database, a role and a vhost to whatever the host already runs. That way a second app
# on the same machine merges in without conflict: one Postgres cluster with many databases,
# one nginx with many vhosts.
{ config, lib, pkgs, ... }:
let
  cfg = config.services.gathedge;
  inherit (lib) mkIf mkOption mkEnableOption mkBefore types;
in
{
  options.services.gathedge = {
    enable = mkEnableOption "the gathedge application";

    package = mkOption {
      type = types.package;
      default = pkgs.gathedge-backend;
      defaultText = lib.literalExpression "pkgs.gathedge-backend";
      description = "Backend package providing bin/gathedge-backend.";
    };

    webPackage = mkOption {
      type = types.package;
      default = pkgs.gathedge-web;
      defaultText = lib.literalExpression "pkgs.gathedge-web";
      description = "Built SPA assets served by nginx as the vhost root.";
    };

    hostName = mkOption {
      type = types.str;
      example = "gathedge.lan";
      description = ''
        nginx virtual host name. It is also set as the default vhost, so reaching the
        server by bare IP works too.
      '';
    };

    publicBaseUrl = mkOption {
      type = types.str;
      example = "http://gathedge.lan";
      description = ''
        Origin the app builds user-visible links from (the email confirmation link, the Google
        OAuth redirect). Must match how users actually reach the server, or those links
        will point somewhere unreachable.
      '';
    };

    port = mkOption {
      type = types.port;
      default = 8080;
      description = ''
        Loopback port the backend binds. Not exposed: nginx proxies /api to it, which is
        what keeps the SPA same-origin with the API (the X-Requested-With CSRF check
        depends on that, since the backend runs no CORS middleware).
      '';
    };

    openFirewall = mkOption {
      type = types.bool;
      default = true;
      description = "Open the HTTP port in the firewall.";
    };

    environmentFile = mkOption {
      type = types.path;
      example = "/var/lib/secrets/gathedge.env";
      description = ''
        systemd EnvironmentFile holding the secrets, kept out of the Nix store (the store
        is world-readable). Must define DB_PASSWORD; should define BOOTSTRAP_ADMIN_EMAIL
        and BOOTSTRAP_ADMIN_PASSWORD; may define GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET
        and GOOGLE_REDIRECT_URI.

        Mode 0400, owned by root. DB_PASSWORD is also read by the gathedge-db-password
        unit, which sets the Postgres role's password to match.
      '';
    };

    database = {
      name = mkOption {
        type = types.str;
        default = "gathedge";
        description = "Database created in the host's Postgres cluster.";
      };
      user = mkOption {
        type = types.str;
        default = "gathedge";
        description = "Postgres role owning that database.";
      };
    };
  };

  config = mkIf cfg.enable {

    # Additive only — the host turns the cluster on.
    services.postgresql = {
      ensureDatabases = [ cfg.database.name ];
      ensureUsers = [{
        name = cfg.database.user;
        ensureDBOwnership = true;
      }];
      # pgjdbc speaks TCP only (no unix socket without junixsocket), so the backend
      # connects over loopback with a password. mkBefore matters: the NixOS defaults are
      # themselves mkAfter and start with blanket `trust` lines, which would match first.
      authentication = mkBefore ''
        host ${cfg.database.name} ${cfg.database.user} 127.0.0.1/32 scram-sha-256
        host ${cfg.database.name} ${cfg.database.user} ::1/128      scram-sha-256
      '';
    };

    # services.postgresql.ensureUsers cannot set a password (the option was removed from
    # nixpkgs), so set it here from the same secret file the backend reads. psql's :'var'
    # interpolation quotes the value properly, so a password with a quote in it is safe.
    systemd.services.gathedge-db-password = {
      description = "Set the gathedge Postgres role password";
      after = [ "postgresql.service" "postgresql-setup.service" ];
      requires = [ "postgresql.service" ];
      wantedBy = [ "multi-user.target" ];
      before = [ "gathedge-backend.service" ];
      serviceConfig = {
        Type = "oneshot";
        RemainAfterExit = true;
        User = "postgres";
        Group = "postgres";
        LoadCredential = "env:${toString cfg.environmentFile}";
      };
      script = ''
        set -euo pipefail
        pw=$(${pkgs.gnugrep}/bin/grep -m1 '^DB_PASSWORD=' "$CREDENTIALS_DIRECTORY/env" | cut -d= -f2-)
        ${config.services.postgresql.package}/bin/psql \
          --no-psqlrc --quiet --tuples-only \
          -v pw="$pw" \
          -c "ALTER ROLE ${cfg.database.user} WITH LOGIN PASSWORD :'pw'"
      '';
    };

    systemd.services.gathedge-backend = {
      description = "gathedge backend (ZIO HTTP)";
      after = [ "network.target" "postgresql.service" "gathedge-db-password.service" ];
      requires = [ "postgresql.service" ];
      wantedBy = [ "multi-user.target" ];

      # Every name here is a ${?VAR} override in the backend's application.conf. Typesafe
      # Config substitution reads real environment variables, which is what systemd's
      # Environment=/EnvironmentFile= provide.
      environment = {
        # Staying on dev is required while the app is served over plain HTTP:
        # AppConfig.productionIssues refuses to boot under APP_ENV=production unless
        # SESSION_COOKIE_SECURE=true, PUBLIC_BASE_URL is https:// and DB_PASSWORD is not
        # the development default. See the security note in the README section below.
        APP_ENV = "dev";
        SERVER_HOST = "127.0.0.1";
        SERVER_PORT = toString cfg.port;
        DB_URL = "jdbc:postgresql://127.0.0.1:${toString config.services.postgresql.settings.port}/${cfg.database.name}";
        DB_USER = cfg.database.user;
        PUBLIC_BASE_URL = cfg.publicBaseUrl;
        SESSION_COOKIE_SECURE = "false";
        NETTY_MAX_THREADS = "0";
        JAVA_OPTS = "-XX:MaxRAMPercentage=75";
      };

      serviceConfig = {
        ExecStart = lib.getExe cfg.package;
        EnvironmentFile = cfg.environmentFile;

        # Flyway migrates before Server.serve and fails fast if Postgres is not reachable,
        # so a restart loop is the boot-race handling — same reasoning as the compose
        # file's `restart: unless-stopped`.
        Restart = "on-failure";
        RestartSec = "5s";

        # No state on disk: sessions and all data live in Postgres.
        DynamicUser = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
        PrivateDevices = true;
        NoNewPrivileges = true;
        RestrictAddressFamilies = [ "AF_INET" "AF_INET6" "AF_UNIX" ];
        RestrictNamespaces = true;
        LockPersonality = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectControlGroups = true;
      };
    };

    # Translation of docker/nginx.conf.
    services.nginx = {
      recommendedGzipSettings = lib.mkDefault true;
      recommendedProxySettings = lib.mkDefault true;
      clientMaxBodySize = lib.mkDefault "1m";

      virtualHosts.${cfg.hostName} = {
        default = true;
        root = "${cfg.webPackage}";

        # No trailing path, so the /api prefix is preserved — backend routes are mounted
        # under /api.
        locations."/api/".proxyPass = "http://127.0.0.1:${toString cfg.port}";

        locations."/assets/".extraConfig = ''
          add_header Cache-Control "public, immutable, max-age=31536000";
          try_files $uri =404;
        '';

        # Fallback to index.html is what makes Waypoint's HTML5 history routing survive a
        # reload on a deep link.
        locations."/".extraConfig = ''
          add_header Cache-Control "no-cache";
          try_files $uri $uri/ /index.html;
        '';
      };
    };

    networking.firewall.allowedTCPPorts = mkIf cfg.openFirewall [ 80 ];

    assertions = [
      {
        assertion = config.services.postgresql.enable;
        message = "services.gathedge needs services.postgresql.enable = true on the host.";
      }
      {
        assertion = config.services.nginx.enable;
        message = "services.gathedge needs services.nginx.enable = true on the host.";
      }
    ];
  };
}
